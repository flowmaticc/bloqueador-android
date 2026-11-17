package app.bloqueador.ui

import app.bloqueador.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.bloqueador.BloqueadorApp
import app.bloqueador.VpnState
import app.bloqueador.apps.AppStrategy
import app.bloqueador.settings.AppSettings
import app.bloqueador.vpn.DnsVpnService

/** Cabecera de pantalla secundaria: botón de volver + título. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Fila con botón de radio, título y descripción; toda la fila es pulsable. */
@Composable
fun RadioRow(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun StrategyChooser(selected: AppStrategy, onSelect: (AppStrategy) -> Unit) {
    Column {
        AppStrategy.entries.forEach { strategy ->
            RadioRow(
                selected = strategy == selected,
                title = if (strategy == AppStrategy.ALL) stringResource(R.string.common_recommended, strategy.label.get())
                else strategy.label.get(),
                description = strategy.description.get(),
                onClick = { onSelect(strategy) },
            )
        }
    }
}

/** Resumen legible de la selección de apps actual. */
fun strategySummary(context: Context, settings: AppSettings): String {
    val res = context.resources
    return when (settings.strategy) {
        AppStrategy.ALL -> context.getString(R.string.strategy_all)
        AppStrategy.ALL_EXCEPT -> when (val n = settings.excludedApps.size) {
            0 -> context.getString(R.string.strategy_all)
            else -> res.getQuantityString(R.plurals.apps_all_except, n, n)
        }
        AppStrategy.ONLY -> settings.includedApps.size.let { res.getQuantityString(R.plurals.apps_only, it, it) }
    }
}

/** "hace un momento", "hace 5 minutos", "hace 3 horas"… (en minúscula, para ir tras otra palabra). */
fun relativeTime(context: Context, timeMs: Long): String =
    if (System.currentTimeMillis() - timeMs < 60_000) {
        context.getString(R.string.time_just_now)
    } else {
        android.text.format.DateUtils.getRelativeTimeSpanString(timeMs).toString().lowercase()
    }

fun modeSummary(context: Context, settings: AppSettings): String =
    if (settings.isCustom) context.getString(R.string.mode_custom, settings.mode.label.get(context))
    else settings.mode.label.get(context)

/**
 * Guarda la selección de apps y, si la VPN está activa, recrea el túnel para aplicarla.
 * [selection] es la lista de apps de la estrategia (null para "todas").
 */
fun applyAppSelection(context: Context, strategy: AppStrategy, selection: Set<String>?) {
    val app = BloqueadorApp.instance
    val settings = app.settings
    if (selection != null) {
        when (strategy) {
            AppStrategy.ALL_EXCEPT -> settings.excludedApps = selection
            AppStrategy.ONLY -> settings.includedApps = selection
            AppStrategy.ALL -> Unit
        }
    }
    settings.strategy = strategy
    if (app.vpnState.value is VpnState.Running) DnsVpnService.reloadApps(context)
    Toast.makeText(context, context.getString(R.string.settings_applied), Toast.LENGTH_SHORT).show()
}
