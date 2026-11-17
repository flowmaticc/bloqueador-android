package app.bloqueador.diag

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import app.bloqueador.BloqueadorApp
import app.bloqueador.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Un tipo de error, agrupado: cuántas veces ha pasado y entre qué días. Sin nada del usuario. */
data class ErrorEntry(
    val tipo: String,
    val detalle: String,
    val traza: List<String>,
    val veces: Int,
    val primerDia: String,
    val ultimoDia: String,
) {
    /** Lo que identifica "el mismo error" para agruparlo. */
    val firma: String get() = (listOf(tipo, detalle) + traza.take(4)).joinToString("|")
}

/**
 * Informes de errores anónimos y opcionales. Solo funcionan si el usuario lo aceptó.
 *
 * Qué se guarda de cada error: un tipo fijo (p. ej. "cierre" o "listas_descarga"), un detalle corto
 * (p. ej. "HTTP 404"), las clases de la excepción y dónde ocurrió dentro del código de Garbillo. Nunca
 * el texto del mensaje (podría llevar el nombre de una web), ni dominios, historial, apps, red ni ningún
 * identificador del móvil. Los errores iguales se agrupan con un contador; la fecha va solo por día.
 *
 * Se envían como mucho una vez al día con [ErrorReportWorker], según la preferencia de red.
 */
object ErrorReports {
    private const val TAG = "ErrorReports"
    private const val FILE = "errores-pendientes.json"
    internal const val MAX_TIPOS = 20
    internal const val MAX_LINEAS = 8
    private val lock = Any()

    /** Anota los cierres inesperados de la app (y deja que Android siga con su gestión normal). */
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, "cierre", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Anota un error si el usuario aceptó los informes. No falla nunca. */
    fun record(context: Context, tipo: String, error: Throwable? = null, detalle: String? = null) {
        runCatching {
            if (BloqueadorApp.instance.settings.errorReports != true) return
            val day = today()
            val entry = ErrorEntry(clean(tipo), clean(detalle.orEmpty()), error?.let(::traza).orEmpty(), 1, day, day)
            synchronized(lock) { save(context, merge(load(context), entry)) }
        }.onFailure { Log.w(TAG, "No se pudo anotar el error", it) }
    }

    /** Lo que se enviaría ahora mismo, o null si no hay nada pendiente. */
    fun payload(context: Context): JSONObject? {
        val entries = synchronized(lock) { load(context) }
        if (entries.isEmpty()) return null
        val s = BloqueadorApp.instance.settings
        return JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("canal", s.updateChannel.name.lowercase())
            .put("android", Build.VERSION.SDK_INT)
            .put("fabricante", clean(Build.MANUFACTURER).lowercase())
            .put("nivel", s.mode.name.lowercase())
            .put("listas_extra", s.layers.size)
            .put("amenazas", s.threatProtection)
            .put("errores", JSONArray(entries.map { e ->
                JSONObject()
                    .put("tipo", e.tipo)
                    .put("detalle", e.detalle)
                    .put("traza", JSONArray(e.traza))
                    .put("veces", e.veces)
                    .put("primer_dia", e.primerDia)
                    .put("ultimo_dia", e.ultimoDia)
            }))
    }

    /** Texto para "Ver qué se enviaría". */
    fun preview(context: Context): String =
        payload(context)?.toString(2) ?: context.getString(app.bloqueador.R.string.reports_nothing_pending)

    fun clear(context: Context) {
        synchronized(lock) { File(context.filesDir, FILE).delete() }
    }

    // --- Lógica pura (con tests) ---

    /**
     * Clases de la excepción (y de sus causas) y líneas del código de Garbillo donde ocurrió.
     * Sin el texto del mensaje.
     */
    internal fun traza(error: Throwable): List<String> {
        val chain = generateSequence(error) { it.cause }.take(4).toList()
        val classes = chain.map { "clase ${it.javaClass.name}" }.distinct()
        val root = chain.last()
        val own = (root.stackTrace + error.stackTrace).filter { it.className.startsWith("app.bloqueador.") }.distinct()
        val frames = own.ifEmpty { root.stackTrace.take(3) }.take(MAX_LINEAS).map {
            "${it.className.removePrefix("app.bloqueador.")}.${it.methodName}:${it.lineNumber}"
        }
        return (classes + frames).map(::clean).take(MAX_LINEAS + 4)
    }

    /** Solo letras, números y unos pocos signos; corto. Nada que pueda formar un texto libre largo. */
    internal fun clean(text: String): String =
        text.replace(Regex("[^A-Za-z0-9 _.:$/<>-]"), "").trim().take(120)

    /** Suma [entry] a la lista: si ya estaba, cuenta una vez más; si no, se añade (hasta [MAX_TIPOS]). */
    internal fun merge(list: List<ErrorEntry>, entry: ErrorEntry): List<ErrorEntry> {
        val i = list.indexOfFirst { it.firma == entry.firma }
        if (i >= 0) {
            val old = list[i]
            return list.toMutableList().also {
                it[i] = old.copy(veces = (old.veces + entry.veces).coerceAtMost(9999), ultimoDia = entry.ultimoDia)
            }
        }
        return if (list.size >= MAX_TIPOS) list else list + entry
    }

    /** Detalle corto de un error de descarga: el código HTTP si lo hay, o "sin_conexion". */
    internal fun downloadDetail(message: String?): String =
        message?.let { Regex("HTTP \\d{3}").find(it)?.value } ?: "sin_conexion"

    // --- Almacenamiento en el propio móvil ---

    private fun load(context: Context): List<ErrorEntry> = runCatching {
        val f = File(context.filesDir, FILE)
        if (!f.isFile) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val t = o.getJSONArray("traza")
            ErrorEntry(
                o.getString("tipo"), o.getString("detalle"), (0 until t.length()).map { t.getString(it) },
                o.getInt("veces"), o.getString("primer_dia"), o.getString("ultimo_dia"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, list: List<ErrorEntry>) {
        val arr = JSONArray(list.map { e ->
            JSONObject().put("tipo", e.tipo).put("detalle", e.detalle).put("traza", JSONArray(e.traza))
                .put("veces", e.veces).put("primer_dia", e.primerDia).put("ultimo_dia", e.ultimoDia)
        })
        File(context.filesDir, FILE).writeText(arr.toString())
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
