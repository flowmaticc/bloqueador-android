package app.bloqueador.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.bloqueador.BloqueadorApp
import app.bloqueador.history.HistoryEvent
import app.bloqueador.history.SiteGuess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.Collections

/**
 * Vista previa de una entrada del historial: carga la web donde probablemente apareció el dominio,
 * dejando pasar ese dominio solo dentro de esta ventana, y rodea en rojo lo que carga. Así el
 * usuario ve si lo bloqueado era un anuncio, un vídeo o algo que sí le interesaba.
 *
 * El filtro DNS no ve la dirección completa del anuncio (va cifrada), así que no se puede repetir la
 * petición exacta: se recarga la página y se muestra lo que ese dominio pone en ella ahora.
 *
 * Bloqueador no pasa por su propio túnel, así que esta ventana no depende del filtro: es ella la que
 * decide, petición a petición, qué carga ([PreviewClient]). No usa la sesión del navegador y borra
 * cookies y datos al cerrarse.
 */
@Composable
fun WebPreviewDialog(event: HistoryEvent, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                var url by remember { mutableStateOf<String?>(null) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { if (url != null) url = null else onDismiss() }) {
                        Text(if (url != null) "‹ Otra web" else "‹ Cerrar")
                    }
                    Text(
                        event.domain,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                val current = url
                if (current == null) {
                    SitePicker(event) { url = it }
                } else {
                    PreviewPage(event, current, onDismiss)
                }
            }
        }
    }
}

/** Paso 1: elegir en qué web se vio (candidatas del historial o una dirección escrita). */
@Composable
private fun SitePicker(event: HistoryEvent, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val app = BloqueadorApp.instance
    val candidates by produceState<List<String>?>(null, event.id) { value = app.history.guessSites(event) }
    val isBrowser = remember(event.appPackage) { event.appPackage?.let { isBrowser(context, it) } }
    var typed by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "¿En qué web apareció?",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Garbillo solo ve el nombre del dominio, no la página. Estas son las webs que " +
                "${appLabel(context, event.appPackage)} abrió en esos momentos. Se cargará la que elijas " +
                "dejando pasar solo ${event.domain}, y lo que cargue se rodeará en rojo.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (isBrowser == false) {
            Text(
                "${appLabel(context, event.appPackage)} no es un navegador: los anuncios dentro de una app " +
                    "los dibuja la propia app y no se pueden reproducir aquí. Puedes probar igualmente con " +
                    "la web del servicio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when {
            candidates == null -> Text("Buscando…")
            candidates!!.isEmpty() -> Text(
                "No hay webs cercanas en el historial (Android pudo reutilizar respuestas guardadas). " +
                    "Escribe la dirección de la web donde lo viste.",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> candidates!!.forEach { host ->
                OutlinedButton(onClick = { onPick("https://$host/") }, modifier = Modifier.fillMaxWidth()) {
                    Text(host)
                }
            }
        }
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Otra dirección (p. ej. marca.com)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { normalizeUrl(typed)?.let(onPick) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = normalizeUrl(typed) != null, onClick = { normalizeUrl(typed)?.let(onPick) }) {
            Text("Abrir")
        }
        Text(
            "La vista previa no usa tu sesión del navegador ni sus cookies, y lo borra todo al cerrar. " +
                "Gasta datos como abrir la web normalmente.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Paso 2: la web, con el dominio resaltado y el resumen de lo que ha cargado. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewPage(event: HistoryEvent, url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = BloqueadorApp.instance
    val settings = app.settings
    val scope = rememberCoroutineScope()

    var allowAll by remember { mutableStateOf(false) }
    val client = remember(allowAll) { PreviewClient(event.domain, allowAll) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var requests by remember { mutableIntStateOf(0) }
    var otherBlocked by remember { mutableIntStateOf(0) }
    var visible by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var showUrls by remember { mutableStateOf(false) }

    // Cambiar "permitir todo" recarga la página con el nuevo criterio.
    LaunchedEffect(client, webView) {
        val wv = webView ?: return@LaunchedEffect
        wv.webViewClient = client
        visible = 0
        loaded = false
        wv.loadUrl(url)
    }
    // Los anuncios llegan tarde y por partes: se vuelve a buscar cada poco mientras la ventana está abierta.
    LaunchedEffect(client, webView) {
        val wv = webView ?: return@LaunchedEffect
        while (true) {
            delay(1_500)
            requests = client.targetUrls.size
            otherBlocked = client.otherBlocked
            loaded = loaded || client.finished
            wv.evaluateJavascript(highlightScript(event.domain)) { result ->
                result?.trim('"')?.toIntOrNull()?.let { visible = it }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }

    fun apply(message: String, change: () -> Unit) {
        change()
        scope.launch {
            app.reloadEngine()
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }

    Column(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // Sin esto la web se mide a su altura completa y no se puede desplazar.
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    this.settings.javaScriptEnabled = true // los anuncios se montan con JavaScript
                    this.settings.domStorageEnabled = true
                    this.settings.allowFileAccess = false
                    this.settings.allowContentAccess = false
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webView = this
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Column(
            Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "En rojo: lo que carga ${event.domain}",
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "$requests peticiones a este dominio · $visible elementos visibles" +
                    if (!allowAll && otherBlocked > 0) " · $otherBlocked bloqueadas de otros dominios" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            val hint = when {
                !loaded -> "Cargando…"
                requests == 0 && !allowAll ->
                    "Esta web no ha pedido ${event.domain} ahora. A veces un anuncio depende de otros " +
                        "dominios bloqueados: activa \"Cargar también lo demás\". $CONSENT_HINT"
                requests == 0 ->
                    "Esta web no ha pedido ${event.domain} ahora. $CONSENT_HINT Si no, prueba con otra " +
                        "página de la web (un artículo en vez de la portada) o más tarde."
                visible == 0 -> "Ha cargado cosas, pero nada visible: suele ser estadística o rastreo."
                else -> "Desplázate por la página para ver todos los recuadros rojos."
            }
            Text(hint, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Cargar también lo demás bloqueado",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = allowAll, onCheckedChange = { allowAll = it })
            }
            if (requests > 0) {
                TextButton(onClick = { showUrls = !showUrls }) {
                    Text(if (showUrls) "Ocultar peticiones" else "Ver peticiones")
                }
                if (showUrls) {
                    client.targetUrls.toList().take(30).forEach {
                        Text(it.take(160), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val allowed = settings.allowedDomains.contains(event.domain)
                if (event.blocked && !allowed) {
                    Button(onClick = {
                        apply("${event.domain} permitido") { settings.allowedDomains = settings.allowedDomains + event.domain }
                    }) { Text("Permitir siempre") }
                    OutlinedButton(onClick = onDismiss) { Text("Mantener bloqueado") }
                } else if (!event.blocked) {
                    Button(onClick = {
                        apply("${event.domain} bloqueado") { settings.blockedDomains = settings.blockedDomains + event.domain }
                    }) { Text("Bloquear siempre") }
                    OutlinedButton(onClick = onDismiss) { Text("Dejarlo así") }
                }
            }
        }
    }
}

/** Las webs europeas no cargan anuncios hasta aceptar cookies; en la vista previa se borran al cerrar. */
private const val CONSENT_HINT =
    "Muchas webs no cargan anuncios hasta que aceptas sus cookies: puedes aceptarlas aquí, se borran al cerrar."

/**
 * Decide qué carga la vista previa. El dominio elegido siempre pasa; lo demás que el filtro
 * bloquearía, solo si [allowAll]. Así se ve exactamente qué cambiaría al permitir ese dominio.
 */
private class PreviewClient(private val target: String, private val allowAll: Boolean) : WebViewClient() {
    val targetUrls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile var otherBlocked = 0
    @Volatile var finished = false

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val host = request.url.host ?: return null
        if (SiteGuess.matches(host, target)) {
            targetUrls.add(request.url.toString())
            return null
        }
        if (!allowAll && BloqueadorApp.instance.engine.peek(host) != null) {
            otherBlocked++
            return WebResourceResponse("text/plain", "utf-8", 403, "Bloqueado", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        return null
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // Solo webs: nada de abrir otras apps (intent:, market:…) desde la vista previa.
        return request.url.scheme !in setOf("http", "https")
    }

    override fun onPageFinished(view: WebView, url: String?) {
        finished = true
    }
}

/**
 * JavaScript que rodea en rojo los elementos cargados desde [domain] (imágenes, iframes, vídeos,
 * enlaces y fondos), también dentro de iframes accesibles, y lleva la vista al primero la primera vez.
 * Devuelve cuántos elementos visibles hay marcados.
 */
private fun highlightScript(domain: String): String {
    val d = domain.lowercase().replace("\\", "").replace("'", "")
    return """
(function(){
  var t='$d';
  function hit(u){ try { var h=new URL(u, location.href).hostname.toLowerCase(); return h===t || h.endsWith('.'+t); } catch(e){ return false; } }
  function mark(el){
    if(!el || el.__bq) return; el.__bq=1;
    el.style.setProperty('outline','4px solid #e53935','important');
    el.style.setProperty('outline-offset','-2px','important');
    (window.__bq = window.__bq || []).push(el);
  }
  function urlOf(e){
    var u = e.currentSrc || e.src || e.href || e.data || '';
    if(!u){ var s=(e.getAttribute && e.getAttribute('style'))||''; var m=s.match(/url\(["']?([^"')]+)/); if(m) u=m[1]; }
    return (typeof u === 'string') ? u : '';
  }
  function scan(doc, frame){
    var els = doc.querySelectorAll('img,iframe,video,source,embed,object,a[href],[style*="url("]');
    for(var i=0;i<els.length;i++){
      var e=els[i], u=urlOf(e);
      if(u && hit(u)){ mark(e.tagName==='SOURCE' ? e.parentElement : e); if(frame) mark(frame); }
    }
    var fr = doc.querySelectorAll('iframe');
    for(var j=0;j<fr.length;j++){ try { if(fr[j].contentDocument) scan(fr[j].contentDocument, frame || fr[j]); } catch(x){} }
  }
  scan(document, null);
  var n=0, first=null, all=window.__bq||[];
  for(var k=0;k<all.length;k++){ var r=all[k].getBoundingClientRect(); if(r.width>=8 && r.height>=8){ n++; if(!first) first=all[k]; } }
  if(first && !window.__bqScrolled){ window.__bqScrolled=1; first.scrollIntoView({block:'center'}); }
  return String(n);
})();
""".trimIndent()
}

/** "marca.com" → "https://marca.com/"; null si no parece una dirección web. */
internal fun normalizeUrl(input: String): String? {
    val s = input.trim()
    if (s.isEmpty() || ' ' in s) return null
    val withScheme = if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
    val host = Uri.parse(withScheme).host ?: return null
    return if ('.' in host) withScheme else null
}

private fun isBrowser(context: android.content.Context, pkg: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
    return context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_ALL).any { it.activityInfo.packageName == pkg }
}
