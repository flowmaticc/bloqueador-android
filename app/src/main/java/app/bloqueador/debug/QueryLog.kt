package app.bloqueador.debug

import android.content.Context
import app.bloqueador.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Registro detallado para pruebas: solo en compilaciones de depuración.
 *
 * Las llamadas van protegidas con `if (BuildConfig.DEBUG)`: en la versión publicada esa constante es
 * false, el compilador elimina las llamadas y esta clase no llega al APK. Se guarda en
 * `files/debug/consultas-AAAA-MM-DD.csv` (solo accesible con `run-as`) y
 * se borra a los [KEEP_DAYS] días.
 *
 * Columnas: ms_epoch, tipo, app, dominio, decision, regla, ms, rcode, tcp
 * - tipo Q: consulta (decision B = bloqueada, A = amenaza, P = permitida)
 * - tipo R: respuesta del DNS real (ms = tiempo de respuesta)
 * - tipo S: estadísticas cada 5 min (en "regla": consultas/bloqueadas/amenazas/caché/en vuelo/heap MB)
 */
object QueryLog {
    val enabled: Boolean get() = BuildConfig.DEBUG

    private const val KEEP_DAYS = 10
    private const val FLUSH_LINES = 200
    private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "query-log").apply { isDaemon = true } }
    private val buffer = StringBuilder()
    private var dir: File? = null
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun init(context: Context) {
        if (!enabled || dir != null) return
        dir = File(context.filesDir, "debug").apply { mkdirs() }
        writer.scheduleWithFixedDelay({ flush() }, 15, 15, TimeUnit.SECONDS)
        writer.execute {
            val limit = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
            dir?.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
        }
    }

    fun query(app: String?, domain: String, rule: String?) {
        val decision = when {
            rule == null -> "P"
            rule.startsWith("amenaza:") -> "A"
            else -> "B"
        }
        add("Q", app, domain, decision, rule?.removePrefix("amenaza:"), "", "", "")
    }

    fun answer(domain: String, ms: Long, rcode: Int, viaTcp: Boolean) =
        add("R", null, domain, "", "", ms.toString(), rcode.toString(), if (viaTcp) "1" else "")

    fun stats(queries: Long, blocked: Long, threats: Long, cacheHits: Long, inFlight: Int) {
        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        add("S", null, "", "", "$queries/$blocked/$threats/$cacheHits/$inFlight/$heapMb", "", "", "")
    }

    private fun add(vararg fields: Any?) {
        if (!enabled || dir == null) return
        val line = buildString {
            append(System.currentTimeMillis())
            fields.forEach { append(',').append(it?.toString()?.replace(',', ';') ?: "") }
            append('\n')
        }
        writer.execute {
            buffer.append(line)
            if (buffer.length > FLUSH_LINES * 80) flush()
        }
    }

    private fun flush() {
        val d = dir ?: return
        if (buffer.isEmpty()) return
        runCatching {
            val f = File(d, "consultas-${day.format(Date())}.csv")
            if (!f.exists()) f.writeText("ms,tipo,app,dominio,decision,regla,ms_respuesta,rcode,tcp\n")
            f.appendText(buffer.toString())
        }
        buffer.setLength(0)
    }

    /** Fuerza la escritura (al parar el filtro). */
    fun flushNow() {
        if (enabled) writer.execute { flush() }
    }
}
