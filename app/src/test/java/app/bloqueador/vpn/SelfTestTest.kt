package app.bloqueador.vpn

import app.bloqueador.dns.DnsMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/** La prueba en vivo del diagnóstico interpreta bien las respuestas. */
class SelfTestTest {

    /** Respuesta a [query] con un registro A [ip] (nombre comprimido, como los servidores reales). */
    private fun answer(query: ByteArray, ip: ByteArray?, rcode: Int = 0): ByteArray {
        val extra = if (ip == null) ByteArray(0) else byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4) + ip
        val out = query + extra
        DnsMessage.put16(out, 2, 0x8180 or rcode)
        DnsMessage.put16(out, 6, if (ip == null) 0 else 1)
        return out
    }

    @Test
    fun `la consulta de prueba es una consulta valida`() {
        val q = SelfTest.query("wikipedia.org", 7)
        assertEquals("wikipedia.org", DnsMessage.parseQuery(q)?.name)
    }

    @Test
    fun `interpreta direccion, bloqueo, error y respuesta vacia`() {
        val q = SelfTest.query("wikipedia.org", 7)
        assertEquals(SelfTestResult.Resolved("185.15.58.224", 30), SelfTest.interpret(answer(q, byteArrayOf(185.toByte(), 15, 58, 224.toByte())), 30))
        assertEquals(SelfTestResult.Blocked(5), SelfTest.interpret(answer(q, byteArrayOf(0, 0, 0, 0)), 5))
        assertEquals(SelfTestResult.Failed(2), SelfTest.interpret(answer(q, null, rcode = 2), 5))
        assertEquals(SelfTestResult.Failed(0), SelfTest.interpret(answer(q, null), 5))
        assertEquals(SelfTestResult.Failed(-1), SelfTest.interpret(ByteArray(5), 5))
    }

    @Test
    fun `la respuesta de bloqueo de Garbillo se reconoce como bloqueo`() {
        val q = SelfTest.query("doubleclick.net", 9)
        val question = DnsMessage.parseQuery(q)!!
        val blocked = DnsMessage.buildBlockedResponse(q, question)
        assertEquals(SelfTestResult.Blocked(3), SelfTest.interpret(blocked, 3))
    }
}
