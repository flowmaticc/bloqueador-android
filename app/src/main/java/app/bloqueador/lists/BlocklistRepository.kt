package app.bloqueador.lists

import app.bloqueador.R
import app.bloqueador.UiText
import android.content.Context
import app.bloqueador.filter.BlocklistParser
import app.bloqueador.filter.DomainMatcher
import app.bloqueador.filter.FilterEngine
import app.bloqueador.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Resultado de comprobar todas las listas: si alguna cambió y los errores de las que fallaron. */
data class UpdateResult(val changed: Boolean, val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/** Una lista descargable. [id] es también el nombre del fichero local. */
/** [label] y [description] se muestran en la app; los nombres propios de las listas no se traducen. */
data class ListSource(val id: String, val label: UiText, val description: UiText, val url: String)

private const val HAGEZI = HAGEZI_URL

/**
 * Nivel de agresividad → lista base de Hagezi en formato adblock.
 *
 * Equilibrado usa Hagezi Pro (la que recomienda su autor) y Estricto Pro++. La lista Normal se
 * descarta porque bloquea prácticamente los mismos anuncios que la Light. El id de cada lista es
 * también el nombre del fichero en disco.
 */
enum class BlockMode(val label: UiText, val description: UiText, val source: ListSource) {
    LIGHT(
        UiText.res(R.string.mode_light), UiText.res(R.string.mode_light_desc),
        ListSource("light", UiText.raw("Hagezi Light"), UiText.EMPTY, "$HAGEZI/light.txt"),
    ),
    BALANCED(
        UiText.res(R.string.mode_balanced), UiText.res(R.string.mode_balanced_desc),
        ListSource("pro", UiText.raw("Hagezi Pro"), UiText.EMPTY, "$HAGEZI/pro.txt"),
    ),
    STRICT(
        UiText.res(R.string.mode_strict), UiText.res(R.string.mode_strict_desc),
        ListSource("proplus", UiText.raw("Hagezi Pro++"), UiText.EMPTY, "$HAGEZI/pro.plus.txt"),
    ),
}

/** Capas opcionales que se suman a la lista base. */
object ExtraLayers {
    val all = listOf(
        ListSource(
            "doh", UiText.res(R.string.layer_doh), UiText.res(R.string.layer_doh_desc),
            "$HAGEZI/doh.txt",
        ),
        ListSource(
            "goodbyeads", UiText.res(R.string.layer_games), UiText.res(R.string.layer_games_desc),
            "https://raw.githubusercontent.com/jerryn70/GoodbyeAds/master/Hosts/GoodbyeAds.txt",
        ),
        ListSource(
            "1hosts", UiText.raw("1Hosts Lite"), UiText.res(R.string.layer_1hosts_desc),
            "https://raw.githubusercontent.com/badmojr/1Hosts/master/Lite/adblock.txt",
        ),
        ListSource(
            "adaway", UiText.raw("AdAway"), UiText.res(R.string.layer_adaway_desc),
            "https://adaway.org/hosts.txt",
        ),
        ListSource(
            "stevenblack", UiText.raw("StevenBlack"), UiText.res(R.string.layer_stevenblack_desc),
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
            m.contains("samsung") -> "native.samsung" to UiText.raw("Samsung")
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "native.xiaomi" to UiText.raw("Xiaomi")
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> "native.oppo-realme" to UiText.res(R.string.vendor_oppo)
            m.contains("huawei") || m.contains("honor") -> "native.huawei" to UiText.raw("Huawei")
            m.contains("vivo") || m.contains("iqoo") -> "native.vivo" to UiText.raw("Vivo")
            m.contains("amazon") -> "native.amazon" to UiText.raw("Amazon")
            else -> return null
        }
        return ListSource(
            "vendor", UiText.res(R.string.layer_vendor, brand), UiText.res(R.string.layer_vendor_desc, brand),
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

    /** Para los mensajes de error (en el idioma de la app). */
    private val appContext: Context = context.applicationContext
    private fun ListSource.name(): String = label.get(appContext)

    private val dir = File(context.filesDir, "lists").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("lists", Context.MODE_PRIVATE)

    /**
     * Un cerrojo por lista: la descarga periódica, la del servicio al arrancar y la que pide el usuario
     * pueden coincidir, y dos descargas de la misma lista se pisarían el fichero y el ETag.
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

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

    /** ¿Está ya descargada la lista base del modo actual? */
    fun hasListsOnDisk(): Boolean = fileFor(settings.mode.source).isFile

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
        var changed = false
        for (source in activeSources()) {
            if (!fileFor(source).isFile && download(source)) changed = true
        }
        // Solo tras descargar bien lo nuevo: si falla (sin conexión), las listas anteriores siguen en el
        // móvil y el ajuste se puede deshacer sin quedarse sin filtro.
        pruneUnused()
        return changed
    }

    /** Borra listas que ya no usa ningún modo ni capa (p. ej. las de versiones anteriores). */
    private fun pruneUnused() {
        val known = (BlockMode.entries.map { it.source } + ExtraLayers.available(android.os.Build.MANUFACTURER) +
            ThreatLevel.entries.map { it.source })
            .map { "${it.id}.txt" }.toSet()
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".txt") && it.name !in known }?.forEach { it.delete() }
        // Temporales de descargas que no terminaron (la app se cerró a mitad).
        val old = System.currentTimeMillis() - 60 * 60_000L
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".tmp") && it.lastModified() < old }?.forEach { it.delete() }
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
                errors += e.message ?: appContext.getString(R.string.list_err_unknown, source.name())
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
    private suspend fun download(source: ListSource): Boolean =
        locks.getOrPut(source.id) { Mutex() }.withLock { downloadLocked(source) }

    private suspend fun downloadLocked(source: ListSource): Boolean = withContext(Dispatchers.IO) {
        val target = fileFor(source)
        SafeReplace.recover(target)
        val tmp = File.createTempFile("${source.id}.", ".tmp", dir) // nombre único por descarga
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
            val code = conn.responseCode
            // Tras las redirecciones la dirección final tiene que seguir siendo HTTPS: una lista bajada
            // sin cifrar podría venir manipulada (p. ej. sin bloquear un dominio de malware).
            if (!isHttps(conn.url)) throw IOException(appContext.getString(R.string.upd_err_url))
            when (code) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    prefs.edit().putLong(updatedKey, System.currentTimeMillis()).apply()
                    false
                }
                HttpURLConnection.HTTP_OK -> {
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_LIST_BYTES) throw IOException(appContext.getString(R.string.list_err_too_big, source.name()))
                                out.write(buf, 0, n)
                            }
                            // Descarga cortada: no se valida ni sustituye nada con un archivo incompleto.
                            val expected = conn.contentLengthLong
                            if (expected >= 0 && total != expected) throw IOException(appContext.getString(R.string.list_err_save, source.name()))
                        }
                    }
                    val newCount = tmp.bufferedReader().useLines { BlocklistParser.parse(it).ruleCount }
                    val oldCount = prefs.getInt(countKey, 0)
                    val rejectedKey = "rejected_${source.id}"
                    val declared = ListSizeCheck.declaredEntries(tmp)
                    if (!ListSizeCheck.accept(newCount, oldCount, declared, prefs.getInt(rejectedKey, 0))) {
                        // Se recuerda: si la próxima descarga trae lo mismo, la bajada era intencionada.
                        prefs.edit().putInt(rejectedKey, newCount).apply()
                        throw IOException(appContext.getString(R.string.list_err_discarded, source.name(), newCount, oldCount))
                    }
                    // Nunca se borra la lista vigente antes de tener la nueva en su sitio.
                    try {
                        SafeReplace.replace(tmp, target)
                    } catch (e: IOException) {
                        throw IOException(appContext.getString(R.string.list_err_save, source.name()), e)
                    }
                    prefs.edit()
                        .putString(etagKey, conn.getHeaderField("ETag"))
                        .putInt(countKey, newCount)
                        .remove("rejected_${source.id}")
                        .putLong(updatedKey, System.currentTimeMillis())
                        .apply()
                    true
                }
                else -> throw IOException(appContext.getString(R.string.list_err_http, source.name(), code))
            }
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    private companion object {
        const val MAX_LIST_BYTES = 120L * 1024 * 1024
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_FAILURES = "consecutive_failures"
        const val KEY_LAST_ERROR = "last_error"
    }
}

/** ¿Es HTTPS? (la dirección final de una descarga, después de seguir las redirecciones). */
internal fun isHttps(url: URL): Boolean = url.protocol.equals("https", ignoreCase = true)
