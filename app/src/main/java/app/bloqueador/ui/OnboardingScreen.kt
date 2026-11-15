package app.bloqueador.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.bloqueador.BloqueadorApp
import app.bloqueador.apps.AppStrategy
import app.bloqueador.update.DownloadPolicy

/**
 * Primer arranque: qué es → qué apps proteger → (selector de apps) → que funcione siempre (permisos y
 * ajustes del sistema) → descargas y ayuda (solo con wifi e informes de errores, que hay que elegir).
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val settings = BloqueadorApp.instance.settings
    var step by rememberSaveable { mutableIntStateOf(0) }
    var strategy by rememberSaveable { mutableStateOf(AppStrategy.ALL) }
    var selection by remember { mutableStateOf<Set<String>?>(null) }
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var reports by rememberSaveable { mutableStateOf<Boolean?>(null) }

    fun finish() {
        selection?.let {
            if (strategy == AppStrategy.ALL_EXCEPT) settings.excludedApps = it
            if (strategy == AppStrategy.ONLY) settings.includedApps = it
        }
        settings.strategy = strategy
        settings.downloadsWifiOnly = wifiOnly
        chooseErrorReports(context, reports == true)
        DownloadPolicy.reschedule(context)
        settings.onboardingDone = true
        onDone()
    }

    BackHandler(enabled = step > 0) { step = if (step == 3 && strategy == AppStrategy.ALL) 1 else step - 1 }

    when (step) {
        0 -> OnboardingPage(title = "Bienvenido a Garbillo", button = "Continuar", onNext = { step = 1 }) {
            Bullet(
                "Bloquea publicidad, rastreadores y webs peligrosas",
                "En todo el móvil: en el navegador y dentro de las apps, sin root.",
            )
            Bullet(
                "Usa una VPN local, no un servidor externo",
                "La \"VPN\" vive dentro del móvil y solo mira a qué dominio quiere conectarse cada app. No ve lo " +
                    "que haces y tu navegación no sale del móvil.",
            )
            Bullet("Lo que no puede quitar", LIMITS_TEXT)
            Bullet(
                "Verás una llave y una notificación fija",
                "Android las muestra siempre que hay una VPN activa. Es normal y no gasta batería. La primera vez " +
                    "que actives el filtro, Android te pedirá permiso (\"Solicitud de conexión\").",
            )
        }

        1 -> OnboardingPage(
            title = "¿Qué apps quieres proteger?",
            button = "Continuar",
            onNext = { step = if (strategy == AppStrategy.ALL) 3 else 2 },
        ) {
            StrategyChooser(strategy) { strategy = it }
            Text(
                "Podrás cambiarlo cuando quieras en Ajustes → Apps protegidas.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        2 -> AppPickerScreen(
            strategy = strategy,
            initial = selection ?: emptySet(),
            preselectSensitive = strategy == AppStrategy.ALL_EXCEPT && selection == null,
            onBack = { step = 1 },
            onSave = { selection = it; step = 3 },
        )

        3 -> OnboardingPage(title = "Que funcione siempre", button = "Continuar", onNext = { step = 4 }) {
            Text(
                "Algunos ajustes del sistema pueden apagar el filtro sin avisar. Cada paso es opcional y puedes " +
                    "hacerlo más tarde desde Ajustes → Batería y arranque.",
                style = MaterialTheme.typography.bodyMedium,
            )
            SystemSteps()
        }

        else -> OnboardingPage(
            title = "Descargas y ayuda",
            button = "Empezar",
            enabled = reports != null,
            onNext = ::finish,
        ) {
            SectionCard("Descargas") {
                SwitchRow(
                    title = "Descargar solo con wifi (recomendado)",
                    description = "Las listas (entre 5 y 40 MB) y las comprobaciones se hacen solas al conectarte a " +
                        "una wifi. Lo que pidas a mano se descarga siempre.",
                    checked = wifiOnly,
                ) { wifiOnly = it }
            }
            SectionCard("¿Ayudas a mejorar Garbillo?") {
                Text(ERROR_REPORTS_WHAT, style = MaterialTheme.typography.bodyMedium)
                Text(ERROR_REPORTS_NOT, style = MaterialTheme.typography.bodySmall)
                RadioRow(
                    selected = reports == true,
                    title = "Sí, enviar informes de errores anónimos",
                    description = "",
                ) { reports = true }
                RadioRow(selected = reports == false, title = "No, gracias", description = "") { reports = false }
                if (reports == null) {
                    Text("Elige una opción para empezar.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Pasos del sistema con su estado: notificaciones, batería e inicio automático (si el fabricante lo pide). */
@Composable
private fun SystemSteps() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var notifications by remember { mutableStateOf(notificationsAllowed(context)) }
    var battery by remember { mutableStateOf(!BatteryGuide.isOptimized(context)) }
    var autostart by remember { mutableStateOf(prefs.getBoolean("autostart_guide_dismissed", false)) }
    val autostartSteps = remember { BatteryGuide.autostartSteps() }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = it
    }

    // Al volver de los ajustes del sistema se comprueba qué ha cambiado.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            notifications = notificationsAllowed(context)
            battery = !BatteryGuide.isOptimized(context)
        }
    }

    if (Build.VERSION.SDK_INT >= 33) {
        StepRow(
            title = "Notificaciones",
            text = "Para avisarte de webs peligrosas bloqueadas y de versiones nuevas.",
            done = notifications,
            button = "Permitir",
        ) { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
    StepRow(
        title = "Sin restricciones de batería",
        text = "Que el ahorro de energía no cierre el filtro. Garbillo apenas gasta: duerme hasta que llega una " +
            "consulta. Busca Garbillo y elige \"No optimizar\" o \"Sin restricciones\".",
        done = battery,
        button = "Abrir ajustes",
    ) { BatteryGuide.openSettings(context) }
    if (autostartSteps != null) {
        StepRow(
            title = "Inicio automático",
            text = "En este móvil hace falta para que el filtro vuelva solo al encenderlo o tras actualizar. $autostartSteps",
            done = autostart,
            button = "Abrir ajustes",
            onDone = {
                autostart = true
                prefs.edit().putBoolean("autostart_guide_dismissed", true).apply()
            },
        ) { BatteryGuide.openAutostart(context) }
    }
}

/** Un paso con su estado: ✓ si ya está hecho; si no, un botón (y "Ya lo hice" si no se puede comprobar). */
@Composable
private fun StepRow(
    title: String,
    text: String,
    done: Boolean,
    button: String,
    onDone: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    SectionCard(if (done) "✓ $title" else title) {
        Text(text, style = MaterialTheme.typography.bodySmall)
        if (!done) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onClick) { Text(button) }
                if (onDone != null) OutlinedButton(onClick = onDone) { Text("Ya lo hice") }
            }
        }
    }
}

private fun notificationsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun OnboardingPage(
    title: String,
    button: String,
    onNext: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.padding(top = 8.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            content()
        }
        Button(onClick = onNext, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(button) }
    }
}

@Composable
private fun Bullet(title: String, text: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Límites de un bloqueador por DNS, dichos con claridad. */
internal const val LIMITS_TEXT =
    "Los anuncios de YouTube y Twitch y las publicaciones promocionadas de redes sociales llegan por " +
        "los mismos servidores que el contenido, así que ningún bloqueador de este tipo puede quitarlos. " +
        "Dentro de las webs puede quedar el hueco del anuncio: para eso, usa Brave o Firefox con " +
        "uBlock Origin; se complementan con Garbillo."
