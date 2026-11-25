package app.bloqueador.lists

import java.io.File
import kotlin.math.abs

/**
 * ¿Se acepta una lista descargada según su número de reglas?
 *
 * Una lista que de repente pierde más de la mitad suele ser un error del servidor (archivo cortado,
 * publicación a medias) y se descarta para no perder protección. Pero a veces el autor la reduce a
 * propósito (p. ej. HaGeZi TIF pasó de ~2,2 M a ~0,9 M entradas): si se descartara siempre, la app se
 * quedaría para siempre con la versión vieja. Por eso una bajada grande se acepta si:
 * - el propio archivo declara su número de entradas y coincide con lo descargado (está completo), o
 * - la misma bajada se repite en una descarga posterior (no fue algo puntual).
 */
internal object ListSizeCheck {
    const val MIN_RULES = 100

    fun accept(newCount: Int, oldCount: Int, declared: Int?, lastRejected: Int): Boolean {
        if (newCount < MIN_RULES) return false
        if (newCount >= oldCount / 2) return true
        if (declared != null && close(newCount, declared, 0.01)) return true
        return lastRejected > 0 && close(newCount, lastRejected, 0.10)
    }

    private fun close(a: Int, b: Int, tolerance: Double): Boolean =
        abs(a - b) <= maxOf(10.0, b * tolerance)

    private val DECLARED = Regex("""^[!#]\s*Number of entries:\s*(\d+)""", RegexOption.IGNORE_CASE)

    /** Número de entradas que declara la cabecera de la lista (formato HaGeZi y similares), o null. */
    fun declaredEntries(lines: Sequence<String>): Int? =
        lines.take(60).firstNotNullOfOrNull { DECLARED.find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }

    fun declaredEntries(file: File): Int? = file.bufferedReader().useLines { declaredEntries(it) }
}
