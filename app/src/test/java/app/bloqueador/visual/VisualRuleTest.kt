package app.bloqueador.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualRuleTest {

    private val banner = NodeSnapshot("com.app:id/ad_banner", "Compra ya", null, "android.widget.FrameLayout")

    @Test
    fun `con resource-id se reconoce por el id y no se guarda el texto`() {
        val rule = VisualRule.from("com.app", banner, VisualAction.HIDE, now = 1)
        assertEquals(VisualRule.Key.VIEW_ID, rule.key)
        assertNull(rule.text) // el texto puede ser privado: no se guarda si no hace falta
        assertTrue(rule.matches(banner.copy(text = "Otro texto")))
        assertFalse(rule.matches(banner.copy(viewId = "com.app:id/contenido")))
        assertEquals("Elemento ad_banner", rule.label)
    }

    @Test
    fun `sin id se usa la descripción y si no el texto`() {
        val close = NodeSnapshot(null, null, "Cerrar anuncio", "android.widget.ImageButton")
        val byDesc = VisualRule.from("com.app", close, VisualAction.CLICK)
        assertEquals(VisualRule.Key.DESCRIPTION, byDesc.key)
        assertTrue(byDesc.matches(close))
        assertFalse(byDesc.matches(close.copy(contentDescription = "Cerrar")))

        val skip = NodeSnapshot(null, "Saltar anuncio", null, "android.widget.TextView")
        val byText = VisualRule.from("com.app", skip, VisualAction.CLICK)
        assertEquals(VisualRule.Key.TEXT, byText.key)
        assertTrue(byText.matches(skip))
        assertFalse(byText.matches(skip.copy(text = "Saltar")))
    }

    @Test
    fun `la clase tiene que coincidir si se conoce`() {
        val rule = VisualRule.from("com.app", banner, VisualAction.HIDE)
        assertFalse(rule.matches(banner.copy(className = "android.widget.Button")))
        assertTrue(rule.matches(banner.copy(className = null)))
    }

    @Test
    fun `no se puede marcar un elemento sin nada que lo identifique`() {
        assertFalse(VisualRule.canIdentify(NodeSnapshot(null, null, null, "android.view.View")))
        assertFalse(VisualRule.canIdentify(NodeSnapshot("", "", "", null)))
        assertTrue(VisualRule.canIdentify(NodeSnapshot(null, "Hola", null, null)))
    }

    @Test
    fun `las apps en pausa no se vigilan`() {
        val r1 = VisualRule.from("com.a", banner, VisualAction.HIDE)
        val r2 = VisualRule.from("com.b", banner, VisualAction.HIDE)
        val state = VisualState(listOf(r1, r2), pausedApps = setOf("com.b"))
        assertEquals(setOf("com.a"), state.activeApps)
        assertEquals(listOf(r1), state.rulesFor("com.a"))
        assertTrue(state.rulesFor("com.b").isEmpty())
    }
}
