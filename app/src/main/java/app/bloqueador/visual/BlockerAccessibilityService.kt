package app.bloqueador.visual

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.bloqueador.BloqueadorApp
import app.bloqueador.apps.SensitiveApps
import app.bloqueador.history.HistoryEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Bloqueo por elemento visual (sección 9, opcional).
 *
 * Salvaguardas:
 * - Solo recibe eventos de las apps que tienen reglas y no están en pausa (el resto del sistema
 *   ni siquiera le llega). Sin reglas, no recibe ningún evento.
 * - Nunca interpreta el contenido de la pantalla: solo compara atributos con los elementos que el
 *   usuario marcó a mano.
 * - Ocultar = tapar con un recuadro propio (las apps ajenas no se pueden modificar). Pulsar solo
 *   si el usuario eligió esa acción para ese elemento.
 */
class BlockerAccessibilityService : AccessibilityService() {

    private val app get() = application as BloqueadorApp
    private val store get() = app.visualRules
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wm: WindowManager

    private val covers = mutableListOf<View>()
    private val allCovers = LinkedHashSet<View>()
    private var coveredPackage: String? = null
    private var watching = false
    private val lastClick = HashMap<String, Long>()
    private val clickedVisible = HashSet<String>()
    private val lastRecord = HashMap<String, Long>()
    private val lastThumb = HashMap<String, Long>()
    private var capturing = false

    // Modo selección
    private var selecting = false
    private var pill: View? = null
    private var catcher: View? = null
    private var highlight: View? = null
    private var panel: View? = null

    private val scanRunnable = Runnable { scan() }
    private val settleRunnable = Runnable { scan() }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WindowManager::class.java)
        applyConfig()
        scope.launch {
            store.state.collect {
                main.post {
                    applyConfig()
                    scheduleScan()
                }
            }
        }
    }

    override fun onDestroy() {
        instance = null
        endSelection()
        clearCovers()
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (selecting) return
        scheduleScan()
        // Cambio de ventana: se vuelve a mirar cuando termina la transición, por si en la primera
        // revisión la app anterior aún figuraba delante (si no, un recuadro podría quedarse encima).
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            main.removeCallbacks(settleRunnable)
            main.postDelayed(settleRunnable, SETTLE_DELAY_MS)
        }
    }

    /**
     * Qué eventos recibe el servicio, para gastar lo mínimo:
     * - Sin reglas: ninguno.
     * - Con reglas, fuera de esas apps: solo los cambios de ventana (poco frecuentes), para saber
     *   cuándo se entra en una app vigilada y quitar los recuadros al salir.
     * - Dentro de una app vigilada: también cambios de contenido y desplazamientos.
     */
    private fun applyConfig() {
        val info = serviceInfo ?: return
        val active = store.state.value.activeApps
        when {
            selecting -> {
                info.packageNames = null
                info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            }
            active.isEmpty() -> {
                info.packageNames = arrayOf(packageName)
                info.eventTypes = 0
                watching = false
            }
            else -> {
                info.packageNames = null
                info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    if (watching) AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED else 0
            }
        }
        serviceInfo = info
        if (active.isEmpty()) clearCovers()
    }

    private fun setWatching(on: Boolean) {
        if (!on) clearCovers() // al dejar de vigilar no puede quedar nada tapado
        if (watching == on) return
        watching = on
        applyConfig()
    }

    /** Agrupa ráfagas de eventos (scroll, animaciones) en una sola revisión. */
    private fun scheduleScan() {
        main.removeCallbacks(scanRunnable)
        main.postDelayed(scanRunnable, SCAN_DELAY_MS)
    }

    private fun scan() {
        if (selecting) return
        val root = rootInActiveWindow ?: return clearCovers()
        val pkg = root.packageName?.toString() ?: return clearCovers()
        val rules = store.state.value.rulesFor(pkg)
        if (rules.isEmpty()) {
            setWatching(false)
            return clearCovers()
        }
        setWatching(true)

        val toCover = mutableListOf<Rect>()
        val seen = HashSet<String>()
        for (rule in rules) {
            for (node in findMatches(root, rule)) {
                if (!node.isVisibleToUser) continue
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (bounds.isEmpty) continue
                when (rule.action) {
                    VisualAction.HIDE -> {
                        if (thumbnailDue(rule)) {
                            // Primero la miniatura (sin nuestro recuadro encima) y luego se tapa.
                            captureThenRecord(rule, pkg, bounds)
                            return
                        }
                        toCover += bounds
                        record(rule, pkg, null)
                    }
                    VisualAction.CLICK -> {
                        seen += rule.id
                        click(rule, node, pkg, bounds)
                    }
                }
            }
        }
        // Un elemento que ya no está en pantalla podrá pulsarse otra vez cuando vuelva a aparecer.
        clickedVisible.retainAll(seen)
        showCovers(pkg, toCover)
    }

    private fun findMatches(root: AccessibilityNodeInfo, rule: VisualRule): List<AccessibilityNodeInfo> =
        when {
            // Vistas nativas ("paquete:id/nombre"): búsqueda directa de Android, muy rápida.
            rule.key == VisualRule.Key.VIEW_ID && rule.viewId!!.contains(":id/") ->
                root.findAccessibilityNodeInfosByViewId(rule.viewId)
            rule.key == VisualRule.Key.TEXT -> root.findAccessibilityNodeInfosByText(rule.text!!)
            // Elementos de una web (id HTML, p. ej. "mb-close") y descripciones: recorrido del árbol.
            else -> collect(root, MAX_NODES).toList()
        }.filter { rule.matches(it.snapshot()) }

    /**
     * Pulsa el elemento una sola vez cada vez que aparece: si sigue en pantalla tras pulsarlo
     * (no era un botón de cerrar), no se insiste.
     */
    private fun click(rule: VisualRule, node: AccessibilityNodeInfo, pkg: String, bounds: Rect) {
        val now = System.currentTimeMillis()
        if (rule.id in clickedVisible) return
        if (now - (lastClick[rule.id] ?: 0) < CLICK_COOLDOWN_MS) return
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            Log.i(TAG, "Pulsado ${rule.label} en $pkg")
            lastClick[rule.id] = now
            clickedVisible += rule.id
            if (thumbnailDue(rule)) captureThenRecord(rule, pkg, bounds, rescan = false) else record(rule, pkg, null)
        }
    }

    // ---------- Recuadros que tapan ----------

    private fun showCovers(pkg: String, rects: List<Rect>) {
        coveredPackage = pkg
        main.removeCallbacks(watchdog)
        if (rects.isNotEmpty()) main.postDelayed(watchdog, WATCHDOG_MS)
        rects.forEachIndexed { i, r ->
            val params = overlayParams(r.left, r.top, r.width(), r.height(), touchable = true)
            if (i < covers.size) {
                runCatching { wm.updateViewLayout(covers[i], params) }
            } else {
                val v = coverView()
                if (runCatching { wm.addView(v, params) }.isSuccess) {
                    covers += v
                    allCovers += v
                }
            }
        }
        while (covers.size > rects.size) removeWindow(covers.removeAt(covers.lastIndex))
    }

    private fun clearCovers() {
        main.removeCallbacks(watchdog)
        // Se recorren todos los recuadros creados, no solo los de la última revisión, para que
        // ninguno se quede "huérfano" encima de otra app si coinciden una salida y una revisión.
        allCovers.toList().forEach { removeWindow(it) }
        allCovers.clear()
        covers.clear()
        coveredPackage = null
    }

    /**
     * Mientras haya algo tapado, se comprueba cada [WATCHDOG_MS] que la app de delante sigue siendo
     * la vigilada. Red de seguridad por si Android no avisa de un cambio de app (pasa en algunas
     * transiciones). Sin recuadros no hay comprobaciones.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (allCovers.isEmpty()) return
            val pkg = rootInActiveWindow?.packageName?.toString()
            if (pkg == null || pkg != coveredPackage) scan() else main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun coverView(): View = FrameLayout(this).apply {
        val dark = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        setBackgroundColor(if (dark) Color.rgb(0x2B, 0x29, 0x30) else Color.rgb(0xE8, 0xE6, 0xEC))
        addView(TextView(context).apply {
            text = "Bloqueado"
            setTextColor(if (dark) Color.rgb(0x93, 0x8F, 0x99) else Color.rgb(0x79, 0x74, 0x7E))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11f) // sin escalar con la fuente del sistema
            maxLines = 1
        }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // En recuadros pequeños (un icono) el texto no cabe: solo el fondo.
        addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            (v as FrameLayout).getChildAt(0).visibility =
                if (r - l >= dp(72) && b - t >= dp(24)) View.VISIBLE else View.GONE
        }
        // Se tragan los toques: así no se puede pulsar el anuncio tapado.
        setOnTouchListener { _, _ -> true }
    }

    // ---------- Historial y miniaturas ----------

    private fun thumbnailDue(rule: VisualRule): Boolean {
        if (Build.VERSION.SDK_INT < 30 || capturing) return false
        return System.currentTimeMillis() - (lastThumb[rule.id] ?: 0) > THUMB_INTERVAL_MS
    }

    /** Anota el bloqueo en el historial, como mucho una vez cada [RECORD_INTERVAL_MS] por regla. */
    private fun record(rule: VisualRule, pkg: String, thumbnail: String?) {
        val now = System.currentTimeMillis()
        if (thumbnail == null && now - (lastRecord[rule.id] ?: 0) < RECORD_INTERVAL_MS) return
        lastRecord[rule.id] = now
        val event = HistoryEvent(
            timestamp = now,
            domain = rule.label,
            appPackage = pkg,
            blocked = true,
            source = "accessibility",
            matchedRule = "visual:${rule.id}:${rule.action.name}",
            count = 1,
            thumbnailPath = thumbnail,
        )
        scope.launch { app.history.write(listOf(event)) }
    }

    private fun captureThenRecord(rule: VisualRule, pkg: String, bounds: Rect, rescan: Boolean = true) {
        if (Build.VERSION.SDK_INT < 30) return
        capturing = true
        lastThumb[rule.id] = System.currentTimeMillis()
        Thumbnails.capture(this, bounds) { path ->
            capturing = false
            record(rule, pkg, path)
            if (rescan) scheduleScan()
        }
    }

    // ---------- Modo selección ----------

    fun startSelection() {
        if (selecting) return
        selecting = true
        applyConfig()
        clearCovers()
        showPill()
    }

    private fun showPill() {
        removeSelectionWindows()
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
            background = rounded(Color.rgb(0x67, 0x50, 0xA4), dp(28).toFloat())
            elevation = dp(6).toFloat()
            addView(TextView(context).apply {
                text = "Abre la app y toca aquí para elegir"
                setTextColor(Color.WHITE)
                textSize = 14f
                setOnClickListener { showCatcher() }
            })
            addView(Button(context).apply {
                text = "✕"
                setOnClickListener { endSelection() }
            }, LinearLayout.LayoutParams(dp(48), dp(40)))
        }
        val params = overlayParams(0, dp(96), -2, -2, touchable = true).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        if (runCatching { wm.addView(view, params) }.isSuccess) pill = view
    }

    private fun showCatcher() {
        removeSelectionWindows()
        val view = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(40, 0x67, 0x50, 0xA4))
            addView(TextView(context).apply {
                text = "Toca el elemento que quieres bloquear"
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = rounded(Color.argb(220, 0x1D, 0x1B, 0x20), dp(12).toFloat())
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(64) })
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_UP) {
                    val x = e.rawX.toInt()
                    val y = e.rawY.toInt()
                    removeSelectionWindows()
                    // Se espera a que desaparezca la capa para leer la pantalla de la app de debajo.
                    main.postDelayed({ pick(x, y) }, 200)
                }
                true
            }
        }
        val params = overlayParams(0, 0, -1, -1, touchable = true)
        if (runCatching { wm.addView(view, params) }.isSuccess) catcher = view
    }

    private fun pick(x: Int, y: Int) {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || pkg == null || pkg == packageName) {
            toast("Primero abre la app donde está el elemento")
            return showPill()
        }
        val node = nodeAt(root, x, y)
        if (node == null) {
            toast("Ese elemento no se puede reconocer. Prueba a tocar justo encima de otro cercano.")
            return showPill()
        }
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val snapshot = node.snapshot()
        showConfirm(pkg, snapshot, bounds)
    }

    /** El elemento más pequeño bajo el dedo que tenga algo con lo que reconocerlo después. */
    private fun nodeAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val r = Rect()
        return collect(root, MAX_NODES)
            .filter { n ->
                n.getBoundsInScreen(r)
                n.isVisibleToUser && r.contains(x, y) && VisualRule.canIdentify(n.snapshot())
            }
            .minByOrNull { n -> n.getBoundsInScreen(r); r.width().toLong() * r.height() }
    }

    private fun showConfirm(pkg: String, node: NodeSnapshot, bounds: Rect) {
        removeSelectionWindows()
        highlight = View(this).apply {
            background = GradientDrawable().apply {
                setStroke(dp(3), Color.rgb(0xB3, 0x26, 0x1E))
                setColor(Color.argb(50, 0xB3, 0x26, 0x1E))
            }
        }.also {
            runCatching {
                wm.addView(it, overlayParams(bounds.left, bounds.top, bounds.width(), bounds.height(), touchable = false))
            }
        }

        val preview = VisualRule.from(pkg, node, VisualAction.HIDE)
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            background = rounded(Color.rgb(0xF3, 0xED, 0xF7), dp(20).toFloat())
            elevation = dp(8).toFloat()
            addView(TextView(context).apply {
                text = "¿Bloquear este elemento?"
                textSize = 18f
                setTextColor(Color.rgb(0x1D, 0x1B, 0x20))
            })
            addView(TextView(context).apply {
                text = preview.label + " · " + appName(pkg)
                textSize = 13f
                setTextColor(Color.rgb(0x49, 0x45, 0x4F))
            })
            if (SensitiveApps.isSensitive(pkg)) {
                addView(TextView(context).apply {
                    text = "Es una app de banca o pagos. No se recomienda: puede dejar de funcionar."
                    textSize = 13f
                    setTextColor(Color.rgb(0xB3, 0x26, 0x1E))
                })
            }
            fun row(vararg buttons: Pair<String, () -> Unit>) = addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    buttons.forEach { (label, action) ->
                        addView(
                            Button(context).apply {
                                text = label
                                isAllCaps = false
                                maxLines = 1
                                setOnClickListener { action() }
                            },
                            LinearLayout.LayoutParams(0, -2, 1f),
                        )
                    }
                },
            )
            row("Ocultar" to { save(pkg, node, VisualAction.HIDE) }, "Pulsar" to { save(pkg, node, VisualAction.CLICK) })
            row("Elegir otro" to { showCatcher() }, "Cancelar" to { endSelection() })
        }
        val params = overlayParams(0, dp(24), -1, -2, touchable = true).apply {
            gravity = Gravity.BOTTOM
            horizontalMargin = 0f
        }
        if (runCatching { wm.addView(view, params) }.isSuccess) panel = view
    }

    private fun save(pkg: String, node: NodeSnapshot, action: VisualAction) {
        store.add(VisualRule.from(pkg, node, action))
        toast(if (action == VisualAction.HIDE) "Se ocultará en ${appName(pkg)}" else "Se pulsará en ${appName(pkg)}")
        endSelection()
    }

    private fun endSelection() {
        removeSelectionWindows()
        if (!selecting) return
        selecting = false
        applyConfig()
        scheduleScan()
    }

    private fun removeSelectionWindows() {
        listOf(pill, catcher, highlight, panel).forEach { it?.let(::removeWindow) }
        pill = null; catcher = null; highlight = null; panel = null
    }

    // ---------- Utilidades ----------

    private fun overlayParams(x: Int, y: Int, w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h, x, y,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun removeWindow(v: View) {
        allCovers.remove(v)
        if (runCatching { wm.removeViewImmediate(v) }.isFailure) runCatching { wm.removeView(v) }
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun appName(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val TAG = "VisualBlocker"
        private const val SCAN_DELAY_MS = 200L
        private const val SETTLE_DELAY_MS = 900L
        private const val WATCHDOG_MS = 2_000L
        private const val CLICK_COOLDOWN_MS = 2_000L
        private const val RECORD_INTERVAL_MS = 5 * 60_000L
        private const val THUMB_INTERVAL_MS = 10 * 60_000L
        private const val MAX_NODES = 3_000

        /** Servicio en marcha (lo enciende el usuario en Ajustes de Android), o null. */
        @Volatile
        var instance: BlockerAccessibilityService? = null
            private set

        /** true si el usuario tiene activado el servicio en Ajustes → Accesibilidad. */
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java)
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        }
    }
}

internal fun AccessibilityNodeInfo.snapshot() = NodeSnapshot(
    viewId = viewIdResourceName,
    text = text?.toString(),
    contentDescription = contentDescription?.toString(),
    className = className?.toString(),
)

/** Recorre el árbol en anchura, como mucho [limit] nodos (protege ante pantallas enormes). */
internal fun collect(root: AccessibilityNodeInfo, limit: Int): Sequence<AccessibilityNodeInfo> = sequence {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    var seen = 0
    while (queue.isNotEmpty() && seen < limit) {
        val n = queue.removeFirst()
        seen++
        yield(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
    }
}

/** Miniaturas del elemento bloqueado (Android 11+): recorte de la pantalla, JPEG al 60 %. */
internal object Thumbnails {
    private const val MAX_WIDTH = 360

    fun dir(context: Context) = File(context.cacheDir, "thumbs").apply { mkdirs() }

    fun capture(service: AccessibilityService, bounds: Rect, onDone: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) return onDone(null)
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val path = try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        result.hardwareBuffer.close()
                        val full = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        full?.let { save(service, it, bounds) }
                    } catch (e: Exception) {
                        Log.w("Thumbnails", "No se pudo guardar la miniatura", e)
                        null
                    }
                    onDone(path)
                }

                override fun onFailure(errorCode: Int) = onDone(null)
            },
        )
    }

    private fun save(context: Context, full: Bitmap, bounds: Rect): String? {
        val r = Rect(bounds)
        if (!r.intersect(0, 0, full.width, full.height) || r.isEmpty) return null
        var crop = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height())
        full.recycle()
        if (crop.width > MAX_WIDTH) {
            val scaled = Bitmap.createScaledBitmap(crop, MAX_WIDTH, crop.height * MAX_WIDTH / crop.width, true)
            crop.recycle()
            crop = scaled
        }
        val file = File(dir(context), "thumb_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, 60, it) }
        crop.recycle()
        return file.absolutePath
    }
}
