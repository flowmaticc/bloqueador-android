package app.bloqueador.ui

import app.bloqueador.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.bloqueador.settings.AppSettings
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
        ScreenHeader(stringResource(R.string.hist_title), onBack)
        if (!app.settings.historyEnabled) {
            Text(
                stringResource(R.string.hist_disabled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            app.settings.historyRetention.hours.toInt().let { pluralStringResource(R.plurals.hist_last_hours, it, it) },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            HistoryFilter.entries.forEach { f ->
                FilterChip(selected = f == filter, onClick = { filter = f }, label = { Text(f.label.get()) })
            }
        }
        appFilter?.let { pkg ->
            InputChip(
                selected = true,
                onClick = { appFilter = null },
                label = { Text(stringResource(R.string.hist_only_app, appLabel(context, pkg))) },
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.hist_search)) },
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
                        stringResource(if (all.isEmpty()) R.string.hist_empty else R.string.hist_no_match),
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
                if (event.count > 1) {
                    stringResource(R.string.hist_row_count, appLabel(context, pkg), relativeTime(context, event.timestamp), event.count)
                } else {
                    stringResource(R.string.hist_row, appLabel(context, pkg), relativeTime(context, event.timestamp))
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            stringResource(statusLabel(event)),
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
    var confirmThreat by remember { mutableStateOf(false) }
    if (confirmThreat) {
        AlertDialog(
            onDismissRequest = { confirmThreat = false },
            title = { Text(stringResource(R.string.hist_allow_threat_title)) },
            text = {
                Text(
                    stringResource(R.string.hist_allow_threat_text, event.domain)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmThreat = false
                    apply(context.getString(R.string.hist_allowed_toast, event.domain)) { settings.allowedDomains = settings.allowedDomains + listOfNotNull(AppSettings.cleanDomain(event.domain)) }
                }) { Text(stringResource(R.string.hist_allow_anyway)) }
            },
            dismissButton = { TextButton(onClick = { confirmThreat = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
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
                Detail(stringResource(R.string.hist_result), stringResource(statusLabel(event)))
                if (FilterEngine.isThreat(event.matchedRule)) {
                    Text(
                        stringResource(R.string.hist_threat_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Detail(stringResource(R.string.hist_app), appLabel(context, event.appPackage) + (event.appPackage?.let { "\n$it" } ?: ""))
                Detail(
                    stringResource(R.string.hist_time),
                    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.timestamp)) +
                        if (event.count > 1) " (" + pluralStringResource(R.plurals.hist_queries, event.count, event.count) + ")" else "",
                )
                if (event.matchedRule != null) {
                    Detail(stringResource(R.string.hist_rule), FilterEngine.plainRule(event.matchedRule))
                    Detail(
                        stringResource(R.string.hist_list),
                        when {
                            origins == null -> stringResource(R.string.hist_searching)
                            origins!!.isEmpty() -> stringResource(R.string.hist_no_list)
                            else -> origins!!.joinToString(", ")
                        },
                    )
                }
                if (userAllow != null) Detail(stringResource(R.string.hist_your_exception), userAllow)
                if (userBlock != null) Detail(stringResource(R.string.hist_your_block), userBlock)
                Text(
                    stringResource(R.string.hist_cache_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                // Un sitio marcado como peligroso no se abre ni en la vista previa.
                if (!FilterEngine.isThreat(event.matchedRule)) {
                    TextButton(onClick = onPreview) { Text(stringResource(R.string.hist_view_web)) }
                }
                TextButton(onClick = { reporting = true }) { Text(stringResource(R.string.report_title)) }
                when {
                    userAllow != null -> TextButton(onClick = {
                        apply(context.getString(R.string.hist_exception_removed)) { settings.allowedDomains = settings.allowedDomains - userAllow }
                    }) { Text(stringResource(R.string.hist_remove_exception)) }
                    userBlock != null -> TextButton(onClick = {
                        apply(context.getString(R.string.hist_block_removed)) { settings.blockedDomains = settings.blockedDomains - userBlock }
                    }) { Text(stringResource(R.string.hist_remove_block)) }
                    event.blocked && FilterEngine.isThreat(event.matchedRule) ->
                        TextButton(onClick = { confirmThreat = true }) { Text(stringResource(R.string.hist_allow_always_dots)) }
                    event.blocked -> TextButton(onClick = {
                        apply(context.getString(R.string.hist_allowed_toast, event.domain)) { settings.allowedDomains = settings.allowedDomains + listOfNotNull(AppSettings.cleanDomain(event.domain)) }
                    }) { Text(stringResource(R.string.hist_allow_always)) }
                    else -> TextButton(onClick = {
                        apply(context.getString(R.string.hist_blocked_toast, event.domain)) { settings.blockedDomains = settings.blockedDomains + listOfNotNull(AppSettings.cleanDomain(event.domain)) }
                    }) { Text(stringResource(R.string.hist_block_always)) }
                }
                if (event.appPackage != null) {
                    TextButton(onClick = onFilterApp) { Text(stringResource(R.string.hist_only_this_app)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
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

private fun statusLabel(e: HistoryEvent): Int = when {
    FilterEngine.isThreat(e.matchedRule) -> R.string.status_danger
    e.blocked -> R.string.status_blocked
    else -> R.string.status_allowed
}

private val labelCache = ConcurrentHashMap<String, String>()

/** Nombre visible de una app ("Chrome"), con caché. */
fun appLabel(context: Context, packageName: String?): String {
    if (packageName == null) return context.getString(R.string.app_unknown)
    if (packageName == AppResolver.SYSTEM_PACKAGE) return context.getString(R.string.app_system)
    return labelCache.getOrPut(packageName) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
