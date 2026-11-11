package app.bloqueador.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsMessageTest {

    /** Consulta DNS real: id 0x1234, RD=1, pregunta [name] tipo [type] clase IN, con registro OPT (EDNS). */
    private fun query(name: String, type: Int, withEdns: Boolean = true): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add((v shr 8).toByte()); out.add(v.toByte()) }
        u16(0x1234); u16(0x0100); u16(1); u16(0); u16(0); u16(if (withEdns) 1 else 0)
        for (label in name.split('.')) {
            out.add(label.length.toByte()); label.forEach { out.add(it.code.toByte()) }
        }
        out.add(0); u16(type); u16(1)
        if (withEdns) { out.add(0); u16(41); u16(4096); u16(0); u16(0); u16(0) }
        return out.toByteArray()
    }

    @Test
    fun `parsea el nombre en minúsculas, el tipo y el final de la pregunta`() {
        val q = query("Ads.Example.COM", DnsMessage.TYPE_A)
        val parsed = DnsMessage.parseQuery(q)!!
        assertEquals("ads.example.com", parsed.name)
        assertEquals(DnsMessage.TYPE_A, parsed.type)
        assertEquals(DnsMessage.CLASS_IN, parsed.dnsClass)
        assertEquals(12 + 17 + 4, parsed.end)
    }

    @Test
    fun `parsea con desplazamiento dentro de un búfer mayor`() {
        val q = query("x.org", DnsMessage.TYPE_AAAA)
        val buffer = ByteArray(10) + q + ByteArray(5)
        assertEquals("x.org", DnsMessage.parseQuery(buffer, 10, q.size)!!.name)
    }

    @Test
    fun `rechaza respuestas, paquetes cortos y nombres truncados`() {
        val response = query("a.com", 1).also { it[2] = (it[2].toInt() or 0x80).toByte() }
        assertNull(DnsMessage.parseQuery(response))
        assertNull(DnsMessage.parseQuery(ByteArray(8)))
        val q = query("example.com", 1, withEdns = false)
        assertNull(DnsMessage.parseQuery(q, 0, 20))
    }

    @Test
    fun `rechaza punteros de compresión en la pregunta`() {
        val q = query("a.com", 1, withEdns = false)
        q[12] = 0xC0.toByte()
        assertNull(DnsMessage.parseQuery(q))
    }

    @Test
    fun `bloqueo de A devuelve 0_0_0_0 y descarta EDNS`() {
        val q = query("ads.com", DnsMessage.TYPE_A)
        val question = DnsMessage.parseQuery(q)!!
        val r = DnsMessage.buildBlockedResponse(q, question)

        assertEquals(0x12, r[0].toInt()); assertEquals(0x34, r[1].toInt())
        assertEquals(0x8180, DnsMessage.u16(r, 2)) // QR, RD, RA, NOERROR
        assertEquals(1, DnsMessage.u16(r, 4))
        assertEquals(1, DnsMessage.u16(r, 6))
        assertEquals(0, DnsMessage.u16(r, 10)) // sin sección adicional
        val answer = question.end
        assertEquals(0xC00C, DnsMessage.u16(r, answer))
        assertEquals(DnsMessage.TYPE_A, DnsMessage.u16(r, answer + 2))
        assertEquals(4, DnsMessage.u16(r, answer + 10))
        assertArrayEquals(ByteArray(4), r.copyOfRange(answer + 12, answer + 16))
        assertEquals(answer + 16, r.size)
    }

    @Test
    fun `bloqueo de AAAA devuelve dirección vacía de 16 bytes`() {
        val q = query("ads.com", DnsMessage.TYPE_AAAA)
        val question = DnsMessage.parseQuery(q)!!
        val r = DnsMessage.buildBlockedResponse(q, question)
        assertEquals(16, DnsMessage.u16(r, question.end + 10))
        assertEquals(question.end + 28, r.size)
    }

    @Test
    fun `otros tipos reciben NXDOMAIN sin respuestas`() {
        val q = query("ads.com", 65) // HTTPS
        val question = DnsMessage.parseQuery(q)!!
        val r = DnsMessage.buildBlockedResponse(q, question)
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.u16(r, 2) and 0xF)
        assertEquals(0, DnsMessage.u16(r, 6))
        assertEquals(question.end, r.size)
        assertNotNull(DnsMessage.parseQuery(q))
    }
}
