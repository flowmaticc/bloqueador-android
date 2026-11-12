package app.bloqueador.loadtest

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Prueba de carga larga: cada [intervalMinutes] despierta el móvil (también en reposo profundo),
 * resuelve dominios normales y de anuncios con el DNS del sistema —que pasa por el filtro de
 * Bloqueador— y descarga algunas webs reales. Cada ciclo deja una línea en `files/log.csv`:
 *
 *   hora, batería %, vpn (1/0), dns ok/total, anuncios bloqueados/total, web ok/total,
 *   ms medio DNS, ms medio web, errores
 *
 * Si el filtro muere o deja de responder se ve enseguida: vpn=0, o dns ok baja, o anuncios
 * bloqueados baja.
 */
object LoadTest {

    private const val PREFS = "loadtest"
    const val DEFAULT_MINUTES = 15

    /** Dominios corrientes: deben resolverse siempre. */
    private val NORMAL = listOf(
        "www.wikipedia.org", "www.google.com", "www.youtube.com", "github.com", "elpais.com",
        "www.marca.com", "www.bbc.com", "www.amazon.es", "www.reddit.com", "www.whatsapp.com",
        "www.instagram.com", "www.netflix.com", "www.microsoft.com", "www.apple.com", "www.cloudflare.com",
    )

    /** Dominios de anuncios y rastreo: el filtro debe devolver 0.0.0.0. */
    private val ADS = listOf(
        "pagead2.googlesyndication.com", "googleads.g.doubleclick.net", "stats.g.doubleclick.net",
        "www.google-analytics.com", "ssl.google-analytics.com", "pixel.facebook.com", "an.facebook.com",
        "static.hotjar.com", "script.hotjar.com", "analytics.tiktok.com", "app-measurement.com",
        "ads.yahoo.com", "adcolony.com", "cdn.mouseflow.com", "luckyorange.com",
    )

    /** Webs reales con publicidad; se descargan unas pocas por ciclo, rotando. */
    private val WEBS = listOf(
        "https://www.wikipedia.org/", "https://www.eldiario.es/", "https://www.marca.com/",
        "https://www.bbc.com/", "https://www.20minutos.es/", "https://github.com/",
        "https://as.com/", "https://www.xataka.com/",
    )
    private const val WEBS_PER_CYCLE = 3

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun logFile(ctx: Context) = File(ctx.filesDir, "log.csv")

    var Context.enabled: Boolean
        get() = prefs(this).getBoolean("enabled", false)
        set(v) = prefs(this).edit().putBoolean("enabled", v).apply()

    var Context.intervalMinutes: Int
        get() = prefs(this).getInt("minutes", DEFAULT_MINUTES)
        set(v) = prefs(this).edit().putInt("minutes", v).apply()

    fun start(ctx: Context, minutes: Int) {
        ctx.intervalMinutes = minutes.coerceAtLeast(1)
        ctx.enabled = true
        val log = logFile(ctx)
        if (!log.exists()) {
            log.writeText("hora,bateria,vpn,dns_ok,dns_total,anuncios_bloq,anuncios_total,web_ok,web_total,ms_dns,ms_web,errores\n")
        }
        schedule(ctx, 5_000)
    }

    fun stop(ctx: Context) {
        ctx.enabled = false
        alarm(ctx).cancel(pending(ctx))
    }

    fun schedule(ctx: Context, delayMs: Long) {
        // "AllowWhileIdle": salta también en Doze (como mucho una vez cada ~9 min por app).
        alarm(ctx).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pending(ctx),
        )
    }

    private fun alarm(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)
    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, CycleReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Un ciclo completo. Se ejecuta en un hilo aparte, con el móvil despierto. */
    fun runCycle(ctx: Context) {
        val errors = mutableListOf<String>()
        val battery = ctx.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val vpn = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        var dnsMs = 0L
        var dnsOk = 0
        for (d in NORMAL) {
            val t = SystemClock.elapsedRealtime()
            try {
                val addrs = InetAddress.getAllByName(d)
                if (addrs.any { !it.isAnyLocalAddress && !it.isLoopbackAddress }) dnsOk++ else errors += "bloqueado:$d"
            } catch (e: Exception) {
                errors += "dns:$d"
            }
            dnsMs += SystemClock.elapsedRealtime() - t
        }
        var adsBlocked = 0
        for (d in ADS) {
            val t = SystemClock.elapsedRealtime()
            try {
                val addrs = InetAddress.getAllByName(d)
                if (addrs.all { it.isAnyLocalAddress || it.isLoopbackAddress }) adsBlocked++ else errors += "pasa:$d"
            } catch (e: Exception) {
                adsBlocked++ // sin respuesta tampoco carga el anuncio
            }
            dnsMs += SystemClock.elapsedRealtime() - t
        }

        val cycle = prefs(ctx).getInt("cycle", 0)
        prefs(ctx).edit().putInt("cycle", cycle + 1).apply()
        val webs = (0 until WEBS_PER_CYCLE).map { WEBS[(cycle * WEBS_PER_CYCLE + it) % WEBS.size] }
        var webOk = 0
        var webMs = 0L
        for (u in webs) {
            val t = SystemClock.elapsedRealtime()
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 8_000
                c.readTimeout = 8_000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
                val code = c.responseCode
                (if (code < 400) c.inputStream else c.errorStream)?.use { s ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (total < 512 * 1024) { val n = s.read(buf); if (n < 0) break; total += n }
                }
                c.disconnect()
                if (code < 400) webOk++ else errors += "http$code:${URL(u).host}"
            } catch (e: Exception) {
                errors += "web:${URL(u).host}:${e.javaClass.simpleName}"
            }
            webMs += SystemClock.elapsedRealtime() - t
        }

        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val n = NORMAL.size + ADS.size
        logFile(ctx).appendText(
            listOf(
                time, battery, if (vpn) 1 else 0, dnsOk, NORMAL.size, adsBlocked, ADS.size,
                webOk, webs.size, dnsMs / n, webMs / webs.size, errors.joinToString(" ").replace(',', ';'),
            ).joinToString(",") + "\n",
        )
    }
}

/** Salta con la alarma: mantiene el móvil despierto, ejecuta un ciclo y programa el siguiente. */
class CycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        with(LoadTest) { if (!ctx.enabled) return }
        // Las alarmas llegan como difusión en segundo plano: hay ~60 s antes de un ANR. Los
        // tiempos de espera de red (8 s) mantienen el ciclo muy por debajo.
        val result = goAsync()
        val wl = ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "loadtest:ciclo").apply { acquire(2 * 60_000L) }
        Thread {
            try {
                LoadTest.runCycle(ctx)
            } catch (e: Throwable) {
                LoadTest.logFile(ctx).appendText("# error del ciclo: $e\n")
            } finally {
                with(LoadTest) { if (ctx.enabled) LoadTest.schedule(ctx, ctx.intervalMinutes * 60_000L) }
                wl.release()
                result.finish()
            }
        }.start()
    }
}

/** Tras reiniciar el móvil, reanuda la prueba si estaba en marcha. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        with(LoadTest) {
            if (ctx.enabled) {
                logFile(ctx).appendText("# reinicio del móvil\n")
                schedule(ctx, 60_000)
            }
        }
    }
}
