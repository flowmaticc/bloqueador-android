package app.bloqueador.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteGuessTest {

    @Test
    fun `dominio registrable`() {
        assertEquals("marca.com", SiteGuess.registrable("e00-marca.www.marca.com"))
        assertEquals("bbc.co.uk", SiteGuess.registrable("static.files.bbc.co.uk"))
        assertEquals("amazon.es", SiteGuess.registrable("www.amazon.es"))
        assertEquals("localhost", SiteGuess.registrable("localhost"))
    }

    @Test
    fun `la web visitada justo antes del anuncio sale la primera`() {
        val t = 1_000_000L
        val nearby = listOf(
            "www.marca.com" to t - 4_000,
            "e00-marca.uecdn.es" to t - 3_000,
            "fonts.gstatic.com" to t - 2_000,
            "securepubads.g.doubleclick.net" to t - 1_000,
            "www.wikipedia.org" to t - 90_000,
            "static.amazon-adsystem.com" to t - 500,
        )
        val c = SiteGuess.candidates("aax-eu.amazon-adsystem.com", t, nearby)
        assertEquals("www.marca.com", c.first())
        assertFalse("infraestructura fuera", c.any { "gstatic" in it || "doubleclick" in it })
        assertFalse("el propio anunciante no es la web", c.any { "amazon-adsystem" in it })
        assertTrue(c.contains("www.wikipedia.org"))
    }

    @Test
    fun `prefiere la portada a un servidor auxiliar del mismo sitio`() {
        val t = 50_000L
        val c = SiteGuess.candidates(
            "ads.example.net", t,
            listOf("cdn.elpais.com" to t - 100, "elpais.com" to t - 5_000),
        )
        assertEquals(listOf("elpais.com"), c)
    }

    @Test
    fun `sin consultas cercanas no hay candidatas`() {
        assertTrue(SiteGuess.candidates("ads.x.com", 0, emptyList()).isEmpty())
    }

    @Test
    fun `coincidencia de dominio y subdominios`() {
        assertTrue(SiteGuess.matches("aax-eu.amazon-adsystem.com", "amazon-adsystem.com"))
        assertTrue(SiteGuess.matches("Amazon-Adsystem.com", "amazon-adsystem.com"))
        assertFalse(SiteGuess.matches("notamazon-adsystem.com", "amazon-adsystem.com"))
    }
}
