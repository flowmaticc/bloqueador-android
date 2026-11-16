package app.bloqueador.vpn

import app.bloqueador.intentar
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class UpstreamDnsTest {

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    @Test
    fun `con DNS de la red no se añade Quad9 (los nombres internos no salen a terceros)`() {
        val chosen = UpstreamDns.choose(listOf(ip("2a0c:5a84:0:2::1"), ip("192.168.1.1")))
        assertEquals(listOf(ip("192.168.1.1"), ip("2a0c:5a84:0:2::1")), chosen) // IPv4 primero
        assertTrue(UpstreamDns.FALLBACK.none { it in chosen })
    }

    @Test
    fun `sin DNS de la red se usa el respaldo`() {
        assertEquals(UpstreamDns.FALLBACK, UpstreamDns.choose(emptyList()))
    }

    @Test
    fun `intentar convierte errores en fallo pero deja pasar la cancelacion`() {
        assertTrue(intentar { error("x") }.isFailure)
        assertEquals(3, intentar { 3 }.getOrNull())
        var relanzada = false
        try {
            intentar { throw CancellationException("cancelada") }
        } catch (e: CancellationException) {
            relanzada = true
        }
        assertTrue(relanzada)
    }
}
