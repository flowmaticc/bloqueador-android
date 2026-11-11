package app.bloqueador.settings

import android.content.Context
import app.bloqueador.apps.AppStrategy
import app.bloqueador.lists.BlockMode

/** Frecuencia de actualización automática de listas (sección 6). [hours] = 0 → solo manual. */
enum class UpdateFrequency(val label: String, val description: String, val hours: Long) {
    EVERY_12H("Cada 12 horas", "Recomendado.", 12),
    EVERY_24H("Cada 24 horas", "Algo menos de datos y batería.", 24),
    MANUAL("Solo manual", "Solo cuando pulses \"Actualizar ahora\".", 0),
}

/** Tiempo que se conserva el historial (sección 7.2). */
enum class HistoryRetention(val label: String, val hours: Long) {
    H1("1 hora", 1), H3("3 horas", 3), H6("6 horas", 6), H24("24 horas", 24);

    val millis: Long get() = hours * 3_600_000
}

/** Configuración persistente de la app (SharedPreferences). Todas las lecturas devuelven copias. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(v) = prefs.edit().putBoolean("onboarding_done", v).apply()

    var strategy: AppStrategy
        get() = AppStrategy.entries.firstOrNull { it.name == prefs.getString("strategy", null) } ?: AppStrategy.ALL
        set(v) = prefs.edit().putString("strategy", v.name).apply()

    /** Apps que no se filtran en el modo "todas excepto". */
    var excludedApps: Set<String>
        get() = stringSet("excluded_apps")
        set(v) = prefs.edit().putStringSet("excluded_apps", v).apply()

    /** Apps que se filtran en el modo "solo las que elija". */
    var includedApps: Set<String>
        get() = stringSet("included_apps")
        set(v) = prefs.edit().putStringSet("included_apps", v).apply()

    var mode: BlockMode
        get() = BlockMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: BlockMode.BALANCED
        set(v) = prefs.edit().putString("mode", v.name).apply()

    /** Identificadores de las capas adicionales activadas (modo Personalizado). */
    var layers: Set<String>
        get() = stringSet("layers")
        set(v) = prefs.edit().putStringSet("layers", v).apply()

    /** Cada cuánto se comprueban las listas en segundo plano. */
    var updateFrequency: UpdateFrequency
        get() = UpdateFrequency.entries.firstOrNull { it.name == prefs.getString("update_frequency", null) }
            ?: UpdateFrequency.EVERY_12H
        set(v) = prefs.edit().putString("update_frequency", v.name).apply()

    /** Canal de actualizaciones de la propia app (Estable por defecto). */
    var updateChannel: app.bloqueador.appupdate.UpdateChannel
        get() = app.bloqueador.appupdate.UpdateChannel.entries
            .firstOrNull { it.name == prefs.getString("update_channel", null) }
            ?: app.bloqueador.appupdate.UpdateChannel.STABLE
        set(v) = prefs.edit().putString("update_channel", v.name).apply()

    var lastAppUpdateCheck: Long
        get() = prefs.getLong("last_app_update_check", 0L)
        set(v) = prefs.edit().putLong("last_app_update_check", v).apply()

    /** Última versión de la que ya se avisó con notificación (para no repetir el aviso). */
    var notifiedVersionCode: Long
        get() = prefs.getLong("notified_version_code", 0L)
        set(v) = prefs.edit().putLong("notified_version_code", v).apply()

    /** Si se guarda el historial de consultas (solo en el móvil). */
    var historyEnabled: Boolean
        get() = prefs.getBoolean("history_enabled", true)
        set(v) = prefs.edit().putBoolean("history_enabled", v).apply()

    /** Cuánto tiempo se conserva el historial. */
    var historyRetention: HistoryRetention
        get() = HistoryRetention.entries.firstOrNull { it.name == prefs.getString("history_retention", null) }
            ?: HistoryRetention.H3
        set(v) = prefs.edit().putString("history_retention", v.name).apply()

    /** Dominios que el usuario permite siempre (equivale a `@@||dominio^`). */
    var allowedDomains: Set<String>
        get() = stringSet("allowed_domains")
        set(v) = prefs.edit().putStringSet("allowed_domains", v).apply()

    /** Dominios que el usuario bloquea siempre (equivale a `||dominio^`). */
    var blockedDomains: Set<String>
        get() = stringSet("blocked_domains")
        set(v) = prefs.edit().putStringSet("blocked_domains", v).apply()

    /** true si hay algo más que la lista base: la app lo muestra como modo "Personalizado". */
    val isCustom: Boolean
        get() = layers.isNotEmpty() || allowedDomains.isNotEmpty() || blockedDomains.isNotEmpty()

    private fun stringSet(key: String): Set<String> = HashSet(prefs.getStringSet(key, emptySet()) ?: emptySet())

    companion object {
        /**
         * Convierte lo que escriba el usuario ("https://www.Ejemplo.com/ruta", "ejemplo.com.")
         * en un dominio válido, o null si no lo es.
         */
        fun cleanDomain(input: String): String? {
            var s = input.trim().lowercase()
            if ("://" in s) s = s.substringAfter("://")
            s = s.removePrefix("@@").removePrefix("||").removePrefix("*.")
            s = s.substringBefore('/').substringBefore(':').substringBefore('?').removeSuffix("^").trimEnd('.')
            if (s.isEmpty() || '.' !in s || s.length > 253) return null
            if (s.startsWith('.') || ".." in s) return null
            if (s.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' || it == '_') }) return null
            return s
        }
    }
}
