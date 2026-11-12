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
    const val DEFAULT_MINUTES = 20

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

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun logFile(ctx: Context) = File(ctx.filesDir, "log.csv")

    var Context.enabled: Boolean
        get() = prefs(this).getBoolean("enabled", false)
        set(v) = prefs(this).edit().putBoolean("enabled", v).apply()

    /** Aceptar los avisos de cookies al navegar (como haría la mayoría de la gente). */
    var Context.acceptCookies: Boolean
        get() = prefs(this).getBoolean("accept_cookies", true)
        set(v) = prefs(this).edit().putBoolean("accept_cookies", v).apply()

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

        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val n = NORMAL.size + ADS.size
        logFile(ctx).appendText(
            listOf(
                time, battery, if (vpn) 1 else 0, dnsOk, NORMAL.size, adsBlocked, ADS.size,
                0, 0, dnsMs / n, 0, errors.joinToString(" ").replace(',', ';'),
            ).joinToString(",") + "\n",
        )
    }
}

/** Salta con la alarma y arranca [BrowseService], que hace el ciclo y programa el siguiente. */
class CycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        with(LoadTest) { if (!ctx.enabled) return }
        ctx.startForegroundService(Intent(ctx, BrowseService::class.java))
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
