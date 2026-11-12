package app.bloqueador.loadtest

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import app.bloqueador.loadtest.LoadTest.acceptCookies
import app.bloqueador.loadtest.LoadTest.enabled
import app.bloqueador.loadtest.LoadTest.intervalMinutes
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Un ciclo de la prueba de carga, en primer plano para que Android no lo corte:
 *  1. Comprobación DNS (dominios normales y de anuncios) → files/log.csv
 *  2. Abre [SITES_PER_CYCLE] webs reales, rotando por [Sites.all], en un navegador interno que pasa
 *     por el filtro, como lo haría una persona → files/webs.csv (una línea por web con los dominios
 *     que pidió).
 * Al terminar programa el siguiente ciclo y se para.
 */
class BrowseService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var queue: List<Sites.Site> = emptyList()
    private var webView: WebView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        if (wakeLock != null) return START_NOT_STICKY // ya hay un ciclo en marcha
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "loadtest:navegar").apply { acquire(10 * 60_000L) }

        Thread {
            runCatching { LoadTest.runCycle(this) }
                .onFailure { LoadTest.logFile(this).appendText("# error del ciclo DNS: $it\n") }
            val cycle = LoadTest.prefs(this).getInt("web_cycle", 0)
            LoadTest.prefs(this).edit().putInt("web_cycle", cycle + 1).apply()
            queue = (0 until SITES_PER_CYCLE).map { Sites.all[(cycle * SITES_PER_CYCLE + it) % Sites.all.size] }
            main.post { visitNext(0) }
        }.start()
        return START_NOT_STICKY
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun visitNext(index: Int) {
        if (index >= queue.size) return finish()
        val site = queue[index]
        val hosts = Collections.synchronizedSet(LinkedHashSet<String>())
        var requests = 0
        val started = SystemClock.elapsedRealtime()
        var done = false

        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            // Tamaño de pantalla de móvil, para que la web cargue como en uno de verdad.
            measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1080, 2200)
            onResume()
            resumeTimers()
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        webView = wv

        fun end(error: String) {
            if (done) return
            done = true
            val ms = SystemClock.elapsedRealtime() - started
            writeLine(site, ms, requests, hosts.toList(), error)
            wv.stopLoading()
            wv.destroy()
            webView = null
            // Cada web empieza sin cookies ni datos de la anterior.
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
            main.postDelayed({ visitNext(index + 1) }, 1_000)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                requests++
                request.url.host?.let { hosts.add(it.lowercase()) }
                return null
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (done) return
                // Como una persona: aceptar el aviso de cookies (si está activado) y bajar por la página.
                main.postDelayed({ if (!done) view.evaluateJavascript(if (acceptCookies) ACCEPT_JS else "0", null) }, 2_000)
                for (i in 1..6) main.postDelayed({ if (!done) view.evaluateJavascript("window.scrollBy(0, 1400)", null) }, 3_000L + i * 2_500)
                main.postDelayed({ end("") }, SETTLE_MS)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) main.postDelayed({ end("error:${error.errorCode}") }, 3_000)
            }
        }
        main.postDelayed({ end("tiempo") }, MAX_MS)
        wv.loadUrl(site.url)
    }

    private fun writeLine(site: Sites.Site, ms: Long, requests: Int, hosts: List<String>, error: String) {
        val f = File(filesDir, "webs.csv")
        if (!f.exists()) f.writeText("hora,web,categoria,ms,peticiones,dominios,cookies_aceptadas,error,lista_dominios\n")
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        f.appendText(
            listOf(
                time, site.url, site.category, ms, requests, hosts.size, if (acceptCookies) 1 else 0, error,
                hosts.joinToString(";"),
            ).joinToString(",") + "\n",
        )
    }

    private fun finish() {
        if (enabled) LoadTest.schedule(this, intervalMinutes * 60_000L)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        webView?.destroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("carga", "Prueba de carga", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, "carga")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Prueba de carga: navegando")
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 77
        const val SITES_PER_CYCLE = 5
        private const val SETTLE_MS = 22_000L // tras cargar: cookies, desplazamiento y anuncios tardíos
        private const val MAX_MS = 45_000L

        /** Acepta avisos de cookies habituales (APIs de las plataformas de consentimiento y botones). */
        private val ACCEPT_JS = """
            (function(){
              try { if (window.Didomi && Didomi.setUserAgreeToAll) Didomi.setUserAgreeToAll(); } catch(e){}
              try { if (window.OneTrust && OneTrust.AllowAll) OneTrust.AllowAll(); } catch(e){}
              try { if (window.Cookiebot && Cookiebot.submitCustomConsent) Cookiebot.submitCustomConsent(true,true,true); } catch(e){}
              var re = /^(aceptar|acepto|aceptar todo|aceptar y continuar|acepto y contin[uú]o gratis|accept|accept all|i accept|agree|i agree|de acuerdo|entendido|consentir)/i;
              var docs=[document];
              document.querySelectorAll('iframe').forEach(function(f){ try{ if(f.contentDocument) docs.push(f.contentDocument);}catch(e){} });
              docs.forEach(function(d){
                d.querySelectorAll('button, a[role=button], [role=button], input[type=button]').forEach(function(b){
                  var t=(b.innerText||b.value||'').trim();
                  if (t.length < 40 && re.test(t)) { try { b.click(); } catch(e){} }
                });
              });
            })();
        """.trimIndent()
    }
}
