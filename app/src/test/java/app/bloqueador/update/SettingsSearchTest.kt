package app.bloqueador.update

import app.bloqueador.ui.SettingsSection
import app.bloqueador.ui.searchSettings
import app.bloqueador.ui.settingsIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    private val index = settingsIndex("samsung")

    private fun sections(q: String) = searchSettings(index, q).map { it.section }.toSet()

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
}
