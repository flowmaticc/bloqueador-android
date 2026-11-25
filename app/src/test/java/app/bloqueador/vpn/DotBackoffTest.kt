package app.bloqueador.vpn

import app.bloqueador.dns.DnsMessage
import app.bloqueador.dns.IpPacket
import app.bloqueador.dns.UdpPacket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Sin red (ningún servidor DoT alcanzable) no se reintenta en cada consulta: se espera y se avisa ya. */
class DotBackoffTest {

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val replies = LinkedBlockingQueue<ByteArray>()
    private var resolver: DotResolver? = null

    @After
    fun tearDown() {
        resolver?.stop()
    }

    private fun request(name: String, id: Int): UdpPacket {
        val q = DnsForwarderTest.dnsQuery(name, id)
        val raw = IpPacket.buildUdp(4, byteArrayOf(10, 111, 222.toByte(), 1), byteArrayOf(10, 111, 222.toByte(), 2), 40000, 53, q)
        return IpPacket.parseUdp(raw, raw.size)!!
    }

    /** Un puerto cerrado: conectar falla al momento, como sin red. */
    private fun closedPort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    private fun rcodeOf(reply: ByteArray): Int = IpPacket.parseUdp(reply, reply.size)!!.payload()[3].toInt() and 0x0F

    @Test
    fun `sin servidor alcanzable responde SERVFAIL y no reintenta en cada consulta`() {
        val attempts = AtomicInteger()
        val r = DotResolver("dot.test", listOf(loopback, loopback), { attempts.incrementAndGet(); true }, { replies.add(it) }, closedPort())
            .also { it.start(); resolver = it }

        r.forward(request("a.example", 0x0A0A), "a.example")
        val first = replies.poll(3, TimeUnit.SECONDS)
        assertNotNull(first)
        assertEquals(DnsMessage.RCODE_SERVFAIL, rcodeOf(first!!))
        assertEquals(0x0A0A, DnsMessage.u16(IpPacket.parseUdp(first, first.size)!!.payload(), 0))
        assertEquals("probó los dos servidores", 2, attempts.get())

        repeat(5) { r.forward(request("b$it.example", it), "b$it.example") }
        repeat(5) { assertNotNull(replies.poll(2, TimeUnit.SECONDS)) }
        assertEquals("durante la espera no se vuelve a conectar", 2, attempts.get())
        assertEquals(0, r.inFlight)
    }

    @Test
    fun `un cambio de red permite reintentar enseguida`() {
        val attempts = AtomicInteger()
        val r = DotResolver("dot.test", listOf(loopback), { attempts.incrementAndGet(); true }, { replies.add(it) }, closedPort())
            .also { it.start(); resolver = it }
        r.forward(request("a.example", 1), "a.example")
        assertNotNull(replies.poll(3, TimeUnit.SECONDS))
        assertEquals(1, attempts.get())

        r.networkChanged()
        r.forward(request("b.example", 2), "b.example")
        assertNotNull(replies.poll(3, TimeUnit.SECONDS))
        assertEquals(2, attempts.get())
    }
}
