package app.bloqueador.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.bloqueador.BloqueadorApp
import app.bloqueador.apps.AppStrategy
import app.bloqueador.apps.InstalledApps
import app.bloqueador.lists.BlockMode
import app.bloqueador.lists.ExtraLayers
import app.bloqueador.settings.AppSettings
import app.bloqueador.settings.UpdateFrequency
import app.bloqueador.settings.HistoryRetention
import androidx.compose.material3.FilterChip
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import app.bloqueador.update.ListUpdateScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangeStrategy: () -> Unit,
    onEditApps: () -> Unit,
    onOpenVisual: () -> Unit,
) {
    val context = LocalContext.current
    val app = BloqueadorApp.instance
    val settings = app.settings
    val scope = rememberCoroutineScope()

    // Copias en memoria de la configuración, para que la pantalla se repinte al cambiarla.
    var appsSummary by remember { mutableStateOf(strategySummary(settings)) }
    var strategy by remember { mutableStateOf(settings.strategy) }
    var mode by remember { mutableStateOf(settings.mode) }
    var frequency by remember { mutableStateOf(settings.updateFrequency) }
    var historyOn by remember { mutableStateOf(settings.historyEnabled) }
    var retention by remember { mutableStateOf(settings.historyRetention) }
    var layers by remember { mutableStateOf(settings.layers) }
    var allowed by remember { mutableStateOf(settings.allowedDomains) }
    var blocked by remember { mutableStateOf(settings.blockedDomains) }

    var applying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    /** Descarga lo que falte (si [download]) y recarga el motor. Un cambio nuevo cancela el anterior. */
    fun applyLists(download: Boolean) {
        job?.cancel()
        job = scope.launch {
            applying = true
            error = null
            try {
                if (download) {
                    try {
                        app.repository.downloadMissing()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = "No se pudo descargar: ${e.message}"
                    }
                }
                app.reloadEngine()
            } finally {
                // Si nos canceló un cambio posterior, ese ya ha puesto su propio "aplicando".
                if (isActive) applying = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Ajustes", onBack)

        if (applying) {
            Column {
                Text("Aplicando cambios…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        SectionCard("Apps afectadas") {
            Text(appsSummary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onChangeStrategy) { Text("Cambiar estrategia") }
                if (strategy != AppStrategy.ALL) {
                    OutlinedButton(onClick = onEditApps) { Text("Editar lista") }
                }
            }
            TextButton(onClick = {
                scope.launch {
                    // Recomendado: todo filtrado salvo las apps de banca y pagos instaladas.
                    val sensitive = InstalledApps.load(context).filter { it.sensitive }.map { it.packageName }.toSet()
                    val recommended = if (sensitive.isEmpty()) AppStrategy.ALL else AppStrategy.ALL_EXCEPT
                    applyAppSelection(context, recommended, sensitive.takeIf { it.isNotEmpty() })
                    strategy = settings.strategy
                    appsSummary = strategySummary(settings)
                }
            }) { Text("Restaurar recomendado (todas menos banca y pagos)") }
        }

        SectionCard("Modo de bloqueo") {
            BlockMode.entries.forEach { m ->
                RadioRow(
                    selected = m == mode,
                    title = "${m.label} · ${m.source.label}",
                    description = m.description,
                    onClick = {
                        if (m != mode) {
                            mode = m
                            settings.mode = m
                            applyLists(download = true)
                        }
                    },
                )
            }
        }

        AppUpdateSection()

        SectionCard("Bloqueo visual (opcional)") {
            Text(
                "Tapa o pulsa elementos concretos dentro de una app (banners, botones \"Saltar\"). " +
                    "Usa el servicio de accesibilidad y solo actúa donde tú marques.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onOpenVisual) { Text("Configurar") }
        }

        SectionCard("Historial") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Guardar historial")
                    Text(
                        "Solo en este móvil. Se borra solo pasado el tiempo elegido.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = historyOn,
                    onCheckedChange = { on ->
                        historyOn = on
                        settings.historyEnabled = on
                        if (!on) scope.launch { app.history.clear() }
                    },
                )
            }
            if (historyOn) {
                Text("Conservar durante:", style = MaterialTheme.typography.bodyMedium)
                // Con desplazamiento horizontal: con letra grande no caben las cuatro opciones.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    HistoryRetention.entries.forEach { r ->
                        FilterChip(
                            selected = r == retention,
                            onClick = {
                                retention = r
                                settings.historyRetention = r
                                scope.launch { app.history.cleanup() }
                            },
                            label = { Text(r.label) },
                        )
                    }
                }
            }
            OutlinedButton(onClick = {
                scope.launch {
                    app.history.clear()
                    Toast.makeText(context, "Historial borrado", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Borrar historial ahora") }
        }

        SectionCard("Actualización de listas") {
            UpdateFrequency.entries.forEach { f ->
                RadioRow(
                    selected = f == frequency,
                    title = f.label,
                    description = f.description,
                    onClick = {
                        if (f != frequency) {
                            frequency = f
                            settings.updateFrequency = f
                            ListUpdateScheduler.schedule(context, f, replace = true)
                        }
                    },
                )
            }
            val repo = app.repository
            Text(
                if (repo.lastCheck > 0) {
                    "Última comprobación: " + relativeTime(repo.lastCheck)
                } else {
                    "Aún no se ha hecho ninguna comprobación automática."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (repo.consecutiveFailures > 0) {
                Text(
                    "Fallos seguidos: ${repo.consecutiveFailures}. ${repo.lastError.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Solo se descarga algo si la lista ha cambiado, y nunca con la batería baja.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("Capas adicionales") {
            Text(
                "Listas que se suman a la del modo elegido. Cuantas más actives, más se bloquea y más " +
                    "memoria usa la app.",
                style = MaterialTheme.typography.bodySmall,
            )
            ExtraLayers.all.forEach { layer ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(layer.label)
                        Text(layer.description, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = layer.id in layers,
                        onCheckedChange = { on ->
                            layers = if (on) layers + layer.id else layers - layer.id
                            settings.layers = layers
                            applyLists(download = on)
                        },
                    )
                }
            }
        }

        DomainListCard(
            title = "Exclusiones (permitir siempre)",
            help = "Dominios que nunca se bloquean, aunque estén en una lista. Incluye sus subdominios.",
            domains = allowed,
            onChange = {
                allowed = it
                settings.allowedDomains = it
                applyLists(download = false)
            },
        )

        DomainListCard(
            title = "Bloqueos propios",
            help = "Dominios que quieres bloquear siempre. Incluye sus subdominios.",
            domains = blocked,
            onChange = {
                blocked = it
                settings.blockedDomains = it
                applyLists(download = false)
            },
        )
    }
}

@Composable
private fun DomainListCard(
    title: String,
    help: String,
    domains: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    SectionCard(title) {
        Text(help, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    invalid = false
                },
                label = { Text("ejemplo.com") },
                singleLine = true,
                isError = invalid,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                val domain = AppSettings.cleanDomain(input)
                if (domain == null) {
                    invalid = true
                } else {
                    input = ""
                    if (domain !in domains) onChange(domains + domain)
                }
            }) { Text("Añadir") }
        }
        if (invalid) Text("No es un dominio válido.", color = MaterialTheme.colorScheme.error)
        if (domains.isEmpty()) {
            Text("Ninguno todavía.", style = MaterialTheme.typography.bodySmall)
        }
        domains.sorted().forEach { domain ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(domain, Modifier.weight(1f))
                TextButton(onClick = { onChange(domains - domain) }) { Text("Quitar") }
            }
        }
    }
}
