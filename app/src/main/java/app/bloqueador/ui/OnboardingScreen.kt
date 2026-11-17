package app.bloqueador.ui

import app.bloqueador.R
import androidx.compose.ui.res.stringResource
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
        0 -> OnboardingPage(
            title = stringResource(R.string.onb_welcome_title),
            button = stringResource(R.string.common_continue),
            onNext = { step = 1 },
        ) {
            Bullet(stringResource(R.string.onb_blocks_title), stringResource(R.string.onb_blocks_text))
            Bullet(stringResource(R.string.onb_vpn_title), stringResource(R.string.onb_vpn_text))
            Bullet(stringResource(R.string.onb_limits_title), stringResource(R.string.limits_text))
            Bullet(stringResource(R.string.onb_key_title), stringResource(R.string.onb_key_text))
        }

        1 -> OnboardingPage(
            title = stringResource(R.string.onb_apps_title),
            button = stringResource(R.string.common_continue),
            onNext = { step = if (strategy == AppStrategy.ALL) 3 else 2 },
        ) {
            StrategyChooser(strategy) { strategy = it }
            Text(stringResource(R.string.onb_apps_later), style = MaterialTheme.typography.bodySmall)
        }

        2 -> AppPickerScreen(
            strategy = strategy,
            initial = selection ?: emptySet(),
            preselectSensitive = strategy == AppStrategy.ALL_EXCEPT && selection == null,
            onBack = { step = 1 },
            onSave = { selection = it; step = 3 },
        )

        3 -> OnboardingPage(
            title = stringResource(R.string.onb_always_title),
            button = stringResource(R.string.common_continue),
            onNext = { step = 4 },
        ) {
            Text(stringResource(R.string.onb_always_text), style = MaterialTheme.typography.bodyMedium)
            SystemSteps()
        }

        else -> OnboardingPage(
            title = stringResource(R.string.onb_downloads_title),
            button = stringResource(R.string.onb_start),
            enabled = reports != null,
            onNext = ::finish,
        ) {
            SectionCard(stringResource(R.string.onb_downloads_section)) {
                SwitchRow(
                    title = stringResource(R.string.onb_wifi_only_title),
                    description = stringResource(R.string.onb_wifi_only_text),
                    checked = wifiOnly,
                ) { wifiOnly = it }
            }
            SectionCard(stringResource(R.string.onb_help_title)) {
                Text(stringResource(R.string.error_reports_what), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.error_reports_not), style = MaterialTheme.typography.bodySmall)
                RadioRow(
                    selected = reports == true,
                    title = stringResource(R.string.onb_reports_yes),
                    description = "",
                ) { reports = true }
                RadioRow(selected = reports == false, title = stringResource(R.string.onb_reports_no), description = "") { reports = false }
                if (reports == null) {
                    Text(stringResource(R.string.onb_choose_option), style = MaterialTheme.typography.bodySmall)
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
    val autostartSteps = remember { BatteryGuide.autostartSteps(context) }
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
            title = stringResource(R.string.onb_notifications_title),
            text = stringResource(R.string.onb_notifications_text),
            done = notifications,
            button = stringResource(R.string.common_allow),
        ) { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
    StepRow(
        title = stringResource(R.string.onb_battery_title),
        text = stringResource(R.string.onb_battery_text),
        done = battery,
        button = stringResource(R.string.common_open_settings),
    ) { BatteryGuide.openSettings(context) }
    if (autostartSteps != null) {
        StepRow(
            title = stringResource(R.string.onb_autostart_title),
            text = stringResource(R.string.onb_autostart_text, autostartSteps),
            done = autostart,
            button = stringResource(R.string.common_open_settings),
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
    SectionCard(if (done) stringResource(R.string.common_done_mark, title) else title) {
        Text(text, style = MaterialTheme.typography.bodySmall)
        if (!done) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onClick) { Text(button) }
                if (onDone != null) OutlinedButton(onClick = onDone) { Text(stringResource(R.string.common_done_it)) }
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
