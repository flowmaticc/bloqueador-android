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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Reenvía las consultas permitidas al DNS real por **un único socket UDP** protegido (fuera del túnel).
 *
 * Cada consulta sale con un identificador DNS propio; un solo hilo recibe las respuestas, les devuelve
 * el identificador original y las entrega al cliente que preguntó. Las consultas sin respuesta se
 * descartan a los [TIMEOUT_MS] sin temporizadores: se limpian al enviar otras.
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
    private var nextId = (System.nanoTime() and 0xFFFF).toInt()

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

    /** Envía [request] (consulta ya analizada, de [domain]) al primer servidor DNS disponible. */
    fun forward(request: UdpPacket, domain: String) {
        val s = socket ?: return
        val server = upstream().firstOrNull() ?: return
        val now = System.currentTimeMillis()
        if (pending.size > CLEANUP_THRESHOLD) expire(now)

        val query = request.payload()
        val originalId = DnsMessage.u16(query, 0)
        val id = allocateId() ?: return // 65.536 consultas en vuelo: imposible en la práctica
        DnsMessage.put16(query, 0, id)
        pending[id] = Pending(request, originalId, now, query, server, domain)
        try {
            s.send(DatagramPacket(query, query.size, server, 53))
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
                if (packet.length < 12 || packet.port != 53) continue
                val id = DnsMessage.u16(buffer, 0)
                val p = pending.remove(id) ?: continue // tardía o desconocida
                val response = buffer.copyOf(packet.length)
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
                sock.connect(InetSocketAddress(p.server, 53), TCP_TIMEOUT_MS)
                val out = DataOutputStream(sock.getOutputStream())
                out.writeShort(p.query.size)
                out.write(p.query)
                out.flush()
                val input = DataInputStream(sock.getInputStream())
                val len = input.readUnsignedShort()
                ByteArray(len).also { input.readFully(it) }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Consulta TCP fallida (${p.domain}): ${e.message}")
            null
        }
        deliver(p, full?.takeIf { it.size >= 12 } ?: truncated, viaTcp = full != null)
    }

    private fun deliver(p: Pending, response: ByteArray, viaTcp: Boolean) {
        DnsMessage.put16(response, 0, p.originalId)
        if (BuildConfig.DEBUG) {
            QueryLog.answer(p.domain, System.currentTimeMillis() - p.sentAt, response[3].toInt() and 0x0F, viaTcp)
        }
        reply(IpPacket.buildUdpReply(p.request, response))
    }

    @Synchronized
    private fun allocateId(): Int? {
        repeat(0x10000) {
            nextId = (nextId + 1) and 0xFFFF
            if (!pending.containsKey(nextId)) return nextId
        }
        return null
    }

    private fun expire(now: Long) {
        pending.entries.removeIf { now - it.value.sentAt > TIMEOUT_MS }
    }

    /** ¿En vuelo? (para el registro interno de pruebas). */
    val inFlight: Int get() = pending.size

    companion object {
        private const val TAG = "DnsForwarder"
        private const val MAX_PACKET = 4096
        private const val TIMEOUT_MS = 5_000L
        private const val CLEANUP_THRESHOLD = 256
        private const val TCP_TIMEOUT_MS = 4_000

        /** Bit TC (respuesta truncada) de la cabecera DNS. */
        internal fun isTruncated(message: ByteArray): Boolean = message.size >= 12 && (message[2].toInt() and 0x02) != 0
    }
}
