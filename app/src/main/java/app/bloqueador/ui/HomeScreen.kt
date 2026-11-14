package app.bloqueador.ui

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
import androidx.compose.material3.Button
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
    // Se vuelve a leer al volver de los ajustes de Android (el usuario puede haberlo cambiado).
    var privateDns by remember { mutableStateOf(privateDnsMode(context)) }
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
                privateDns = privateDnsMode(context)
                lastUpdated = app.repository.lastUpdated // la descarga inicial la hace el servicio
                delay(1000)
            }
        }
    }

    val running = state is VpnState.Running || state == VpnState.Starting
    val protection = protectionOf(state, rules, dnsSilent, privateDns == "hostname")

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
            OutlinedButton(onClick = onOpenSettings) { Text("Ajustes") }
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
                    Text(protection.title, style = MaterialTheme.typography.titleLarge)
                    Text(protection.detail, style = MaterialTheme.typography.bodyMedium)
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

        if (privateDns == "hostname") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("El DNS privado está activado", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Con un proveedor de DNS privado fijo, Android se salta el filtro. " +
                            "Ve a Ajustes → Redes → DNS privado y elige \"Automático\" o \"Desactivado\".",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (running) BatteryGuideCard()

        SectionCard("Desde que se activó") {
            Text("Consultas DNS: $queries")
            Text("Bloqueadas: $blocked" + if (queries > 0) " (${blocked * 100 / queries} %)" else "")
            OutlinedButton(onClick = onOpenHistory) { Text("Ver historial") }
        }

        SectionCard("Configuración") {
            Text("Apps: ${strategySummary(app.settings)}")
            Text("Modo: ${modeSummary(app.settings)}")
            Text(
                when {
                    rules > 0 && threatRules > 0 ->
                        "%,d reglas de publicidad y %,d de amenazas".format(rules, threatRules)
                    rules > 0 -> "%,d reglas cargadas".format(rules)
                    // Con la VPN apagada no se cargan en memoria, para no gastar RAM.
                    lastUpdated > 0 -> "Listas descargadas (se cargan al activar)"
                    else -> "Sin lista descargada"
                }
            )
            Text(
                if (lastUpdated > 0) {
                    "Actualizada " + relativeTime(lastUpdated)
                } else {
                    "Nunca descargada"
                }
            )
            updateMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !updating,
                    onClick = {
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
                                !result.ok -> "Error al actualizar: ${result.errors.joinToString("; ")}"
                                result.changed -> "Listas actualizadas"
                                else -> "Las listas ya estaban al día"
                            }
                            updating = false
                        }
                    },
                ) { Text("Actualizar ahora") }
                if (updating) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/** Lo que se muestra en la tarjeta de estado. [warning]: el filtro está encendido pero no protege del todo. */
internal data class Protection(val title: String, val detail: String, val warning: Boolean = false)

/**
 * Estado real de la protección: con la VPN encendida no basta para decir "Protegido"; también hacen
 * falta listas cargadas, un DNS que conteste y que Android no se salte el filtro con su DNS privado.
 */
internal fun protectionOf(state: VpnState, rules: Int, dnsSilent: Boolean, privateDnsFixed: Boolean): Protection =
    when (state) {
        VpnState.Stopped -> Protection("Desactivado", "La publicidad no se está filtrando")
        VpnState.Starting -> Protection("Activando…", "Preparando el filtro")
        is VpnState.Error -> Protection("Error", state.message, warning = true)
        is VpnState.Running -> when {
            state.loadingList -> Protection("Activando…", "Cargando la lista de bloqueo…")
            privateDnsFixed -> Protection("Sin efecto", "El DNS privado de Android se salta el filtro (mira el aviso de abajo)", warning = true)
            rules == 0 -> Protection(
                "Protección incompleta",
                "No hay ninguna lista cargada. Comprueba la conexión y pulsa \"Actualizar ahora\".",
                warning = true,
            )
            dnsSilent -> Protection(
                "Sin respuesta del DNS",
                "El filtro está activo, pero el DNS de la red no contesta. ¿Hay conexión a Internet?",
                warning = true,
            )
            else -> Protection("Protegido", "Filtrando publicidad y rastreadores")
        }
    }

/** "off", "opportunistic" (Automático) o "hostname" (proveedor fijo: se salta el filtro). */
private fun privateDnsMode(context: Context): String? =
    if (Build.VERSION.SDK_INT >= 28) Settings.Global.getString(context.contentResolver, "private_dns_mode") else null
