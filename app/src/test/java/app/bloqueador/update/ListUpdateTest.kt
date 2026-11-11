package app.bloqueador.update

import app.bloqueador.lists.UpdateResult
import app.bloqueador.settings.UpdateFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListUpdateTest {

    @Test
    fun `solo se avisa a los 3 periodos fallidos y luego cada 3`() {
        val notified = (0..10).filter { ListUpdateWorker.shouldNotify(it) }
        assertEquals(listOf(3, 6, 9), notified)
    }

    @Test
    fun `un resultado con errores no es correcto aunque algo haya cambiado`() {
        assertTrue(UpdateResult(changed = false, errors = emptyList()).ok)
        assertFalse(UpdateResult(changed = true, errors = listOf("AdAway: HTTP 503")).ok)
    }

    @Test
    fun `frecuencias - 12 h por defecto en la lista y manual sin intervalo`() {
        assertEquals(UpdateFrequency.EVERY_12H, UpdateFrequency.entries.first())
        assertEquals(12L, UpdateFrequency.EVERY_12H.hours)
        assertEquals(24L, UpdateFrequency.EVERY_24H.hours)
        assertEquals(0L, UpdateFrequency.MANUAL.hours)
    }
}
