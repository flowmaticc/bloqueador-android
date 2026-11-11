package app.bloqueador.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.bloqueador.apps.AppEntry
import app.bloqueador.apps.AppStrategy
import app.bloqueador.apps.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Selector de apps para las estrategias "todas excepto" y "solo las que elija".
 *
 * @param preselectSensitive si es true y no hay selección previa, se marcan las apps de banca y
 *   pagos (solo tiene sentido en "todas excepto": son las que se recomienda no filtrar).
 */
@Composable
fun AppPickerScreen(
    strategy: AppStrategy,
    initial: Set<String>,
    preselectSensitive: Boolean,
    onBack: (() -> Unit)?,
    onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var selection by rememberSaveable { mutableStateOf(initial.toList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var preselected by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loaded = InstalledApps.load(context)
        if (preselectSensitive && !preselected && selection.isEmpty()) {
            selection = loaded.filter { it.sensitive }.map { it.packageName }
            preselected = true
        }
        apps = loaded
    }

    val selected = selection.toSet()
    val excluding = strategy == AppStrategy.ALL_EXCEPT

    fun toggle(pkg: String) {
        selection = if (pkg in selected) selection - pkg else selection + pkg
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader(if (excluding) "Apps que NO se filtran" else "Apps que se filtran", onBack)
        Text(
            if (excluding) "Marca las apps que quieres dejar fuera del filtro. El resto se filtra."
            else "Marca las apps que quieres filtrar. El resto no se toca.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Buscar app") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text("Mostrar apps del sistema", Modifier.weight(1f))
            Switch(checked = showSystem, onCheckedChange = { showSystem = it })
        }

        val all = apps
        if (all == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val q = query.trim().lowercase()
            val visible = all.filter { app ->
                (showSystem || !app.hiddenSystem || app.packageName in selected) &&
                    (q.isEmpty() || q in app.label.lowercase() || q in app.packageName.lowercase())
            }
            val sensitive = visible.filter { it.sensitive }
            val others = visible.filter { !it.sensitive }

            LazyColumn(Modifier.weight(1f)) {
                if (sensitive.isNotEmpty()) {
                    item(key = "h-sensitive") {
                        SectionLabel(
                            "Banca y pagos",
                            if (excluding) "Recomendado excluirlas: algunas no funcionan con una VPN activa."
                            else "No se recomienda filtrarlas: algunas no funcionan con una VPN activa.",
                        )
                    }
                    items(sensitive, key = { it.packageName }) { app ->
                        AppRow(app, app.packageName in selected) { toggle(app.packageName) }
                    }
                }
                if (others.isNotEmpty()) {
                    item(key = "h-others") { SectionLabel("Otras apps", null) }
                    items(others, key = { it.packageName }) { app ->
                        AppRow(app, app.packageName in selected) { toggle(app.packageName) }
                    }
                }
                if (visible.isEmpty()) {
                    item { Text("No hay apps que coincidan.", Modifier.padding(16.dp)) }
                }
            }
        }

        val canSave = excluding || selected.isNotEmpty()
        if (!canSave) {
            Text("Marca al menos una app.", style = MaterialTheme.typography.bodySmall)
        }
        Button(
            enabled = canSave && apps != null,
            onClick = { onSave(selected) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) { Text("Guardar (${selected.size} marcadas)") }
    }
}

@Composable
private fun SectionLabel(title: String, note: String?) {
    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app.packageName)
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
internal fun AppIcon(packageName: String, size: Int = 40) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size.dp))
    } else {
        Spacer(Modifier.width(size.dp))
    }
}
