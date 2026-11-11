package app.bloqueador.vpn

import android.util.Log
import app.bloqueador.dns.DnsMessage
import app.bloqueador.dns.IpPacket
import app.bloqueador.dns.UdpPacket
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Reenvía las consultas permitidas al DNS real por **un único socket** protegido (fuera del túnel).
 *
 * Antes se creaba un socket por consulta (crear + `protect()` + un hilo esperando hasta 4 s).
 * Ahora cada consulta sale con un identificador DNS propio; un solo hilo recibe las respuestas,
 * les devuelve el identificador original y las entrega al cliente que preguntó.
 *
 * Las consultas sin respuesta se descartan a los [TIMEOUT_MS] sin temporizadores: se limpian al
 * enviar otras. El cliente (el resolvedor de Android) reintenta por su cuenta.
 */
class DnsForwarder(
    private val protect: (DatagramSocket) -> Boolean,
    private val upstream: () -> List<InetAddress>,
    private val reply: (ByteArray) -> Unit,
) {
    private class Pending(val request: UdpPacket, val originalId: Int, val sentAt: Long)

    private val pending = ConcurrentHashMap<Int, Pending>()
    private var nextId = (System.nanoTime() and 0xFFFF).toInt()

    @Volatile
    private var socket: DatagramSocket? = null
    private var receiver: Thread? = null

    fun start() {
        val s = DatagramSocket()
        if (!protect(s)) Log.w(TAG, "No se pudo proteger el socket de reenvío")
        socket = s
        receiver = Thread({ receiveLoop(s) }, "dns-forwarder").also { it.start() }
    }

    fun stop() {
        socket?.close() // desbloquea receive() con una excepción y el hilo termina
        socket = null
        receiver = null
        pending.clear()
    }

    /** Envía [request] (consulta ya analizada) al primer servidor DNS disponible. */
    fun forward(request: UdpPacket) {
        val s = socket ?: return
        val server = upstream().firstOrNull() ?: return
        val now = System.currentTimeMillis()
        if (pending.size > CLEANUP_THRESHOLD) expire(now)

        val query = request.payload()
        val originalId = DnsMessage.u16(query, 0)
        val id = allocateId() ?: return // 65.536 consultas en vuelo: imposible en la práctica
        DnsMessage.put16(query, 0, id)
        pending[id] = Pending(request, originalId, now)
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
                DnsMessage.put16(response, 0, p.originalId)
                reply(IpPacket.buildUdpReply(p.request, response))
            } catch (e: IOException) {
                if (!s.isClosed) Log.d(TAG, "Error recibiendo: ${e.message}")
            }
        }
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

    companion object {
        private const val TAG = "DnsForwarder"
        private const val MAX_PACKET = 4096
        private const val TIMEOUT_MS = 5_000L
        private const val CLEANUP_THRESHOLD = 256
    }
}
