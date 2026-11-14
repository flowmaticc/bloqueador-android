package app.bloqueador.dns

import app.bloqueador.filter.BlocklistParser
import app.bloqueador.vpn.DnsForwarderTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

/**
 * Cualquier app del móvil puede mandar al túnel los bytes que quiera: el analizador nunca debe
 * fallar ni leer fuera del paquete, sea cual sea la entrada.
 */
class MalformedPacketTest {

    private val random = Random(20261113)

    @Test
    fun `bytes aleatorios nunca provocan un fallo`() {
        repeat(50_000) {
            val data = ByteArray(random.nextInt(120)).also(random::nextBytes)
            // La mitad, con pinta de IPv4 o IPv6 + UDP, para llegar más lejos en el análisis.
            if (data.size > 30 && it % 2 == 0) {
                data[0] = if (it % 4 == 0) 0x45 else 0x60
                if (data[0].toInt() == 0x45) data[9] = 17 else data[6] = 17
            }
            val udp = IpPacket.parseUdp(data, data.size)
            if (udp != null) {
                DnsMessage.parseQuery(udp.data, udp.payloadOffset, udp.payloadLength)
                udp.payload()
            }
            DnsMessage.parseQuery(data)
            DnsMessage.parseQuestion(data)
        }
    }

    @Test
    fun `paquetes validos con bytes alterados o recortados nunca provocan un fallo`() {
        val query = DnsForwarderTest.dnsQuery("sub.ejemplo-largo.example.com", 0x4242)
        val packet = IpPacket.buildUdp(4, byteArrayOf(10, 0, 0, 1), byteArrayOf(10, 0, 0, 2), 5000, 53, query)
        repeat(50_000) {
            val copy = packet.copyOf(if (it % 3 == 0) random.nextInt(packet.size + 1) else packet.size)
            repeat(1 + random.nextInt(4)) { if (copy.isNotEmpty()) copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
            val udp = IpPacket.parseUdp(copy, copy.size) ?: return@repeat
            val q = DnsMessage.parseQuery(udp.data, udp.payloadOffset, udp.payloadLength) ?: return@repeat
            // Si se acepta como consulta, se tiene que poder construir la respuesta de bloqueo.
            val blocked = DnsMessage.buildBlockedResponse(udp.data, q, udp.payloadOffset)
            IpPacket.buildUdpReply(udp, blocked)
        }
    }

    @Test
    fun `longitudes declaradas mayores que el paquete se rechazan`() {
        val query = DnsForwarderTest.dnsQuery("ejemplo.com", 1)
        val packet = IpPacket.buildUdp(4, byteArrayOf(10, 0, 0, 1), byteArrayOf(10, 0, 0, 2), 5000, 53, query)

        val udpTooLong = packet.clone().also { DnsMessage.put16(it, 20 + 4, 60_000) }
        assertNull(IpPacket.parseUdp(udpTooLong, udpTooLong.size))

        val labelTooLong = query.clone().also { it[12] = 63 } // la etiqueta dice 63 bytes y no los hay
        assertNull(DnsMessage.parseQuery(labelTooLong))

        assertNull("longitud mayor que el búfer", DnsMessage.parseQuery(query, 0, query.size + 10))
        assertNull("desplazamiento negativo", DnsMessage.parseQuery(query, -1, query.size))
        assertNull("fragmento IPv4", IpPacket.parseUdp(packet.clone().also { it[6] = 0x20 }, packet.size))
    }

    @Test
    fun `nombres con bytes de control, espacios o puntos dentro de una etiqueta se rechazan`() {
        fun withLabelByte(b: Int) = DnsForwarderTest.dnsQuery("abc.example", 1).also { it[13] = b.toByte() }
        assertNull(DnsMessage.parseQuery(withLabelByte(0x00)))
        assertNull(DnsMessage.parseQuery(withLabelByte(0x0A)))
        assertNull(DnsMessage.parseQuery(withLabelByte(' '.code)))
        assertNull(DnsMessage.parseQuery(withLabelByte('.'.code)))
        assertNull(DnsMessage.parseQuery(withLabelByte(0xC3)))
        // Lo que sí usan los nombres reales: guion bajo (SRV, DKIM) y guion.
        assertEquals("_bc.example", DnsMessage.parseQuery(withLabelByte('_'.code))!!.name)
        assertNotNull(DnsMessage.parseQuery(withLabelByte('-'.code)))
    }

    @Test
    fun `punteros de compresion en una consulta se rechazan (no hay bucles)`() {
        val q = DnsForwarderTest.dnsQuery("abc.example", 1)
        q[12] = 0xC0.toByte(); q[13] = 0x0C // puntero a sí mismo
        assertNull(DnsMessage.parseQuery(q))
    }

    @Test
    fun `lineas raras en una lista de bloqueo no rompen el analizador`() {
        val lines = sequenceOf(
            "", "   ", "||", "@@||", "||^", "||.^", "||a..b^", "0.0.0.0", "0.0.0.0 ", "127.0.0.1\t",
            "||" + "a".repeat(5000) + ".com^", "\u0000\u0001\u0002", "||ejemplo.com^\$important", "!||comentado.com^",
            "||ÉXITO.com^", "||bueno.example^", "http://no-es-una-regla.example/ruta", "a b c d e",
        )
        val matcher = BlocklistParser.parse(lines).build()
        assertEquals(true, matcher.isBlocked("sub.bueno.example"))
        assertEquals(false, matcher.isBlocked("comentado.com"))
    }
}
