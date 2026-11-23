package app.bloqueador.vpn

import app.bloqueador.dns.DnsMessage
import app.bloqueador.dns.IpPacket
import app.bloqueador.dns.UdpPacket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Si Android no deja proteger un socket (protect() devuelve false), ese socket no se usa: podría
 * volver a entrar al túnel y crear un bucle de consultas.
 */
class ProtectFailureTest {

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val server = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = 1500 }
    private val replies = LinkedBlockingQueue<ByteArray>()
    private val cleanup = mutableListOf<() -> Unit>()

    @After
    fun tearDown() {
        cleanup.forEach { runCatching(it) }
        server.close()
    }

    private fun request(name: String, id: Int): UdpPacket {
        val q = DnsForwarderTest.dnsQuery(name, id)
        val raw = IpPacket.buildUdp(4, byteArrayOf(10, 111, 222.toByte(), 1), byteArrayOf(10, 111, 222.toByte(), 2), 40000, 53, q)
        return IpPacket.parseUdp(raw, raw.size)!!
    }

    private fun payloadOf(p: ByteArray) = IpPacket.parseUdp(p, p.size)!!.payload()

    private fun forwarder(protect: (DatagramSocket) -> Boolean, protectTcp: Boolean = true) = DnsForwarder(
        protect = protect,
        protectTcp = { protectTcp },
        upstream = { listOf(loopback) },
        reply = { replies.add(it) },
        port = server.localPort,
    ).also { it.start(); cleanup.add(it::stop) }

    @Test
    fun `UDP sin proteger no envia nada y responde SERVFAIL`() {
        val sockets = CopyOnWriteArrayList<DatagramSocket>()
        val f = forwarder(protect = { sockets.add(it); false })

        f.forward(request("a.example", 0x1234), "a.example")

        val payload = payloadOf(replies.poll(2, TimeUnit.SECONDS)!!)
        assertEquals(0x1234, DnsMessage.u16(payload, 0))
        assertEquals(DnsMessage.RCODE_SERVFAIL, payload[3].toInt() and 0x0F)
        assertTrue("el socket sin proteger se cierra", sockets.isNotEmpty() && sockets.all { it.isClosed })
        val got = runCatching { server.receive(DatagramPacket(ByteArray(512), 512)) }
        assertTrue("al servidor no llega nada", got.exceptionOrNull() is SocketTimeoutException)
        assertEquals(0, f.inFlight)
    }

    @Test
    fun `UDP vuelve a funcionar cuando protect lo permite`() {
        val allow = AtomicBoolean(false)
        val f = forwarder(protect = { allow.get() })
        f.forward(request("a.example", 1), "a.example")
        assertNotNull(replies.poll(2, TimeUnit.SECONDS)) // SERVFAIL
        allow.set(true)
        Thread.sleep(1_100) // pasa el intervalo entre reintentos
        f.forward(request("b.example", 2), "b.example")
        val q = DatagramPacket(ByteArray(512), 512).also { server.receive(it) }
        assertTrue(q.length > 12)
    }

    @Test
    fun `TCP sin proteger no conecta y entrega la respuesta truncada`() {
        val tcp = ServerSocket(server.localPort, 5, loopback).apply { soTimeout = 3000 }
        cleanup.add { tcp.close() }
        val accepted = AtomicInteger()
        Thread { runCatching { tcp.accept().use { accepted.incrementAndGet() } } }.start()
        val f = forwarder(protect = { true }, protectTcp = false)

        f.forward(request("grande.example", 9), "grande.example")
        val q = DatagramPacket(ByteArray(2048), 2048).also { server.receive(it) }
        val data = q.data.copyOf(q.length)
        DnsMessage.put16(data, 2, 0x8380) // respuesta truncada (TC)
        server.send(DatagramPacket(data, data.size, q.address, q.port))

        val payload = payloadOf(replies.poll(5, TimeUnit.SECONDS)!!)
        assertTrue(DnsForwarder.isTruncated(payload))
        Thread.sleep(300)
        assertEquals("no se abrió ninguna conexión TCP", 0, accepted.get())
    }

    @Test
    fun `DoT sin proteger no conecta con el servidor`() {
        val tcp = ServerSocket(0, 5, loopback).apply { soTimeout = 3000 }
        cleanup.add { tcp.close() }
        val accepted = AtomicInteger()
        Thread { runCatching { tcp.accept().use { accepted.incrementAndGet() } } }.start()
        val r = DotResolver("dot.test", listOf(loopback), { false }, { replies.add(it) }, tcp.localPort)
            .also { it.start(); cleanup.add(it::stop) }

        r.forward(request("a.example", 3), "a.example")
        assertNull(replies.poll(1_500, TimeUnit.MILLISECONDS))
        assertEquals("no se abrió ninguna conexión", 0, accepted.get())
    }
}
