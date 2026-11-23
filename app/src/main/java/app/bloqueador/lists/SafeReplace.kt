package app.bloqueador.lists

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Sustituye [target] por [replacement] sin quedarse nunca sin ninguno de los dos.
 *
 * 1. Renombrado atómico (mismo directorio): o queda la lista nueva entera o sigue la anterior.
 * 2. Si el sistema no lo admite: la anterior se aparta como copia de seguridad, se mueve la nueva y,
 *    si eso falla, se restaura la anterior. La copia solo se borra cuando la nueva ya está en su sitio.
 *
 * Lanza [IOException] si no se pudo sustituir; en ese caso [target] sigue siendo la versión anterior.
 */
internal object SafeReplace {

    /** Movimiento intermedio; se puede cambiar en las pruebas para simular fallos. */
    internal var move: (File, File, Boolean) -> Unit = { from, to, atomic ->
        if (atomic) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.move(from.toPath(), to.toPath())
        }
    }

    private fun backupOf(target: File) = File(target.parentFile, target.name + ".bak")

    /**
     * Si la app se cerró justo entre apartar la anterior y colocar la nueva, devuelve la anterior a su
     * sitio. Si las dos existen, la copia sobra.
     */
    fun recover(target: File) {
        val backup = backupOf(target)
        if (!backup.exists()) return
        if (target.exists()) backup.delete() else runCatching { move(backup, target, false) }
    }

    fun replace(replacement: File, target: File) {
        recover(target)
        try {
            move(replacement, target, true)
            return
        } catch (e: AtomicMoveNotSupportedException) {
            // sigue con el método de respaldo
        } catch (e: IOException) {
            // sigue con el método de respaldo
        }
        if (!target.exists()) {
            move(replacement, target, false)
            return
        }
        val backup = backupOf(target)
        move(target, backup, false) // si falla, la anterior sigue en su sitio
        try {
            move(replacement, target, false)
        } catch (e: IOException) {
            runCatching { target.delete() } // por si quedó a medias
            move(backup, target, false)
            throw e
        }
        backup.delete()
    }
}
