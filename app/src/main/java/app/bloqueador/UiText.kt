package app.bloqueador

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Un texto para mostrar: o una referencia a res/values/strings.xml (se traduce según el idioma) o un
 * nombre propio que no se traduce ("Hagezi Pro", "Quad9"). Permite que las opciones de la app
 * (niveles, listas, canales…) no lleven el texto escrito en un idioma concreto.
 */
sealed interface UiText {
    fun get(context: Context): String

    @Composable
    fun get(): String = get(LocalContext.current)

    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        override fun get(context: Context): String =
            if (args.isEmpty()) context.getString(id)
            else context.getString(id, *args.map { if (it is UiText) it.get(context) else it }.toTypedArray())
    }

    /** Plural ("1 hora" / "3 horas"): cada idioma decide sus formas en <plurals>. */
    data class Plural(@androidx.annotation.PluralsRes val id: Int, val count: Int) : UiText {
        override fun get(context: Context): String = context.resources.getQuantityString(id, count, count)
    }

    data class Raw(val text: String) : UiText {
        override fun get(context: Context): String = text
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun raw(text: String): UiText = Raw(text)
        val EMPTY: UiText = Raw("")
    }
}
