package app.bloqueador.lists

import android.content.Context
import app.bloqueador.filter.BlocklistParser
import app.bloqueador.filter.DomainMatcher
import app.bloqueador.filter.FilterEngine
import app.bloqueador.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Resultado de comprobar todas las listas: si alguna cambió y los errores de las que fallaron. */
data class UpdateResult(val changed: Boolean, val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/** Una lista descargable. [id] es también el nombre del fichero local. */
data class ListSource(val id: String, val label: String, val description: String, val url: String)

private const val HAGEZI = HAGEZI_URL

/**
 * Nivel de agresividad → lista base de Hagezi en formato adblock.
 *
 * Equilibrado usa Hagezi Pro (la que recomienda su autor) y Estricto Pro++. La lista Normal se
 * descarta porque bloquea prácticamente los mismos anuncios que la Light. El id de cada lista es
 * también el nombre del fichero en disco.
 */
enum class BlockMode(val label: String, val description: String, val source: ListSource) {
    LIGHT(
        "Ligero", "Máxima compatibilidad. Riesgo muy bajo de que algo deje de funcionar.",
        ListSource("light", "Hagezi Light", "", "$HAGEZI/light.txt"),
    ),
    BALANCED(
        "Equilibrado", "Para el día a día: configurar y olvidar. Bloquea bien sin romper nada habitual.",
        ListSource("pro", "Hagezi Pro", "", "$HAGEZI/pro.txt"),
    ),
    STRICT(
        "Estricto", "Privacidad agresiva. Puede romper alguna app o enlace.",
        ListSource("proplus", "Hagezi Pro++", "", "$HAGEZI/pro.plus.txt"),
    ),
}

/** Capas opcionales que se suman a la lista base. */
object ExtraLayers {
    val all = listOf(
        ListSource(
            "doh", "Anti-evasión DNS",
            "Bloquea servidores de DNS cifrado para que navegadores y apps no se salten el filtro.",
            "$HAGEZI/doh.txt",
        ),
        ListSource(
            "goodbyeads", "Juegos y apps gratis",
            "Anuncios de juegos y apps gratuitas (lista GoodbyeAds). Puede romper alguna recompensa por ver anuncios.",
            "https://raw.githubusercontent.com/jerryn70/GoodbyeAds/master/Hosts/GoodbyeAds.txt",
        ),
        ListSource(
            "1hosts", "1Hosts Lite",
            "Alternativa ligera centrada en anuncios y rastreo.",
            "https://raw.githubusercontent.com/badmojr/1Hosts/master/Lite/adblock.txt",
        ),
        ListSource(
            "adaway", "AdAway",
            "Lista clásica y conservadora para móviles.",
            "https://adaway.org/hosts.txt",
        ),
        ListSource(
            "stevenblack", "StevenBlack",
            "Lista unificada de amplia cobertura.",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        ),
    )

    /** Capas que vienen activadas en una instalación nueva. */
    const val DEFAULT_LAYER = "doh"

    /**
     * Lista de publicidad y rastreo del fabricante del móvil (Hagezi "native"), si hay una para esa
     * marca. [manufacturer] es `Build.MANUFACTURER` ("samsung", "Xiaomi"…).
     */
    fun vendorFor(manufacturer: String?): ListSource? {
        val m = manufacturer.orEmpty().lowercase()
        val (file, brand) = when {
            m.contains("samsung") -> "native.samsung" to "Samsung"
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "native.xiaomi" to "Xiaomi"
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> "native.oppo-realme" to "Oppo, Realme y OnePlus"
            m.contains("huawei") || m.contains("honor") -> "native.huawei" to "Huawei"
            m.contains("vivo") || m.contains("iqoo") -> "native.vivo" to "Vivo"
            m.contains("amazon") -> "native.amazon" to "Amazon"
            else -> return null
        }
        return ListSource(
            "vendor", "Fabricante: $brand",
            "Publicidad y rastreo del propio sistema de tu móvil $brand.",
            "$HAGEZI/$file.txt",
        )
    }

    /** Capas que se ofrecen en este móvil: la del fabricante (si la hay) primero. */
    fun available(manufacturer: String?): List<ListSource> = listOfNotNull(vendorFor(manufacturer)) + all
}

/**
 * Descarga y carga de listas según la configuración: lista base del modo elegido, capas extra y
 * reglas propias del usuario. La comprobación periódica la programa ListUpdateScheduler.
 */
class BlocklistRepository(context: Context, private val settings: AppSettings) {

    private val dir = File(context.filesDir, "lists").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("lists", Context.MODE_PRIVATE)

    /** Listas activas ahora mismo: la base del modo, las capas marcadas y la de amenazas. */
    fun activeSources(): List<ListSource> = adSources() + listOfNotNull(threatSource())

    /** Listas de publicidad y rastreo activas: la base del modo + las capas marcadas. */
    private fun adSources(): List<ListSource> {
        val layers = settings.layers
        return listOf(settings.mode.source) +
            ExtraLayers.available(android.os.Build.MANUFACTURER).filter { it.id in layers }
    }

    /** Lista de amenazas activa, o null si la protección está desactivada. */
    fun threatSource(): ListSource? = settings.threatLevel.source.takeIf { settings.threatProtection }

    private fun fileFor(source: ListSource) = File(dir, "${source.id}.txt")

    /** Momento (ms epoch) de la última comprobación correcta de la lista base, o 0 si nunca. */
    val lastUpdated: Long
        get() {
            val source = settings.mode.source
            val saved = prefs.getLong("updated_${source.id}", 0L)
            // Listas descargadas por una versión anterior que no guardaba la fecha con esta clave.
            return if (saved > 0) saved else fileFor(source).takeIf { it.isFile }?.lastModified() ?: 0L
        }

    /**
     * Construye el motor con todas las listas activas que estén en disco y las reglas del usuario.
     * Las reglas del usuario se aplican siempre, también si aún no hay ninguna lista descargada.
     */
    suspend fun load(): FilterEngine.Filters = withContext(Dispatchers.IO) {
        val builder = BlocklistParser.Builder()
        for (source in adSources()) {
            val file = fileFor(source)
            if (file.isFile) file.bufferedReader().useLines { BlocklistParser.parse(it, builder) }
        }
        settings.blockedDomains.forEach(builder.suffix::add)
        settings.allowedDomains.forEach(builder.allowSuffix::add)

        // Amenazas en su propio motor; las excepciones del usuario también valen aquí.
        val threats = threatSource()?.let { fileFor(it) }?.takeIf { it.isFile }?.let { file ->
            val t = BlocklistParser.Builder()
            file.bufferedReader().useLines { BlocklistParser.parse(it, t) }
            settings.allowedDomains.forEach(t.allowSuffix::add)
            t.build()
        } ?: DomainMatcher.EMPTY
        FilterEngine.Filters(builder.build(), threats)
    }

    /** Descarga solo las listas activas que todavía no están en disco. @return true si bajó alguna. */
    suspend fun downloadMissing(): Boolean {
        pruneUnused()
        var changed = false
        for (source in activeSources()) {
            if (!fileFor(source).isFile && download(source)) changed = true
        }
        return changed
    }

    /** Borra listas que ya no usa ningún modo ni capa (p. ej. las de versiones anteriores). */
    private fun pruneUnused() {
        val known = (BlockMode.entries.map { it.source } + ExtraLayers.available(android.os.Build.MANUFACTURER) +
            ThreatLevel.entries.map { it.source })
            .map { "${it.id}.txt" }.toSet()
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".txt") && it.name !in known }?.forEach { it.delete() }
    }

    /**
     * Comprueba todas las listas activas. Un fallo en una lista no impide comprobar las demás.
     * No registra nada: quien llama decide con [recordCheck] cuándo cuenta como un intento
     * (el worker no cuenta sus reintentos cercanos como fallos distintos).
     */
    suspend fun downloadAll(): UpdateResult {
        var changed = false
        val errors = mutableListOf<String>()
        for (source in activeSources()) {
            try {
                if (download(source)) changed = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += e.message ?: "${source.label}: error desconocido"
            }
        }
        return UpdateResult(changed, errors)
    }

    /** Registra una comprobación: fecha y número de fallos seguidos (0 si ha ido bien). */
    fun recordCheck(result: UpdateResult) {
        val edit = prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis())
        if (result.ok) {
            edit.putInt(KEY_FAILURES, 0).remove(KEY_LAST_ERROR)
        } else {
            edit.putInt(KEY_FAILURES, consecutiveFailures + 1)
                .putString(KEY_LAST_ERROR, result.errors.joinToString("; "))
        }
        edit.commit() // síncrono: el worker lee el contador justo después
    }

    /** Última comprobación completa (correcta o no), o 0 si nunca. */
    val lastCheck: Long get() = prefs.getLong(KEY_LAST_CHECK, 0L)

    /** Comprobaciones seguidas con algún fallo. Vuelve a 0 con la primera correcta. */
    val consecutiveFailures: Int get() = prefs.getInt(KEY_FAILURES, 0)

    val lastError: String? get() = prefs.getString(KEY_LAST_ERROR, null)

    /**
     * Descarga una lista. Usa `ETag` para no volver a bajarla si no ha cambiado; escribe en un
     * temporal y lo valida antes de sustituir la lista vigente.
     * @return true si hay una lista nueva que recargar.
     */
    private suspend fun download(source: ListSource): Boolean = withContext(Dispatchers.IO) {
        val target = fileFor(source)
        val tmp = File(dir, "${target.name}.tmp")
        val etagKey = "etag_${source.id}"
        val countKey = "count_${source.id}"
        val updatedKey = "updated_${source.id}"
        val conn = (URL(source.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Garbillo-Android")
            if (target.isFile) prefs.getString(etagKey, null)?.let { setRequestProperty("If-None-Match", it) }
        }
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    prefs.edit().putLong(updatedKey, System.currentTimeMillis()).apply()
                    false
                }
                HttpURLConnection.HTTP_OK -> {
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    val newCount = tmp.bufferedReader().useLines { BlocklistParser.parse(it).ruleCount }
                    val oldCount = prefs.getInt(countKey, 0)
                    if (newCount < MIN_RULES || newCount < oldCount / 2) {
                        throw IOException("${source.label}: lista descartada ($newCount reglas, antes $oldCount)")
                    }
                    if (!tmp.renameTo(target)) {
                        target.delete()
                        if (!tmp.renameTo(target)) throw IOException("No se pudo guardar ${source.label}")
                    }
                    prefs.edit()
                        .putString(etagKey, conn.getHeaderField("ETag"))
                        .putInt(countKey, newCount)
                        .putLong(updatedKey, System.currentTimeMillis())
                        .apply()
                    true
                }
                else -> throw IOException("${source.label}: HTTP $code")
            }
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    private companion object {
        const val MIN_RULES = 100
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_FAILURES = "consecutive_failures"
        const val KEY_LAST_ERROR = "last_error"
    }
}
