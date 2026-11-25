package app.bloqueador.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class ListSizeCheckTest {

    @Test
    fun `una lista que crece o baja poco se acepta`() {
        assertTrue(ListSizeCheck.accept(2_200_000, 2_171_154, null, 0))
        assertTrue(ListSizeCheck.accept(1_200_000, 2_171_154, null, 0))
    }

    @Test
    fun `una bajada grande sin mas datos se descarta (archivo cortado o publicacion a medias)`() {
        assertFalse(ListSizeCheck.accept(882_061, 2_171_154, null, 0))
        assertFalse("la cabecera no coincide: incompleta", ListSizeCheck.accept(500_000, 2_171_154, 882_061, 0))
    }

    @Test
    fun `una bajada grande se acepta si el archivo declara ese numero de entradas (caso HaGeZi TIF)`() {
        assertTrue(ListSizeCheck.accept(882_061, 2_171_154, 882_061, 0))
    }

    @Test
    fun `una bajada grande se acepta si se repite en la siguiente descarga`() {
        assertTrue(ListSizeCheck.accept(885_000, 2_171_154, null, 882_061))
        assertFalse("otra bajada distinta no confirma la anterior", ListSizeCheck.accept(300_000, 2_171_154, null, 882_061))
    }

    @Test
    fun `nunca se acepta una lista casi vacia`() {
        assertFalse(ListSizeCheck.accept(50, 0, 50, 50))
    }

    @Test
    fun `lee el numero de entradas de la cabecera`() {
        val header = sequenceOf(
            "[Adblock Plus]",
            "! Title: HaGeZi's Threat Intelligence Feeds DNS Blocklist",
            "! Version: 2026.1125.1551.23",
            "! Number of entries: 882061",
            "||ejemplo.com^",
        )
        assertEquals(882_061, ListSizeCheck.declaredEntries(header))
        assertEquals(42, ListSizeCheck.declaredEntries(sequenceOf("# Number of Entries: 42", "0.0.0.0 a.com")))
        assertNull(ListSizeCheck.declaredEntries(sequenceOf("||a.com^", "||b.com^")))
    }

    @Test
    fun `solo vale HTTPS como direccion final de una descarga`() {
        assertTrue(isHttps(URL("https://raw.githubusercontent.com/x")))
        assertFalse(isHttps(URL("http://raw.githubusercontent.com/x")))
    }
}
