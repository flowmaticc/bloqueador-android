package app.bloqueador.dns

import app.bloqueador.vpn.PublicDns
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** El RST con el que se rechazan las conexiones TCP al DNS (DoT, DoH, DNS por TCP) es un TCP válido. */
class TcpResetTest {

    private val app4 = byteArrayOf(10, 111, 222.toByte(), 1)
    private val dns4 = byteArrayOf(8, 8, 8, 8)
    private val app6 = InetAddress.getByName("fd00:6c6f:6361:6c::1").address
    private val dns6 = InetAddress.getByName("2001:4860:4860::8888").address

    /** Segmento TCP de [src]:40000 a [dst]:[port] con [flags], [seq], [ack] y [payload]. */
    private fun segment(src: ByteArray, dst: ByteArray, port: Int, flags: Int, seq: Long, ack: Long = 0, payload: Int = 0): ByteArray {
        val v4 = src.size == 4
        val h = if (v4) 20 else 40
        val out = ByteArray(h + 20 + payload)
        if (v4) {
            out[0] = 0x45; DnsMessage.put16(out, 2, out.size); out[9] = 6
            System.arraycopy(src, 0, out, 12, 4); System.arraycopy(dst, 0, out, 16, 4)
        } else {
            out[0] = 0x60; DnsMessage.put16(out, 4, 20 + payload); out[6] = 6
            System.arraycopy(src, 0, out, 8, 16); System.arraycopy(dst, 0, out, 24, 16)
        }
        DnsMessage.put16(out, h, 40000); DnsMessage.put16(out, h + 2, port)
        put32(out, h + 4, seq); put32(out, h + 8, ack)
        out[h + 12] = 0x50; out[h + 13] = flags.toByte()
        return out
    }

    private fun put32(d: ByteArray, i: Int, v: Long) { DnsMessage.put16(d, i, (v ushr 16).toInt() and 0xFFFF); DnsMessage.put16(d, i + 2, (v and 0xFFFF).toInt()) }
    private fun u32(d: ByteArray, i: Int) = (DnsMessage.u16(d, i).toLong() shl 16) or DnsMessage.u16(d, i + 2).toLong()

    /** La suma de comprobación TCP (con pseudo-cabecera) de un paquete correcto da 0. */
    private fun tcpChecksumOk(p: ByteArray): Boolean {
        val v4 = (p[0].toInt() and 0xF0) == 0x40
        val h = if (v4) 20 else 40
        val src = if (v4) p.copyOfRange(12, 16) else p.copyOfRange(8, 24)
        val dst = if (v4) p.copyOfRange(16, 20) else p.copyOfRange(24, 40)
        val pseudo = src + dst
        var sum = 0
        for (i in pseudo.indices step 2) sum += DnsMessage.u16(pseudo, i)
        sum += 6 + (p.size - h)
        return IpPacket.checksum(sum, p, h, p.size - h) == 0
    }

    @Test
    fun `a un SYN IPv4 se responde RST+ACK desde el servidor`() {
        val rst = IpPacket.buildTcpReset(segment(app4, dns4, 853, 0x02, seq = 1000), 40)!!
        assertArrayEquals(dns4, rst.copyOfRange(12, 16))
        assertArrayEquals(app4, rst.copyOfRange(16, 20))
        assertEquals(853, DnsMessage.u16(rst, 20))
        assertEquals(40000, DnsMessage.u16(rst, 22))
        assertEquals(0L, u32(rst, 24))
        assertEquals("ACK = seq + 1 (el SYN cuenta)", 1001L, u32(rst, 28))
        assertEquals(0x14, rst[33].toInt() and 0xFF)
        assertEquals(0, IpPacket.checksum(0, rst, 0, 20))
        assertTrue(tcpChecksumOk(rst))
    }

    @Test
    fun `a un segmento con ACK se responde RST con su numero de ACK`() {
        val p = segment(app4, dns4, 443, 0x18, seq = 5, ack = 0xFFFFFFF0L, payload = 10)
        val rst = IpPacket.buildTcpReset(p, p.size)!!
        assertEquals(0xFFFFFFF0L, u32(rst, 24))
        assertEquals(0x04, rst[33].toInt() and 0xFF)
        assertTrue(tcpChecksumOk(rst))
    }

    @Test
    fun `IPv6 tambien`() {
        val p = segment(app6, dns6, 443, 0x02, seq = 0xFFFFFFFFL)
        val rst = IpPacket.buildTcpReset(p, p.size)!!
        assertEquals(60, rst.size)
        assertArrayEquals(dns6, rst.copyOfRange(8, 24))
        assertArrayEquals(app6, rst.copyOfRange(24, 40))
        assertEquals("el ACK da la vuelta", 0L, u32(rst, 48))
        assertTrue(tcpChecksumOk(rst))
    }

    @Test
    fun `no se responde a un RST, a UDP ni a basura`() {
        assertNull(IpPacket.buildTcpReset(segment(app4, dns4, 53, 0x04, seq = 1), 40))
        val udp = IpPacket.buildUdp(4, app4, dns4, 40000, 53, ByteArray(20))
        assertNull(IpPacket.buildTcpReset(udp, udp.size))
        assertNull(IpPacket.buildTcpReset(ByteArray(10), 10))
        assertNull(IpPacket.buildTcpReset(segment(app4, dns4, 53, 0x02, seq = 1), 30)) // cortado
    }

    @Test
    fun `la lista de DNS publicos son direcciones validas de su familia`() {
        PublicDns.V4.forEach { assertTrue(it, InetAddress.getByName(it) is Inet4Address) }
        PublicDns.V6.forEach { assertTrue(it, InetAddress.getByName(it) is Inet6Address) }
        assertEquals(PublicDns.V4.size, PublicDns.V4.toSet().size)
        assertEquals(PublicDns.V6.size, PublicDns.V6.toSet().size)
    }
}
