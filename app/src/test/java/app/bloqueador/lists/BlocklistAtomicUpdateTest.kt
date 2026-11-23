package app.bloqueador.lists

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files

/** Una actualización fallida nunca deja sin la última lista válida. */
class BlocklistAtomicUpdateTest {

    private lateinit var dir: File
    private val original = SafeReplace.move

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("listas").toFile()
    }

    @After
    fun tearDown() {
        SafeReplace.move = original
        dir.deleteRecursively()
    }

    private fun file(name: String, text: String) = File(dir, name).apply { writeText(text) }

    @Test
    fun `sustituye la lista por la nueva`() {
        val target = file("base.txt", "vieja")
        val tmp = file("base.123.tmp", "nueva")
        SafeReplace.replace(tmp, target)
        assertEquals("nueva", target.readText())
        assertFalse(tmp.exists())
        assertFalse(File(dir, "base.txt.bak").exists())
    }

    @Test
    fun `si falla el renombrado atomico se usa el respaldo`() {
        SafeReplace.move = { from, to, atomic ->
            if (atomic) throw AtomicMoveNotSupportedException(from.path, to.path, "prueba")
            original(from, to, false)
        }
        val target = file("base.txt", "vieja")
        SafeReplace.replace(file("base.1.tmp", "nueva"), target)
        assertEquals("nueva", target.readText())
        assertFalse(File(dir, "base.txt.bak").exists())
    }

    @Test
    fun `si fallan todos los intentos se conserva la lista anterior`() {
        SafeReplace.move = { from, to, atomic ->
            if (atomic || from.name.endsWith(".tmp")) throw IOException("disco")
            original(from, to, false)
        }
        val target = file("base.txt", "vieja")
        try {
            SafeReplace.replace(file("base.1.tmp", "nueva"), target)
            fail("debía fallar")
        } catch (e: IOException) {
            // esperado
        }
        assertEquals("vieja", target.readText())
        assertFalse(File(dir, "base.txt.bak").exists())
    }

    @Test
    fun `si la app se cerro a mitad, la anterior se recupera`() {
        val backup = file("base.txt.bak", "vieja")
        val target = File(dir, "base.txt")
        SafeReplace.recover(target)
        assertTrue(target.exists())
        assertEquals("vieja", target.readText())
        assertFalse(backup.exists())
    }
}
