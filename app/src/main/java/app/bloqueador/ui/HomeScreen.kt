package app.bloqueador.ui

import app.bloqueador.UiText
import app.bloqueador.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import app.bloqueador.update.DownloadPolicy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.bloqueador.BloqueadorApp
import app.bloqueador.VpnState
import app.bloqueador.update.UpdateNotifier
import app.bloqueador.vpn.DnsVpnService
import app.bloqueador.vpn.LockdownWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    val app = BloqueadorApp.instance
    val state by app.vpnState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) DnsVpnService.start(context)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun startVpn() {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val prepare: Intent? = VpnService.prepare(context)
        if (prepare != null) vpnPermission.launch(prepare) else DnsVpnService.start(context)
    }

    var updating by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var lastUpdated by remember { mutableLongStateOf(app.repository.lastUpdated) }

    // Contadores: se leen una vez por segundo solo mientras la pantalla está visible.
    var queries by remember { mutableLongStateOf(0) }
    var blocked by remember { mutableLongStateOf(0) }
    var rules by remember { mutableIntStateOf(0) }
    var threatRules by remember { mutableIntStateOf(0) }
    var dnsSilent by remember { mutableStateOf(false) }
    var waitingForWifi by remember { mutableStateOf(false) }
    var confirmMobileData by remember { mutableStateOf(false) }
    // Se vuelve a leer al volver de los ajustes de Android (el usuario puede haberlo cambiado).
    var privateDns by remember { mutableStateOf(privateDnsMode(context)) }
    var privateDnsHost by remember { mutableStateOf(privateDnsHost(context)) }
    var lockdown by remember { mutableStateOf(app.settings.vpnLockdown) }
    // Solo mientras la pantalla está a la vista: en segundo plano no se despierta la CPU para nada.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                queries = app.engine.queries.get()
                blocked = app.engine.blocked.get()
                rules = app.engine.matcher.size
                threatRules = app.engine.threats.size
                dnsSilent = app.dnsUnresponsive?.invoke() == true
                waitingForWifi = !DownloadPolicy.automaticAllowed(context)
                privateDns = privateDnsMode(context)
                privateDnsHost = privateDnsHost(context)
                app.checkLockdown?.invoke()
                lockdown = app.settings.vpnLockdown
                lastUpdated = app.repository.lastUpdated // la descarga inicial la hace el servicio
                delay(1000)
            }
        }
    }

    val running = state is VpnState.Running || state == VpnState.Starting
    // Si las listas bloquean el propio DNS privado (p. ej. la capa anti-evasión bloquea dns.google),
    // Android no puede usarlo y el móvil se queda sin internet, en vez de saltarse el filtro.
    val privateDnsBlocked = privateDns == "hostname" && rules > 0 &&
        privateDnsHost?.let { app.engine.peek(it) != null } == true
    val protection = protectionOf(state, rules, dnsSilent, privateDns == "hostname", waitingForWifi, lockdown, privateDnsBlocked)

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Garbillo", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.home_settings)) }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = if (protection.warning) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            } else {
                CardDefaults.cardColors()
            },
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(protection.title.get(), style = MaterialTheme.typography.titleLarge)
                    Text(protection.detail.get(), style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = running,
                    onCheckedChange = { on ->
                        app.settings.filterWanted = on
                        if (on) startVpn() else DnsVpnService.stop(context)
                    },
                )
            }
        }

        if (lockdown && running) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_no_internet_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.home_no_internet_text),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { LockdownWarning.openVpnSettings(context) }) { Text(stringResource(R.string.home_open_vpn_settings)) }
                }
            }
        }

        if (privateDns == "hostname") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.home_private_dns_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (privateDnsBlocked) {
                            stringResource(R.string.home_private_dns_blocked_text, privateDnsHost.orEmpty())
                        } else {
                            stringResource(R.string.home_private_dns_text)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (running) {
            AutostartCard()
            BatteryGuideCard()
        }

        SectionCard(stringResource(R.string.home_since)) {
            Text(stringResource(R.string.home_queries, queries))
            Text(
                if (queries > 0) stringResource(R.string.home_blocked_pct, blocked, (blocked * 100 / queries).toInt())
                else stringResource(R.string.home_blocked, blocked)
            )
            OutlinedButton(onClick = onOpenHistory) { Text(stringResource(R.string.home_view_history)) }
        }

        SectionCard(stringResource(R.string.home_config)) {
            Text(stringResource(R.string.home_apps, strategySummary(context, app.settings)))
            Text(stringResource(R.string.home_mode, modeSummary(context, app.settings)))
            Text(
                when {
                    rules > 0 && threatRules > 0 -> stringResource(R.string.home_rules_both, rules, threatRules)
                    rules > 0 -> stringResource(R.string.home_rules, rules)
                    // Con la VPN apagada no se cargan en memoria, para no gastar RAM.
                    lastUpdated > 0 -> stringResource(R.string.home_lists_downloaded)
                    else -> stringResource(R.string.home_no_list)
                }
            )
            Text(
                if (lastUpdated > 0) {
                    stringResource(R.string.home_updated, relativeTime(context, lastUpdated))
                } else {
                    stringResource(R.string.home_never_downloaded)
                }
            )
            updateMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            fun updateNow() {
                updating = true
                updateMessage = null
                scope.launch {
                    val result = app.repository.downloadAll()
                    app.repository.recordCheck(result)
                    if (result.ok) UpdateNotifier.clear(context)
                    // Solo se cargan en memoria si el filtro está activo (ahorro de RAM).
                    if (result.changed && app.vpnState.value is VpnState.Running) app.reloadEngine()
                    lastUpdated = app.repository.lastUpdated
                    updateMessage = when {
                        !result.ok -> context.getString(R.string.home_update_error, result.errors.joinToString("; "))
                        result.changed -> context.getString(R.string.home_lists_updated)
                        else -> context.getString(R.string.home_lists_current)
                    }
                    updating = false
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !updating,
                    // Con datos móviles se pide confirmación: las listas pueden ocupar varios MB.
                    onClick = { if (DownloadPolicy.isMetered(context)) confirmMobileData = true else updateNow() },
                ) { Text(stringResource(R.string.home_update_now)) }
                if (updating) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator()
                }
            }
            if (confirmMobileData) {
                AlertDialog(
                    onDismissRequest = { confirmMobileData = false },
                    title = { Text(stringResource(R.string.home_metered_title)) },
                    text = {
                        Text(stringResource(R.string.home_metered_text))
                    },
                    confirmButton = {
                        TextButton(onClick = { confirmMobileData = false; updateNow() }) { Text(stringResource(R.string.home_download)) }
                    },
                    dismissButton = { TextButton(onClick = { confirmMobileData = false }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
        }

        if (app.settings.onboardingDone) ErrorReportsQuestionCard()
    }
}

/** Lo que se muestra en la tarjeta de estado. [warning]: el filtro está encendido pero no protege del todo. */
internal data class Protection(val title: UiText, val detail: UiText, val warning: Boolean = false) {
    constructor(title: Int, detail: Int, warning: Boolean = false) : this(UiText.res(title), UiText.res(detail), warning)
}

/**
 * Estado real de la protección: con la VPN encendida no basta para decir "Protegido"; también hacen
 * falta listas cargadas, un DNS que conteste y que Android no se salte el filtro con su DNS privado.
 */
internal fun protectionOf(
    state: VpnState,
    rules: Int,
    dnsSilent: Boolean,
    privateDnsFixed: Boolean,
    waitingForWifi: Boolean = false,
    lockdown: Boolean = false,
    privateDnsBlocked: Boolean = false,
): Protection =
    when (state) {
        VpnState.Stopped -> Protection(R.string.state_off, R.string.state_off_text)
        VpnState.Starting -> Protection(R.string.state_starting, R.string.state_preparing)
        is VpnState.Error -> Protection(UiText.res(R.string.state_error), state.message, warning = true)
        is VpnState.Running -> when {
            state.loadingList -> Protection(R.string.state_starting, R.string.state_loading)
            lockdown -> Protection(R.string.state_no_internet, R.string.state_no_internet_text, warning = true)
            privateDnsFixed && privateDnsBlocked ->
                Protection(R.string.state_no_internet, R.string.state_private_dns_blocked_text, warning = true)
            privateDnsFixed -> Protection(R.string.state_no_effect, R.string.state_no_effect_text, warning = true)
            rules == 0 && waitingForWifi -> Protection(R.string.state_waiting_wifi, R.string.state_waiting_wifi_text, warning = true)
            rules == 0 -> Protection(R.string.state_incomplete, R.string.state_incomplete_text, warning = true)
            dnsSilent -> Protection(R.string.state_dns_silent, R.string.state_dns_silent_text, warning = true)
            else -> Protection(R.string.state_protected, R.string.state_protected_text)
        }
    }

/** "off", "opportunistic" (Automático) o "hostname" (proveedor fijo: se salta el filtro). */
private fun privateDnsMode(context: Context): String? =
    if (Build.VERSION.SDK_INT >= 28) Settings.Global.getString(context.contentResolver, "private_dns_mode") else null

/** Nombre del proveedor de DNS privado fijo (p. ej. "dns.google"), en minúsculas, o null. */
private fun privateDnsHost(context: Context): String? =
    if (Build.VERSION.SDK_INT >= 28) {
        Settings.Global.getString(context.contentResolver, "private_dns_specifier")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    } else {
        null
    }
