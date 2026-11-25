package app.bloqueador.vpn

import app.bloqueador.R
import app.bloqueador.UiText
import android.util.Log
import app.bloqueador.dns.DnsMessage
import app.bloqueador.dns.IpPacket
import app.bloqueador.dns.UdpPacket
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Servicio de DNS cifrado (lista cerrada: se elige en Ajustes, no se escribe a mano). */
enum class DotProvider(val label: UiText, val description: UiText, val hostname: String, val addresses: List<String>) {
    QUAD9(
        UiText.raw("Quad9"), UiText.res(R.string.dot_quad9_desc),
        "dns.quad9.net", listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"),
    ),
    CLOUDFLARE(
        UiText.raw("Cloudflare"), UiText.res(R.string.dot_cloudflare_desc),
        "one.one.one.one", listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
    ),
    MULLVAD(
        UiText.raw("Mullvad"), UiText.res(R.string.dot_mullvad_desc),
        "dns.mullvad.net", listOf("194.242.2.2", "2a07:e340::2"),
    );

    /** Direcciones fijas: no hace falta resolver el nombre del servicio (no hay DNS antes del DNS). */
    val inetAddresses: List<InetAddress> get() = addresses.map { InetAddress.getByName(it) }
}

/**
 * DNS sobre TLS (RFC 7858) a un servicio de [DotProvider]: una conexión TLS persistente al puerto 853,
 * protegida (sale por la red real, no por el túnel), con varias consultas en vuelo a la vez y
 * respuestas en cualquier orden (RFC 7766).
 *
 * - El certificado se comprueba contra el nombre del servicio (no solo la IP): nadie en la red puede
 *   hacerse pasar por él.
 * - Si la conexión se cae, se abre otra al llegar la siguiente consulta; las que estaban en vuelo se
 *   reenvían una vez por la nueva.
 * - Si el servidor preferido no conecta, se prueba el siguiente de la lista.
 * - Nunca hay más de [MAX_PENDING] consultas en vuelo, ni más de [MAX_PENDING_PER_APP] de una app.
 * - Todo lo lento (conectar, el saludo TLS, escribir) va en un hilo propio: el hilo que lee el túnel
 *   nunca espera a la red.
 */
class DotResolver(
    private val hostname: String,
    private val servers: List<InetAddress>,
    private val protect: (Socket) -> Boolean,
    private val reply: (ByteArray) -> Unit,
    private val port: Int = 853,
    private val sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
) : DnsResolver {

    private class Pending(
        val request: UdpPacket,
        val originalId: Int,
        val query: ByteArray,
        val domain: String,
        val app: String,
        @Volatile var sentAt: Long,
        @Volatile var attempts: Int = 0,
    )

    private class Connection(val socket: SSLSocket, val out: DataOutputStream)

    private val pending = ConcurrentHashMap<Int, Pending>()
    private val perApp = ConcurrentHashMap<String, AtomicInteger>()
    private val random = SecureRandom()

    @Volatile private var connection: Connection? = null
    @Volatile private var writer: ExecutorService? = null
    @Volatile private var serverIndex = 0
    @Volatile private var lastSentAt = 0L
    @Volatile private var waitingSince = 0L
    @Volatile private var lastCleanup = 0L
    @Volatile override var lastAnswerAt = 0L
        private set

    /** Sin red (o sin ningún servidor alcanzable) no se reintenta conectar hasta [retryAt]. */
    @Volatile private var retryAt = 0L
    @Volatile private var backoffMs = 0L

    override fun start() {
        writer = Executors.newSingleThreadExecutor { Thread(it, "dns-tls") }
    }

    override fun stop() {
        writer?.shutdownNow()
        writer = null
        connection?.let { runCatching { it.socket.close() } }
        connection = null
        pending.clear()
        perApp.clear()
    }

    override fun forward(request: UdpPacket, domain: String, app: String?) {
        val w = writer ?: return
        val now = System.currentTimeMillis()
        expire(now)
        val key = app ?: "?"
        val query = request.payload()
        if (pending.size >= MAX_PENDING || (perApp[key]?.get() ?: 0) >= MAX_PENDING_PER_APP) {
            reply(IpPacket.buildUdpReply(request, DnsMessage.buildErrorResponse(query, DnsMessage.RCODE_SERVFAIL)))
            return
        }
        val p = Pending(request, DnsMessage.u16(query, 0), query, domain, key, now)
        val id = reserveId(p) ?: return
        DnsMessage.put16(query, 0, id)
        perApp.getOrPut(key) { AtomicInteger() }.incrementAndGet()
        lastSentAt = now
        if (waitingSince == 0L) waitingSince = now
        try {
            w.execute { send(id) }
        } catch (e: RejectedExecutionException) {
            take(id) // se está parando
        }
    }

    /** En el hilo de escritura: conecta si hace falta y envía la consulta [id]. */
    private fun send(id: Int) {
        val p = pending[id] ?: return
        val conn = connection ?: connect() ?: run {
            // Sin conexión: SERVFAIL ya (el cliente no espera en vano). Reintentará más tarde.
            take(id)?.let { p -> reply(IpPacket.buildUdpReply(p.request, DnsMessage.buildErrorResponse(p.request.payload(), DnsMessage.RCODE_SERVFAIL))) }
            return
        }
        try {
            synchronized(conn.out) {
                conn.out.writeShort(p.query.size)
                conn.out.write(p.query)
                conn.out.flush()
            }
            p.sentAt = System.currentTimeMillis()
        } catch (e: IOException) {
            Log.d(TAG, "Fallo al escribir por TLS: ${e.message}")
            drop(conn)
            retry(listOf(id))
        }
    }

    /** Abre la conexión TLS (probando los servidores en orden) y su hilo lector. */
    private fun connect(): Connection? {
        if (System.currentTimeMillis() < retryAt) return null
        val conn = tryServers()
        if (conn != null) {
            backoffMs = 0L
            retryAt = 0L
        } else {
            // Ningún servidor contesta (sin red, o la red bloquea el puerto 853): se espera cada vez más
            // antes de volver a probar, para no gastar batería. Un cambio de red lo reinicia.
            backoffMs = if (backoffMs == 0L) MIN_BACKOFF_MS else minOf(backoffMs * 2, MAX_BACKOFF_MS)
            retryAt = System.currentTimeMillis() + backoffMs
            Log.i(TAG, "Ningún servidor TLS disponible: siguiente intento en ${backoffMs / 1000} s")
        }
        return conn
    }

    override fun networkChanged() {
        backoffMs = 0L
        retryAt = 0L
    }

    private fun tryServers(): Connection? {
        repeat(servers.size) {
            val server = servers[Math.floorMod(serverIndex, servers.size)]
            val raw = Socket()
            try {
                // protect() necesita que el socket ya exista en el sistema: un Socket() de Java no lo
                // crea hasta enlazarlo o conectarlo. Se enlaza antes para que la conexión no entre al túnel.
                raw.bind(null)
                // Sin proteger, la conexión podría entrar al túnel: se descarta sin conectar.
                if (!protect(raw)) throw IOException("no se pudo proteger el socket TLS")
                raw.connect(InetSocketAddress(server, port), CONNECT_TIMEOUT_MS)
                val tls = sslFactory.createSocket(raw, hostname, port, true) as SSLSocket
                tls.sslParameters = tls.sslParameters.apply {
                    // Comprueba que el certificado es del servicio (nombre), además de la cadena de confianza.
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName(hostname))
                }
                tls.soTimeout = 0
                tls.startHandshake()
                val conn = Connection(tls, DataOutputStream(BufferedOutputStream(tls.outputStream)))
                connection = conn
                Thread({ readLoop(conn) }, "dns-tls-reader").start()
                return conn
            } catch (e: IOException) {
                Log.i(TAG, "No se pudo conectar por TLS con ${server.hostAddress}: ${e.message}")
                runCatching { raw.close() }
                serverIndex++
            }
        }
        return null
    }

    private fun readLoop(conn: Connection) {
        val input = DataInputStream(conn.socket.inputStream)
        try {
            while (true) {
                val len = input.readUnsignedShort()
                if (len < 12 || len > MAX_RESPONSE) throw IOException("longitud no válida ($len)")
                val response = ByteArray(len).also { input.readFully(it) }
                val id = DnsMessage.u16(response, 0)
                val p = pending[id] ?: continue
                if (!DnsForwarder.sameQuestion(p.query, response)) continue
                if (take(id) == null) continue
                waitingSince = 0L
                lastAnswerAt = System.currentTimeMillis()
                DnsMessage.put16(response, 0, p.originalId)
                reply(IpPacket.buildUdpReply(p.request, response))
            }
        } catch (e: IOException) {
            // El servidor cerró la conexión (inactividad) o se cayó la red: lo pendiente se reenvía.
            if (connection === conn) Log.d(TAG, "Conexión TLS cerrada: ${e.message}")
        }
        if (drop(conn)) retry(pending.keys.toList())
    }

    /** Olvida [conn] si es la vigente. true si lo era. */
    private fun drop(conn: Connection): Boolean {
        runCatching { conn.socket.close() }
        synchronized(this) {
            if (connection !== conn) return false
            connection = null
            return true
        }
    }

    /** Reenvía una vez por una conexión nueva las consultas [ids] que se quedaron sin respuesta. */
    private fun retry(ids: List<Int>) {
        val w = writer ?: return
        for (id in ids) {
            val p = pending[id] ?: continue
            if (p.attempts++ >= 1) { take(id); continue }
            runCatching { w.execute { send(id) } }
        }
    }

    private fun take(id: Int): Pending? {
        val p = pending.remove(id) ?: return null
        perApp[p.app]?.decrementAndGet()
        return p
    }

    private fun reserveId(p: Pending): Int? {
        repeat(64) {
            val id = random.nextInt(0x10000)
            if (pending.putIfAbsent(id, p) == null) return id
        }
        return (0 until 0x10000).firstOrNull { pending.putIfAbsent(it, p) == null }
    }

    private fun expire(now: Long) {
        // Con muchas en vuelo, o como mucho cada TIMEOUT_MS: las caducadas no se quedan para siempre.
        if (pending.size < CLEANUP_THRESHOLD && now - lastCleanup < TIMEOUT_MS) return
        lastCleanup = now
        pending.entries.filter { now - it.value.sentAt > TIMEOUT_MS }.forEach { take(it.key) }
    }

    override val inFlight: Int get() = pending.size

    override fun isUnresponsive(now: Long): Boolean {
        val since = waitingSince
        return since > 0 && now - since > DnsForwarder.SILENT_ALERT_MS && now - lastSentAt < RECENT_MS
    }

    companion object {
        private const val TAG = "DotResolver"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val TIMEOUT_MS = 8_000L
        private const val CLEANUP_THRESHOLD = 64
        private const val RECENT_MS = 30_000L
        internal const val MIN_BACKOFF_MS = 1_000L
        internal const val MAX_BACKOFF_MS = 15_000L
        private const val MAX_RESPONSE = 65_535
        internal const val MAX_PENDING = 1024
        internal const val MAX_PENDING_PER_APP = 256

        fun forProvider(provider: DotProvider, protect: (Socket) -> Boolean, reply: (ByteArray) -> Unit) =
            DotResolver(provider.hostname, provider.inetAddresses, protect, reply)
    }
}
