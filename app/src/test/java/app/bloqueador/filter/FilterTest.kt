package app.bloqueador.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterTest {

    private val sample = """
        [Adblock Plus]
        ! Title: muestra
        # comentario
        ||ads.example.com^
        ||tracker.net^
        @@||ok.tracker.net^
        ||con-opciones.com^${'$'}third-party
        ||ruta.com/banner^
        example.com##.banner
        0.0.0.0 exacto.org
        127.0.0.1 otro.org  # comentario en línea
        0.0.0.0 localhost
        plano.io
        SOLO-TLD
        mal dominio con espacios
    """.trimIndent()

    private val matcher = BlocklistParser.parse(sample.lineSequence()).build()

    @Test
    fun `reglas adblock bloquean el dominio y sus subdominios`() {
        assertTrue(matcher.isBlocked("ads.example.com"))
        assertTrue(matcher.isBlocked("cdn.ads.example.com"))
        assertTrue(matcher.isBlocked("tracker.net"))
        assertFalse(matcher.isBlocked("example.com"))
        assertFalse(matcher.isBlocked("notads.example.com"))
    }

    @Test
    fun `las excepciones ganan sobre los bloqueos`() {
        assertTrue(matcher.isBlocked("x.tracker.net"))
        assertFalse(matcher.isBlocked("ok.tracker.net"))
        assertFalse(matcher.isBlocked("api.ok.tracker.net"))
    }

    @Test
    fun `hosts y dominios planos son coincidencia exacta`() {
        assertTrue(matcher.isBlocked("exacto.org"))
        assertTrue(matcher.isBlocked("otro.org"))
        assertTrue(matcher.isBlocked("plano.io"))
        assertFalse(matcher.isBlocked("sub.exacto.org"))
    }

    @Test
    fun `se ignoran reglas no aplicables por DNS`() {
        assertFalse(matcher.isBlocked("con-opciones.com"))
        assertFalse(matcher.isBlocked("ruta.com"))
        assertFalse(matcher.isBlocked("localhost"))
        assertFalse(matcher.isBlocked("solo-tld"))
        assertEquals(5, matcher.size) // ads.example.com, tracker.net, exacto.org, otro.org, plano.io
    }

    @Test
    fun `el canario de DoH de Firefox siempre se bloquea`() {
        assertTrue(DomainMatcher.EMPTY.isBlocked("use-application-dns.net"))
    }

    @Test
    fun `normalize limpia y valida`() {
        assertEquals("a.b.com", BlocklistParser.normalize("A.B.COM."))
        assertNull(BlocklistParser.normalize("a..com"))
        assertNull(BlocklistParser.normalize("*.com"))
        assertNull(BlocklistParser.normalize("com"))
    }

    @Test
    fun `el motor usa caché y se puede sustituir en caliente`() {
        val engine = FilterEngine(cacheSize = 2)
        engine.update(matcher)
        assertTrue(engine.isBlocked("ads.example.com"))
        assertTrue(engine.isBlocked("ads.example.com"))
        assertEquals(1, engine.cacheHits.get())
        assertEquals(2, engine.blocked.get())

        engine.update(DomainMatcher.EMPTY)
        assertFalse(engine.isBlocked("ads.example.com")) // la caché se vació al actualizar
    }

    @Test
    fun `la caché LRU expulsa la entrada menos usada`() {
        val cache = LruCache<String, Int>(2)
        cache.put("a", 1); cache.put("b", 2)
        cache["a"]
        cache.put("c", 3)
        assertEquals(1, cache["a"])
        assertNull(cache["b"])
        assertEquals(2, cache.size)
    }
}

class DomainHashSetTest {
    @Test
    fun `quita duplicados y encuentra sufijos sin crear subcadenas`() {
        val set = DomainHashSet.of(listOf("ads.com", "tracker.net", "ads.com"))
        assertEquals(2, set.size)
        assertTrue("ads.com" in set)
        assertTrue(set.contains("x.ads.com", 2)) // "ads.com" dentro de "x.ads.com"
        assertFalse("ads.co" in set)
        assertFalse(DomainHashSet.EMPTY.contains("ads.com"))
    }

    @Test
    fun `sin colisiones en 300 mil dominios distintos`() {
        val b = DomainHashSet.Builder()
        repeat(300_000) { b.add("host$it.example$it.com") }
        assertEquals(300_000, b.build().size)
    }
}
