package app.bloqueador.vpn

import android.util.Log
import app.bloqueador.BuildConfig
import app.bloqueador.debug.QueryLog
import app.bloqueador.dns.DnsMessage
import app.bloqueador.dns.IpPacket
import app.bloqueador.dns.UdpPacket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reenvía las consultas permitidas al DNS real por **un único socket UDP** protegido (fuera del túnel).
 *
 * Cada consulta sale con un identificador DNS propio y aleatorio; un solo hilo recibe las respuestas,
 * les devuelve el identificador original y las entrega al cliente que preguntó. Las consultas sin
 * respuesta se descartan a los [TIMEOUT_MS] sin temporizadores: se limpian al enviar otras.
 *
 * Defensa contra respuestas falsas: solo se acepta una respuesta si llega desde la dirección y el
 * puerto del servidor al que se preguntó, con un identificador en vuelo y con la misma pregunta.
 *
 * Si el servidor preferido deja de responder ([FAILOVER_AFTER] consultas seguidas sin respuesta), se
 * pasa al siguiente de la lista. Se vuelve al preferido al cambiar de red o pasados [PRIMARY_RETRY_MS].
 * Al cambiar de red se abre un socket nuevo (con otro puerto de origen).
 *
 * Nunca hay más de [MAX_PENDING] consultas en vuelo, ni más de [MAX_PENDING_PER_APP] de una misma app:
 * una app que inunda el DNS recibe SERVFAIL al pasarse, y las demás siguen resolviendo.
 *
 * Respuestas truncadas (bit TC, demasiado grandes para UDP): el cliente reintentaría por TCP contra el
 * DNS virtual del túnel, que solo atiende UDP. Por eso se pide la respuesta completa al DNS real por TCP
 * y se entrega entera por UDP (los resolvedores actuales aceptan respuestas UDP grandes con EDNS0).
 */
class DnsForwarder(
    private val protect: (DatagramSocket) -> Boolean,
    private val protectTcp: (Socket) -> Boolean,
    private val upstream: () -> List<InetAddress>,
    private val reply: (ByteArray) -> Unit,
    private val port: Int = 53,
) : DnsResolver {
    private class Pending(
        val request: UdpPacket,
        val originalId: Int,
        val sentAt: Long,
        val query: ByteArray,
        val server: InetAddress,
        val domain: String,
        val app: String,
    )

    private val pending = ConcurrentHashMap<Int, Pending>()
    private val perApp = ConcurrentHashMap<String, AtomicInteger>()
    private val random = SecureRandom()
    private val unanswered = AtomicInteger()
    private val serverShift = AtomicInteger()

    @Volatile
    private var lastServers: List<InetAddress> = emptyList()

    /** Momento del último cambio de servidor por falta de respuesta. */
    @Volatile
    private var shiftedAt = 0L

    /** Último envío y primer envío aún sin ninguna respuesta detrás (0 = todo contestado). */
    @Volatile
    private var lastSentAt = 0L
    @Volatile
    private var waitingSince = 0L

    @Volatile
    private var socket: DatagramSocket? = null
    @Volatile
    private var running = false
    /** Último intento de abrir el socket (para no reintentar en cada consulta si protect() falla). */
    @Volatile
    private var openAttemptAt = 0L
    private var receiver: Thread? = null
    private var tcpWorker: ExecutorService? = null

    override fun start() {
        running = true
        tcpWorker = Executors.newSingleThreadExecutor { Thread(it, "dns-tcp") }
        openSocket()
    }

    /**
     * Abre el socket de reenvío (protegido: sale por la red real) y su hilo receptor. Si Android no
     * deja protegerlo, se cierra sin usarlo: un socket sin proteger podría volver a entrar al túnel
     * (bucle de consultas). Queda [socket] a null y [forward] vuelve a intentarlo más tarde.
     */
    private fun openSocket(): Boolean {
        openAttemptAt = System.currentTimeMillis()
        val s = try {
            DatagramSocket()
        } catch (e: IOException) {
            Log.w(TAG, "No se pudo abrir el socket de reenvío: ${e.message}")
            socket = null
            return false
        }
        if (!protect(s)) {
            s.close()
            socket = null
            Log.w(TAG, "No se pudo proteger el socket de reenvío: no se usa")
            return false
        }
        socket = s
        receiver = Thread({ receiveLoop(s) }, "dns-forwarder").also { it.start() }
        return true
    }

    override fun stop() {
        running = false
        socket?.close() // desbloquea receive() con una excepción y el hilo termina
        socket = null
        receiver = null
        tcpWorker?.shutdownNow()
        tcpWorker = null
        pending.clear()
        perApp.clear()
    }

    /**
     * Envía [request] (consulta ya analizada, de [domain]) al servidor DNS preferido. [app] identifica
     * a quien preguntó (paquete, o null si no se sabe) para el cupo por app.
     */
    override fun forward(request: UdpPacket, domain: String, app: String?) {
        if (!running) return
        val servers = upstream()
        if (servers.isEmpty()) return
        val now = System.currentTimeMillis()
        if (servers != lastServers) {
            // Otra red u otros servidores: se empieza de nuevo por el preferido, con un socket nuevo.
            // Las consultas en vuelo por la red anterior ya no tendrían respuesta: el cliente reintenta.
            if (lastServers.isNotEmpty()) {
                socket?.close()
                pending.clear()
                perApp.clear()
                openSocket()
            }
            lastServers = servers
            serverShift.set(0)
            unanswered.set(0)
        } else if (serverShift.get() != 0 && now - shiftedAt > PRIMARY_RETRY_MS) {
            // El preferido pudo caerse solo un rato: se vuelve a probar de vez en cuando.
            serverShift.set(0)
            unanswered.set(0)
        }
        if (socket == null && now - openAttemptAt >= REOPEN_INTERVAL_MS) openSocket()
        if (socket == null) {
            // Sin socket protegido no se envía nada: SERVFAIL para que el cliente no espere en vano.
            reply(IpPacket.buildUdpReply(request, DnsMessage.buildErrorResponse(request.payload(), DnsMessage.RCODE_SERVFAIL)))
            return
        }
        if (pending.size > CLEANUP_THRESHOLD || unanswered.get() >= FAILOVER_AFTER) expire(now, servers.size)
        val key = app ?: UNKNOWN_APP
        val query = request.payload()
        // Límite de memoria y de reparto: si una app inunda de consultas sin respuesta, recibe SERVFAIL
        // (deja de esperar) en lugar de acumularlas y dejar sin hueco a las demás.
        if (pending.size >= MAX_PENDING || (perApp[key]?.get() ?: 0) >= MAX_PENDING_PER_APP) {
            reply(IpPacket.buildUdpReply(request, DnsMessage.buildErrorResponse(query, DnsMessage.RCODE_SERVFAIL)))
            return
        }
        val server = servers[Math.floorMod(serverShift.get(), servers.size)]

        val originalId = DnsMessage.u16(query, 0)
        val p = Pending(request, originalId, now, query, server, domain, key)
        val id = reserveId(p) ?: return // 65.536 consultas en vuelo: imposible en la práctica
        DnsMessage.put16(query, 0, id)
        perApp.getOrPut(key) { AtomicInteger() }.incrementAndGet()
        unanswered.incrementAndGet()
        try {
            val s = socket ?: throw IOException("socket cerrado")
            s.send(DatagramPacket(query, query.size, server, port))
            lastSentAt = now
            if (waitingSince == 0L) waitingSince = now
        } catch (e: IOException) {
            take(id)
            Log.d(TAG, "No se pudo enviar a ${server.hostAddress}: ${e.message}")
        }
    }

    /** Saca una consulta de las pendientes (y del cupo de su app). null si ya no estaba. */
    private fun take(id: Int): Pending? {
        val p = pending.remove(id) ?: return null
        perApp[p.app]?.decrementAndGet()
        return p
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buffer = ByteArray(MAX_PACKET)
        while (!s.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                s.receive(packet)
                if (packet.length < 12 || packet.port != port) continue
                val id = DnsMessage.u16(buffer, 0)
                val p = pending[id] ?: continue // tardía o desconocida
                // Solo vale la respuesta del servidor al que se preguntó y a la misma pregunta.
                if (packet.address != p.server) continue
                val response = buffer.copyOf(packet.length)
                if (!sameQuestion(p.query, response)) continue
                if (take(id) == null) continue
                unanswered.set(0)
                waitingSince = 0L
                if (isTruncated(response)) {
                    tcpWorker?.execute { answerOverTcp(p, response) }
                } else {
                    deliver(p, response, viaTcp = false)
                }
            } catch (e: IOException) {
                if (!s.isClosed) Log.d(TAG, "Error recibiendo: ${e.message}")
            }
        }
    }

    /** Pide por TCP la respuesta completa; si falla, entrega la truncada (el cliente decidirá). */
    private fun answerOverTcp(p: Pending, truncated: ByteArray) {
        val full = try {
            Socket().use { sock ->
                sock.bind(null) // protect() necesita el socket ya creado en el sistema (ver DotResolver)
                // Sin proteger, la conexión podría entrar al túnel: no se conecta y se entrega la truncada.
                if (!protectTcp(sock)) throw IOException("no se pudo proteger el socket TCP")
                sock.soTimeout = TCP_TIMEOUT_MS
                sock.connect(InetSocketAddress(p.server, port), TCP_TIMEOUT_MS)
                val out = DataOutputStream(sock.getOutputStream())
                out.writeShort(p.query.size)
                out.write(p.query)
                out.flush()
                val input = DataInputStream(sock.getInputStream())
                val len = input.readUnsignedShort()
                if (len > MAX_TCP_RESPONSE) throw IOException("respuesta demasiado grande ($len)")
                ByteArray(len).also { input.readFully(it) }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Consulta TCP fallida (${p.domain}): ${e.message}")
            null
        }
        val valid = full?.takeIf { it.size >= 12 && DnsMessage.u16(it, 0) == DnsMessage.u16(p.query, 0) && sameQuestion(p.query, it) }
        deliver(p, valid ?: truncated, viaTcp = valid != null)
    }

    private fun deliver(p: Pending, response: ByteArray, viaTcp: Boolean) {
        DnsMessage.put16(response, 0, p.originalId)
        if (BuildConfig.DEBUG) {
            QueryLog.answer(p.domain, System.currentTimeMillis() - p.sentAt, response[3].toInt() and 0x0F, viaTcp)
        }
        reply(IpPacket.buildUdpReply(p.request, response))
    }

    /**
     * Reserva un identificador aleatorio libre para [p] (un atacante no puede predecir el siguiente).
     * `putIfAbsent` comprueba y reserva a la vez: dos envíos simultáneos nunca comparten identificador.
     */
    private fun reserveId(p: Pending): Int? {
        repeat(64) {
            val id = random.nextInt(0x10000)
            if (pending.putIfAbsent(id, p) == null) return id
        }
        return (0 until 0x10000).firstOrNull { pending.putIfAbsent(it, p) == null }
    }

    /** Descarta lo caducado y, si el servidor preferido no contesta, pasa al siguiente. */
    private fun expire(now: Long, serverCount: Int) {
        pending.entries.filter { now - it.value.sentAt > TIMEOUT_MS }.forEach { take(it.key) }
        if (unanswered.get() >= FAILOVER_AFTER && serverCount > 1) {
            val oldest = pending.values.minOfOrNull { it.sentAt } ?: now
            // Solo si de verdad lleva un rato sin contestar (no por una ráfaga de consultas).
            if (now - oldest > FAILOVER_SILENCE_MS || pending.isEmpty()) {
                serverShift.incrementAndGet()
                shiftedAt = now
                unanswered.set(0)
                Log.i(TAG, "El DNS preferido no responde: se usa el siguiente")
            }
        }
    }

    /** Consultas en vuelo (para el registro interno de pruebas). */
    override val inFlight: Int get() = pending.size

    /**
     * true si se están enviando consultas pero ningún servidor contesta desde hace [SILENT_ALERT_MS]
     * (sin conexión real, o la red bloquea el DNS). Sin consultas recientes no se considera un problema.
     */
    override fun isUnresponsive(now: Long): Boolean {
        val since = waitingSince
        return since > 0 && now - since > SILENT_ALERT_MS && now - lastSentAt < RECENT_MS
    }

    companion object {
        private const val TAG = "DnsForwarder"
        /** El máximo de un datagrama UDP: con EDNS0 un servidor puede mandar más de 4 KB. */
        private const val MAX_PACKET = 65_535
        private const val UNKNOWN_APP = "?"
        internal const val MAX_PENDING_PER_APP = 256
        private const val MAX_TCP_RESPONSE = 16 * 1024
        private const val TIMEOUT_MS = 5_000L
        private const val CLEANUP_THRESHOLD = 256
        private const val TCP_TIMEOUT_MS = 4_000
        private const val FAILOVER_AFTER = 6
        private const val FAILOVER_SILENCE_MS = 2_500L
        internal const val MAX_PENDING = 1024
        private const val PRIMARY_RETRY_MS = 5 * 60_000L
        private const val REOPEN_INTERVAL_MS = 1_000L
        internal const val SILENT_ALERT_MS = 8_000L
        private const val RECENT_MS = 30_000L

        /** Bit TC (respuesta truncada) de la cabecera DNS. */
        internal fun isTruncated(message: ByteArray): Boolean = message.size >= 12 && (message[2].toInt() and 0x02) != 0

        /**
         * ¿Es [response] una respuesta a la pregunta de [query]? Compara el bit de respuesta y la
         * sección de pregunta (nombre sin distinguir mayúsculas, tipo y clase).
         */
        internal fun sameQuestion(query: ByteArray, response: ByteArray): Boolean {
            if (response.size < 12 || (response[2].toInt() and 0x80) == 0) return false
            // Sin pregunta solo se acepta FORMERR ("no entiendo tu consulta", que no puede repetirla).
            // Cualquier otra respuesta sin pregunta podría ser falsa: un atacante solo tendría que
            // acertar el identificador. Coste: un SERVFAIL sin pregunta hace esperar al cliente.
            if (DnsMessage.u16(response, 4) == 0) return (response[3].toInt() and 0x0F) == DnsMessage.RCODE_FORMERR
            val q = DnsMessage.parseQuestion(query) ?: return false
            val r = DnsMessage.parseQuestion(response) ?: return false
            return q.name == r.name && q.type == r.type && q.dnsClass == r.dnsClass
        }
    }
}
