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
 * Nunca hay más de [MAX_PENDING] consultas en vuelo.
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
) {
    private class Pending(
        val request: UdpPacket,
        val originalId: Int,
        val sentAt: Long,
        val query: ByteArray,
        val server: InetAddress,
        val domain: String,
    )

    private val pending = ConcurrentHashMap<Int, Pending>()
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
    private var receiver: Thread? = null
    private var tcpWorker: ExecutorService? = null

    fun start() {
        val s = DatagramSocket()
        if (!protect(s)) Log.w(TAG, "No se pudo proteger el socket de reenvío")
        socket = s
        tcpWorker = Executors.newSingleThreadExecutor { Thread(it, "dns-tcp") }
        receiver = Thread({ receiveLoop(s) }, "dns-forwarder").also { it.start() }
    }

    fun stop() {
        socket?.close() // desbloquea receive() con una excepción y el hilo termina
        socket = null
        receiver = null
        tcpWorker?.shutdownNow()
        tcpWorker = null
        pending.clear()
    }

    /** Envía [request] (consulta ya analizada, de [domain]) al servidor DNS preferido. */
    fun forward(request: UdpPacket, domain: String) {
        val s = socket ?: return
        val servers = upstream()
        if (servers.isEmpty()) return
        val now = System.currentTimeMillis()
        if (servers != lastServers) {
            // Otra red u otros servidores: se empieza de nuevo por el preferido.
            lastServers = servers
            serverShift.set(0)
            unanswered.set(0)
        } else if (serverShift.get() != 0 && now - shiftedAt > PRIMARY_RETRY_MS) {
            // El preferido pudo caerse solo un rato: se vuelve a probar de vez en cuando.
            serverShift.set(0)
            unanswered.set(0)
        }
        if (pending.size > CLEANUP_THRESHOLD || unanswered.get() >= FAILOVER_AFTER) expire(now, servers.size)
        // Límite de memoria: si una app inunda de consultas sin respuesta, se descartan las nuevas
        // (el cliente reintentará) en lugar de acumularlas.
        if (pending.size >= MAX_PENDING) return
        val server = servers[Math.floorMod(serverShift.get(), servers.size)]

        val query = request.payload()
        val originalId = DnsMessage.u16(query, 0)
        val id = allocateId() ?: return // 65.536 consultas en vuelo: imposible en la práctica
        DnsMessage.put16(query, 0, id)
        pending[id] = Pending(request, originalId, now, query, server, domain)
        unanswered.incrementAndGet()
        try {
            s.send(DatagramPacket(query, query.size, server, port))
            lastSentAt = now
            if (waitingSince == 0L) waitingSince = now
        } catch (e: IOException) {
            pending.remove(id)
            Log.d(TAG, "No se pudo enviar a ${server.hostAddress}: ${e.message}")
        }
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
                if (pending.remove(id) == null) continue
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
                if (!protectTcp(sock)) Log.w(TAG, "No se pudo proteger el socket TCP")
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

    /** Identificador aleatorio que no esté en vuelo (un atacante no puede predecir el siguiente). */
    private fun allocateId(): Int? {
        repeat(64) {
            val id = random.nextInt(0x10000)
            if (!pending.containsKey(id)) return id
        }
        return (0 until 0x10000).firstOrNull { !pending.containsKey(it) }
    }

    /** Descarta lo caducado y, si el servidor preferido no contesta, pasa al siguiente. */
    private fun expire(now: Long, serverCount: Int) {
        pending.entries.removeIf { now - it.value.sentAt > TIMEOUT_MS }
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
    val inFlight: Int get() = pending.size

    /**
     * true si se están enviando consultas pero ningún servidor contesta desde hace [SILENT_ALERT_MS]
     * (sin conexión real, o la red bloquea el DNS). Sin consultas recientes no se considera un problema.
     */
    fun isUnresponsive(now: Long = System.currentTimeMillis()): Boolean {
        val since = waitingSince
        return since > 0 && now - since > SILENT_ALERT_MS && now - lastSentAt < RECENT_MS
    }

    companion object {
        private const val TAG = "DnsForwarder"
        private const val MAX_PACKET = 4096
        private const val MAX_TCP_RESPONSE = 16 * 1024
        private const val TIMEOUT_MS = 5_000L
        private const val CLEANUP_THRESHOLD = 256
        private const val TCP_TIMEOUT_MS = 4_000
        private const val FAILOVER_AFTER = 6
        private const val FAILOVER_SILENCE_MS = 2_500L
        internal const val MAX_PENDING = 1024
        private const val PRIMARY_RETRY_MS = 5 * 60_000L
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
            // Algunos servidores devuelven los errores sin repetir la pregunta.
            if (DnsMessage.u16(response, 4) == 0) return (response[3].toInt() and 0x0F) != 0
            val q = DnsMessage.parseQuestion(query) ?: return false
            val r = DnsMessage.parseQuestion(response) ?: return false
            return q.name == r.name && q.type == r.type && q.dnsClass == r.dnsClass
        }
    }
}
