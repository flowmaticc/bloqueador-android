package app.bloqueador.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpPacketTest {

    private val client4 = byteArrayOf(10, 111, 222.toByte(), 1)
    private val dns4 = byteArrayOf(10, 111, 222.toByte(), 2)
    private val client6 = ByteArray(16) { if (it == 0) 0xfd.toByte() else if (it == 15) 1 else 0 }
    private val dns6 = ByteArray(16) { if (it == 0) 0xfd.toByte() else if (it == 15) 2 else 0 }
    private val payload = "hola dns".toByteArray()

    @Test
    fun `IPv4 ida y vuelta conserva direcciones, puertos y payload`() {
        val pkt = IpPacket.buildUdp(4, client4, dns4, 40000, 53, payload)
        val udp = IpPacket.parseUdp(pkt, pkt.size)!!
        assertEquals(4, udp.ipVersion)
        assertArrayEquals(client4, udp.source)
        assertArrayEquals(dns4, udp.destination)
        assertEquals(40000, udp.sourcePort)
        assertEquals(53, udp.destinationPort)
        assertArrayEquals(payload, udp.payload())
    }

    @Test
    fun `la respuesta intercambia origen y destino`() {
        val req = IpPacket.parseUdp(IpPacket.buildUdp(4, client4, dns4, 40000, 53, payload).let { it }, 36)!!
        val reply = IpPacket.parseUdp(IpPacket.buildUdpReply(req, byteArrayOf(1, 2)).let { it }, 30)!!
        assertArrayEquals(dns4, reply.source)
        assertArrayEquals(client4, reply.destination)
        assertEquals(53, reply.sourcePort)
        assertEquals(40000, reply.destinationPort)
    }

    @Test
    fun `checksums IPv4 y UDP son válidos`() {
        val pkt = IpPacket.buildUdp(4, client4, dns4, 40000, 53, payload)
        // Un checksum correcto hace que la suma de la cabecera completa sea 0.
        assertEquals(0, IpPacket.checksum(0, pkt, 0, 20))
        assertEquals(0, udpChecksumOver(pkt, 20, client4, dns4))
    }

    @Test
    fun `IPv6 ida y vuelta con checksum UDP válido`() {
        val pkt = IpPacket.buildUdp(6, client6, dns6, 5353, 53, payload)
        val udp = IpPacket.parseUdp(pkt, pkt.size)!!
        assertEquals(6, udp.ipVersion)
        assertArrayEquals(client6, udp.source)
        assertArrayEquals(payload, udp.payload())
        assertEquals(0, udpChecksumOver(pkt, 40, client6, dns6))
    }

    @Test
    fun `ignora TCP, fragmentos y paquetes truncados`() {
        val pkt = IpPacket.buildUdp(4, client4, dns4, 1, 53, payload)
        assertNull(IpPacket.parseUdp(pkt.copyOf().also { it[9] = 6 }, pkt.size))
        assertNull(IpPacket.parseUdp(pkt.copyOf().also { it[6] = 0x20 }, pkt.size)) // MF
        assertNull(IpPacket.parseUdp(pkt, 25))
        assertNull(IpPacket.parseUdp(byteArrayOf(0x55), 1))
    }

    private fun udpChecksumOver(pkt: ByteArray, udpStart: Int, src: ByteArray, dst: ByteArray): Int {
        val udpLen = pkt.size - udpStart
        val pseudo = src + dst + byteArrayOf(0, 17, (udpLen shr 8).toByte(), udpLen.toByte())
        return IpPacket.checksum(0, pseudo + pkt.copyOfRange(udpStart, pkt.size), 0, pseudo.size + udpLen)
    }
}
