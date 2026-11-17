package app.bloqueador.update

import app.bloqueador.Textos
import app.bloqueador.ui.SettingsSection
import app.bloqueador.ui.searchSettings
import app.bloqueador.ui.settingsIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El buscador de Ajustes, en español y en inglés, con los textos reales de la app. */
class SettingsSearchTest {

    private val index = settingsIndex("samsung")
    private val es = Textos("es")
    private val en = Textos("en")

    private fun sections(q: String, t: Textos = es) = searchSettings(index, q) { t.get(it) }.map { it.section }.toSet()

    @Test
    fun `encuentra sin tildes ni mayusculas`() {
        assertTrue(SettingsSection.BATTERY in sections("BATERIA"))
        assertTrue(SettingsSection.LAYERS in sections("anti-evasión"))
        assertTrue(SettingsSection.LAYERS in sections("anti evasion dns"))
    }

    @Test
    fun `encuentra por palabras que usa la gente`() {
        assertTrue(SettingsSection.RULES in sections("lista blanca"))
        assertTrue(SettingsSection.HELP in sections("youtube"))
        assertTrue(SettingsSection.APP_UPDATES in sections("beta"))
        assertTrue(SettingsSection.LAYERS in sections("samsung"))
        assertTrue(SettingsSection.APPS in sections("banca"))
        assertTrue(SettingsSection.LANGUAGE in sections("idioma"))
    }

    @Test
    fun `en ingles tambien encuentra lo mismo`() {
        assertTrue(SettingsSection.BATTERY in sections("battery", en))
        assertTrue(SettingsSection.RULES in sections("whitelist", en))
        assertTrue(SettingsSection.SECURITY in sections("phishing", en))
        assertTrue(SettingsSection.LAYERS in sections("anti-evasion", en))
        assertTrue(SettingsSection.LANGUAGE in sections("language", en))
        assertTrue(SettingsSection.ENCRYPTED_DNS in sections("encrypted dns", en))
    }

    @Test
    fun `sin coincidencias o vacio no devuelve nada`() {
        assertEquals(emptySet<SettingsSection>(), sections("zzzz"))
        assertEquals(emptySet<SettingsSection>(), sections("   "))
    }

    @Test
    fun `todas las categorias se pueden encontrar`() {
        SettingsSection.entries.forEach { s -> assertTrue(s.name, index.any { it.section == s }) }
    }

    @Test
    fun `la variante sin actualizador no muestra la seccion de actualizaciones`() {
        assertTrue(app.bloqueador.ui.visibleSections(selfUpdate = true).contains(SettingsSection.APP_UPDATES))
        assertTrue(!app.bloqueador.ui.visibleSections(selfUpdate = false).contains(SettingsSection.APP_UPDATES))
        assertEquals(SettingsSection.entries.size - 1, app.bloqueador.ui.visibleSections(selfUpdate = false).size)
    }

    @Test
    fun `el espanol tiene todos los textos del ingles`() {
        val enKeys = Textos("en").keys
        val esOnly = java.io.File(listOf("src/main/res/values-es/strings.xml", "app/src/main/res/values-es/strings.xml").first { java.io.File(it).isFile })
            .readText().let { Regex("""<(?:string|plurals) name="([^"]+)"""").findAll(it).map { m -> m.groupValues[1] }.toSet() }
        val enDeclared = java.io.File(listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml").first { java.io.File(it).isFile })
            .readText().let { Regex("""<(?:string|plurals) name="([^"]+)"(?! translatable="false")""").findAll(it).map { m -> m.groupValues[1] }.toSet() }
        val missing = enDeclared - esOnly
        assertTrue("Faltan en español: $missing", missing.isEmpty())
        assertTrue(enKeys.isNotEmpty())
    }
}
