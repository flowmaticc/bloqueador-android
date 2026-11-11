package app.bloqueador.apps

import app.bloqueador.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStrategyTest {

    private val self = "app.bloqueador"
    private val installed = setOf("com.banco", "com.juego", "com.chat", self)
    private fun compute(strategy: AppStrategy, excluded: Set<String> = emptySet(), included: Set<String> = emptySet()) =
        computeTunnelApps(strategy, excluded, included, self) { it in installed }

    @Test
    fun `todas - solo se excluye la propia app`() {
        assertEquals(TunnelApps.Disallowed(listOf(self)), compute(AppStrategy.ALL, excluded = setOf("com.banco")))
    }

    @Test
    fun `todas excepto - excluye la propia y las elegidas que sigan instaladas`() {
        val result = compute(AppStrategy.ALL_EXCEPT, excluded = setOf("com.juego", "com.banco", "com.desinstalada", self))
        assertEquals(TunnelApps.Disallowed(listOf(self, "com.banco", "com.juego")), result)
    }

    @Test
    fun `solo - permite las elegidas instaladas y nunca la propia`() {
        val result = compute(AppStrategy.ONLY, included = setOf("com.chat", "com.desinstalada", self))
        assertEquals(TunnelApps.Allowed(listOf("com.chat")), result)
    }

    @Test
    fun `solo sin ninguna app válida - se filtra todo en vez de nada`() {
        assertEquals(TunnelApps.Disallowed(listOf(self)), compute(AppStrategy.ONLY, included = setOf("com.desinstalada")))
        assertEquals(TunnelApps.Disallowed(listOf(self)), compute(AppStrategy.ONLY))
    }

    @Test
    fun `detecta apps de banca y pagos`() {
        assertTrue(SensitiveApps.isSensitive("com.bbva.bbvacontigo"))
        assertTrue(SensitiveApps.isSensitive("es.bancosantander.apps"))
        assertTrue(SensitiveApps.isSensitive("com.google.android.apps.walletnfcrel"))
        assertTrue(SensitiveApps.isSensitive("com.paypal.android.p2pmobile"))
        assertTrue(SensitiveApps.isSensitive("es.gob.clave"))
        assertTrue(SensitiveApps.isSensitive("com.unbancodesconocido.app"))
    }

    @Test
    fun `no marca apps normales como banca`() {
        assertFalse(SensitiveApps.isSensitive("com.whatsapp"))
        assertFalse(SensitiveApps.isSensitive("com.android.chrome"))
        assertFalse(SensitiveApps.isSensitive("com.tienda.powerbank"))
        assertFalse(SensitiveApps.isSensitive("org.wikipedia"))
    }

    @Test
    fun `limpia los dominios que escribe el usuario`() {
        assertEquals("www.ejemplo.com", AppSettings.cleanDomain("  https://www.Ejemplo.com/ruta?x=1 "))
        assertEquals("ejemplo.com", AppSettings.cleanDomain("@@||ejemplo.com^"))
        assertEquals("ejemplo.com", AppSettings.cleanDomain("*.ejemplo.com."))
        assertEquals("ejemplo.com", AppSettings.cleanDomain("ejemplo.com:8080"))
        assertNull(AppSettings.cleanDomain("sin punto"))
        assertNull(AppSettings.cleanDomain("ejemplo"))
        assertNull(AppSettings.cleanDomain(""))
        assertNull(AppSettings.cleanDomain("a..com"))
    }
}
