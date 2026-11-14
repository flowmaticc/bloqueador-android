package app.bloqueador.ui

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bloqueador.BloqueadorApp
import app.bloqueador.filter.FilterEngine
import app.bloqueador.history.AppResolver
import app.bloqueador.history.HistoryEvent
import app.bloqueador.history.HistoryFilter
import app.bloqueador.history.RuleOrigin
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/** Historial de consultas: solo se lee cuando el usuario lo abre. */
@Composable
fun HistoryScreen(onBack: () -> Unit, startWithThreats: Boolean = false) {
    val context = LocalContext.current
    val app = BloqueadorApp.instance

    var filter by rememberSaveable { mutableStateOf(if (startWithThreats) HistoryFilter.THREATS else HistoryFilter.BLOCKED) }
    var query by rememberSaveable { mutableStateOf("") }
    var appFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<HistoryEvent?>(null) }
    var preview by remember { mutableStateOf<HistoryEvent?>(null) }

    LaunchedEffect(Unit) { app.history.cleanup() }
    val events by remember(filter) { app.history.observe(filter) }.collectAsState(initial = null)

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader("Historial", onBack)
        if (!app.settings.historyEnabled) {
            Text(
                "El historial está desactivado. Puedes activarlo en Ajustes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            app.settings.historyRetention.let { if (it.hours == 1L) "Última hora" else "Últimas ${it.label}" } +
                ". Toca una entrada para ver el detalle, " +
                "permitirla o bloquearla.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            HistoryFilter.entries.forEach { f ->
                FilterChip(selected = f == filter, onClick = { filter = f }, label = { Text(f.label) })
            }
        }
        appFilter?.let { pkg ->
            InputChip(
                selected = true,
                onClick = { appFilter = null },
                label = { Text("Solo ${appLabel(context, pkg)}  ✕") },
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Buscar dominio o app") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        val all = events
        if (all == null) {
            Box(Modifier.weight(1f)) {}
        } else {
            val q = query.trim().lowercase()
            val visible = all.filter { e ->
                (appFilter == null || e.appPackage == appFilter) &&
                    (q.isEmpty() || q in e.domain || q in appLabel(context, e.appPackage).lowercase())
            }
            if (visible.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (all.isEmpty()) "Todavía no hay nada. Activa el filtro y usa el móvil con normalidad."
                        else "No hay entradas que coincidan.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(visible, key = { it.id }) { event ->
                        HistoryRow(event) { selected = event }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    selected?.let { event ->
        HistoryDetailDialog(
            event = event,
            onDismiss = { selected = null },
            onFilterApp = {
                appFilter = event.appPackage
                selected = null
            },
            onPreview = {
                preview = event
                selected = null
            },
        )
    }
    preview?.let { WebPreviewDialog(it, onDismiss = { preview = null }) }
}

@Composable
private fun HistoryRow(event: HistoryEvent, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val pkg = event.appPackage
        if (pkg != null && pkg != AppResolver.SYSTEM_PACKAGE) AppIcon(pkg, 32) else Box(Modifier.padding(16.dp))
        Column(Modifier.weight(1f)) {
            Text(event.domain, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                appLabel(context, pkg) + " · " + relativeTime(event.timestamp) +
                    if (event.count > 1) " · ×${event.count}" else "",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            statusLabel(event),
            style = MaterialTheme.typography.labelMedium,
            color = if (event.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun HistoryDetailDialog(
    event: HistoryEvent,
    onDismiss: () -> Unit,
    onFilterApp: () -> Unit,
    onPreview: () -> Unit,
) {
    val context = LocalContext.current
    val app = BloqueadorApp.instance
    val settings = app.settings
    val scope = rememberCoroutineScope()

    val origins by produceState<List<String>?>(initialValue = null, event.matchedRule) {
        value = event.matchedRule?.let { RuleOrigin.find(it, settings, app.repository, context) } ?: emptyList()
    }
    // La regla del usuario que afecta a este dominio, si la hay, para poder deshacerla.
    val userAllow = settings.allowedDomains.firstOrNull { event.domain == it || event.domain.endsWith(".$it") }
    val userBlock = settings.blockedDomains.firstOrNull { event.domain == it || event.domain.endsWith(".$it") }

    fun apply(message: String, change: () -> Unit) {
        change()
        scope.launch {
            app.reloadEngine()
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }

    var reporting by remember { mutableStateOf(false) }
    if (reporting) {
        ReportDialog(
            domain = event.domain,
            suggested = when {
                FilterEngine.isThreat(event.matchedRule) -> ReportReason.DANGER
                event.blocked -> ReportReason.BROKEN
                else -> ReportReason.AD
            },
            onDismiss = { reporting = false },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.domain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Detail("Resultado", statusLabel(event))
                if (FilterEngine.isThreat(event.matchedRule)) {
                    Text(
                        "Está en la lista de amenazas (phishing, malware o estafas). Solo permítelo si estás " +
                            "seguro de que es de confianza.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Detail("App", appLabel(context, event.appPackage) + (event.appPackage?.let { "\n$it" } ?: ""))
                Detail(
                    "Hora",
                    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.timestamp)) +
                        if (event.count > 1) " (${event.count} consultas)" else "",
                )
                if (event.matchedRule != null) {
                    Detail("Regla", FilterEngine.plainRule(event.matchedRule))
                    Detail(
                        "Lista",
                        when {
                            origins == null -> "Buscando…"
                            origins!!.isEmpty() -> "Ya no está en ninguna lista activa"
                            else -> origins!!.joinToString(", ")
                        },
                    )
                }
                if (userAllow != null) Detail("Tu excepción", userAllow)
                if (userBlock != null) Detail("Tu bloqueo", userBlock)
                Text(
                    "Recuerda: Android guarda las respuestas unos minutos. Si una app sigue sin funcionar " +
                        "tras permitir un dominio, ciérrala y vuelve a abrirla.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                // Un sitio marcado como peligroso no se abre ni en la vista previa.
                if (!FilterEngine.isThreat(event.matchedRule)) {
                    TextButton(onClick = onPreview) { Text("Ver en la web") }
                }
                TextButton(onClick = { reporting = true }) { Text("Reportar para analizar") }
                when {
                    userAllow != null -> TextButton(onClick = {
                        apply("Excepción quitada") { settings.allowedDomains = settings.allowedDomains - userAllow }
                    }) { Text("Quitar excepción") }
                    userBlock != null -> TextButton(onClick = {
                        apply("Bloqueo quitado") { settings.blockedDomains = settings.blockedDomains - userBlock }
                    }) { Text("Quitar mi bloqueo") }
                    event.blocked -> TextButton(onClick = {
                        apply("${event.domain} permitido") { settings.allowedDomains = settings.allowedDomains + event.domain }
                    }) { Text("Permitir siempre") }
                    else -> TextButton(onClick = {
                        apply("${event.domain} bloqueado") { settings.blockedDomains = settings.blockedDomains + event.domain }
                    }) { Text("Bloquear siempre") }
                }
                if (event.appPackage != null) {
                    TextButton(onClick = onFilterApp) { Text("Ver solo esta app") }
                }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun statusLabel(e: HistoryEvent): String = when {
    FilterEngine.isThreat(e.matchedRule) -> "Peligro"
    e.blocked -> "Bloqueada"
    else -> "Permitida"
}

private val labelCache = ConcurrentHashMap<String, String>()

/** Nombre visible de una app ("Chrome"), con caché. */
fun appLabel(context: Context, packageName: String?): String {
    if (packageName == null) return "App desconocida"
    if (packageName == AppResolver.SYSTEM_PACKAGE) return "Sistema Android"
    return labelCache.getOrPut(packageName) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
