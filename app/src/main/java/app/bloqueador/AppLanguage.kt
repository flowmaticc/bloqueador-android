package app.bloqueador

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Idioma de la app. Por defecto, el del móvil (inglés si el móvil está en un idioma sin traducción).
 * Se puede elegir otro en Ajustes → Idioma (y en Android 13+ también en los ajustes del sistema).
 *
 * Los idiomas disponibles salen de las carpetas res/values-xx (BuildConfig.LANGUAGES): añadir un
 * idioma es añadir su strings.xml, sin tocar este código.
 *
 * - Android 13+: lo guarda y aplica el propio sistema (LocaleManager).
 * - Android 8 a 12: se guarda en las preferencias y se aplica al crear cada contexto ([wrap]).
 */
object AppLanguage {
    private const val PREFS = "settings"
    private const val KEY = "app_language"

    /** Códigos de idioma disponibles ("en", "es"…), con su nombre en su propio idioma. */
    fun available(): List<Pair<String, String>> =
        BuildConfig.LANGUAGES.map { tag -> tag to displayName(tag) }.sortedBy { it.second.lowercase() }

    /** "English", "Español", "Français"… (el nombre del idioma en ese mismo idioma). */
    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /** Idioma elegido, o null si se sigue el del móvil. */
    fun current(context: Context): String? =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales
                .takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        }

    /** Cambia el idioma (null = el del móvil). En Android 8 a 12 hay que recrear la pantalla después. */
    fun set(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (tag == null) remove(KEY) else putString(KEY, tag)
            }.commit()
        }
    }

    /**
     * Android 8 a 12: contexto con el idioma elegido (para la app, las pantallas y el servicio).
     * En Android 13+ no hace nada: el sistema ya lo aplica.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(config)
    }
}
