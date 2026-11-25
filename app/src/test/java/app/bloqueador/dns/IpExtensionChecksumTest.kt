package app.bloqueador.dns

import app.bloqueador.vpn.DnsForwarderTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import kotlin.random.Random

/** Cabeceras de extensión IPv6, sumas de comprobación y fuzzing de los analizadores IP/UDP/TCP. */
class IpExtensionChecksumTest {

    private val app6 = InetAddress.getByName("fd00:6c6f:6361:6c::1").address
    private val dns6 = InetAddress.getByName("fd00:6c6f:6361:6c::2").address
    private val app4 = byteArrayOf(10, 111, 222.toByte(), 1)
    private val dns4 = byteArrayOf(10, 111, 222.toByte(), 2)
    private val query = DnsForwarderTest.dnsQuery("ejemplo.com", 0x4242)

    /** Inserta cabeceras de extensión [ext] (tipo → longitud en bytes) entre la cabecera IPv6 y el UDP. */
    private fun withExtensions(packet: ByteArray, vararg ext: Pair<Int, Int>): ByteArray {
        val ip = packet.copyOfRange(0, 40)
        val rest = packet.copyOfRange(40, packet.size)
        var next = ip[6].toInt() and 0xFF
        val headers = ArrayList<ByteArray>()
        // Se construyen de la última a la primera para encadenar "siguiente cabecera".
        for ((type, len) in ext.reversed()) {
            val h = ByteArray(len)
            h[0] = next.toByte()
            h[1] = (len / 8 - 1).toByte()
            headers.add(0, h)
            next = type
        }
        ip[6] = next.toByte()
        val out = ip + headers.fold(ByteArray(0)) { a, b -> a + b } + rest
        DnsMessage.put16(out, 4, out.size - 40)
        return out
    }

    @Test
    fun `IPv6 con cabeceras de extension habituales se entiende`() {
        val plain = IpPacket.buildUdp(6, app6, dns6, 40000, 53, query)
        val p = withExtensions(plain, 0 to 8, 60 to 16)
        val udp = IpPacket.parseUdp(p, p.size)
        assertNotNull(udp)
        assertEquals(53, udp!!.destinationPort)
        assertArrayEquals(query, udp.payload())
        // Y la respuesta sale sin cabeceras de extensión, con su suma bien.
        val reply = IpPacket.buildUdpReply(udp, query)
        assertNotNull(IpPacket.parseUdp(reply, reply.size))
    }

    @Test
    fun `IPv6 fragmentado o con cabeceras que no caben se rechaza`() {
        val plain = IpPacket.buildUdp(6, app6, dns6, 40000, 53, query)
        val frag = withExtensions(plain, 44 to 8)
        assertNull(IpPacket.parseUdp(frag, frag.size))
        val cut = withExtensions(plain, 0 to 8)
        cut[41] = 50 // dice medir 408 bytes
        assertNull(IpPacket.parseUdp(cut, cut.size))
    }

    @Test
    fun `sumas de comprobacion danadas se rechazan`() {
        val v4 = IpPacket.buildUdp(4, app4, dns4, 40000, 53, query)
        assertNotNull(IpPacket.parseUdp(v4, v4.size))
        val badHeader = v4.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertNull("cabecera IPv4", IpPacket.parseUdp(badHeader, badHeader.size))
        val badUdp = v4.copyOf().also { it[27] = (it[27] + 1).toByte() }
        assertNull("UDP IPv4", IpPacket.parseUdp(badUdp, badUdp.size))
        val badPayload = v4.copyOf().also { it[v4.size - 1] = (it[v4.size - 1] + 1).toByte() }
        assertNull("datos IPv4", IpPacket.parseUdp(badPayload, badPayload.size))

        val v6 = IpPacket.buildUdp(6, app6, dns6, 40000, 53, query)
        val badV6 = v6.copyOf().also { it[v6.size - 1] = (it[v6.size - 1] + 1).toByte() }
        assertNull("datos IPv6", IpPacket.parseUdp(badV6, badV6.size))
        val zeroV6 = v6.copyOf().also { DnsMessage.put16(it, 46, 0) }
        assertNull("en IPv6 la suma es obligatoria", IpPacket.parseUdp(zeroV6, zeroV6.size))
    }

    @Test
    fun `en IPv4 una suma UDP a cero significa sin suma y se acepta`() {
        val v4 = IpPacket.buildUdp(4, app4, dns4, 40000, 53, query)
        DnsMessage.put16(v4, 26, 0)
        assertNotNull(IpPacket.parseUdp(v4, v4.size))
    }

    @Test
    fun `RST a TCP sobre IPv6 con cabecera de extension`() {
        val tcp = ByteArray(40 + 20)
        tcp[0] = 0x60; DnsMessage.put16(tcp, 4, 20); tcp[6] = 6
        System.arraycopy(app6, 0, tcp, 8, 16); System.arraycopy(dns6, 0, tcp, 24, 16)
        DnsMessage.put16(tcp, 40, 40000); DnsMessage.put16(tcp, 42, 853)
        tcp[52] = 0x50; tcp[53] = 0x02
        val p = withExtensions(tcp, 0 to 8)
        val rst = IpPacket.buildTcpReset(p, p.size)
        assertNotNull(rst)
        assertEquals(853, DnsMessage.u16(rst!!, 40))
        assertEquals(40000, DnsMessage.u16(rst, 42))
    }

    @Test
    fun `fuzzing - paquetes alterados nunca provocan un fallo`() {
        val rnd = Random(20261125)
        val seeds = listOf(
            IpPacket.buildUdp(4, app4, dns4, 40000, 53, query),
            withExtensions(IpPacket.buildUdp(6, app6, dns6, 40000, 53, query), 0 to 8, 43 to 24, 60 to 8),
        )
        repeat(20_000) {
            val p = seeds[rnd.nextInt(seeds.size)].copyOf()
            repeat(rnd.nextInt(1, 6)) { p[rnd.nextInt(p.size)] = rnd.nextInt(256).toByte() }
            val len = if (rnd.nextBoolean()) p.size else rnd.nextInt(p.size + 1)
            IpPacket.parseUdp(p, len)?.let { DnsMessage.parseQuery(it.data, it.payloadOffset, it.payloadLength) }
            IpPacket.buildTcpReset(p, len)
        }
        repeat(20_000) {
            val p = ByteArray(rnd.nextInt(0, 120)).also(rnd::nextBytes)
            if (p.isNotEmpty()) p[0] = (if (rnd.nextBoolean()) 0x45 else 0x60).toByte()
            IpPacket.parseUdp(p, p.size)
            IpPacket.buildTcpReset(p, p.size)
        }
    }
}
