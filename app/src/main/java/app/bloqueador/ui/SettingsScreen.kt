package app.bloqueador.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.bloqueador.BloqueadorApp
import app.bloqueador.BuildConfig
import app.bloqueador.VpnState
import app.bloqueador.apps.AppStrategy
import app.bloqueador.apps.InstalledApps
import app.bloqueador.lists.BlockMode
import app.bloqueador.lists.ExtraLayers
import app.bloqueador.lists.ThreatLevel
import app.bloqueador.settings.AppSettings
import app.bloqueador.settings.HistoryRetention
import app.bloqueador.settings.UpdateFrequency
import app.bloqueador.update.ListUpdateScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.Normalizer

/**
 * Categorías de Ajustes. La pantalla principal solo muestra estas filas (con un resumen del valor
 * actual); cada una abre su propia pantalla. Así la lista no crece aunque se añadan ajustes.
 */
enum class SettingsSection(val title: String, val group: String) {
    MODE("Nivel de bloqueo", "Protección"),
    SECURITY("Protección contra amenazas", "Protección"),
    LAYERS("Listas adicionales", "Protección"),
    APPS("Apps protegidas", "Protección"),
    RULES("Mis excepciones y bloqueos", "Protección"),
    LIST_UPDATES("Actualización de listas", "Funcionamiento"),
    HISTORY("Historial", "Funcionamiento"),
    BATTERY("Batería y arranque", "Funcionamiento"),
    APP_UPDATES("Actualizaciones de la app", "Aplicación"),
    HELP("Qué puede y qué no puede bloquear", "Aplicación"),
    ABOUT("Acerca de", "Aplicación"),
}

/** Un ajuste concreto para el buscador: dónde está y palabras con las que se puede encontrar. */
data class SettingEntry(val section: SettingsSection, val title: String, val keywords: String)

/** Índice del buscador. Al añadir un ajuste nuevo, añadirlo aquí también. */
fun settingsIndex(manufacturer: String?): List<SettingEntry> = buildList {
    add(SettingEntry(SettingsSection.MODE, "Nivel de bloqueo", "modo ligero equilibrado estricto agresividad hagezi pro lista base"))
    add(SettingEntry(SettingsSection.SECURITY, "Protección contra amenazas", "seguridad phishing malware virus estafas fraude timo webs falsas criptominado peligroso ciberseguridad"))
    add(SettingEntry(SettingsSection.SECURITY, "Avisos de sitios peligrosos", "notificacion alerta amenaza bloqueada"))
    add(SettingEntry(SettingsSection.LAYERS, "Listas adicionales", "capas extra mas bloqueo"))
    ExtraLayers.available(manufacturer).forEach {
        add(SettingEntry(SettingsSection.LAYERS, it.label, it.description + " " + it.id))
    }
    add(SettingEntry(SettingsSection.APPS, "Apps protegidas", "estrategia excluir excluidas banca pagos todas solo elegir apps"))
    add(SettingEntry(SettingsSection.APPS, "Restaurar recomendado", "banca pagos por defecto"))
    add(SettingEntry(SettingsSection.RULES, "Permitir siempre un dominio", "excepciones exclusiones lista blanca whitelist desbloquear"))
    add(SettingEntry(SettingsSection.RULES, "Bloquear siempre un dominio", "bloqueos propios lista negra blacklist"))
    add(SettingEntry(SettingsSection.LIST_UPDATES, "Frecuencia de actualización de listas", "12 24 horas manual automatica descargar"))
    add(SettingEntry(SettingsSection.LIST_UPDATES, "Comprobar listas ahora", "actualizar descargar ya"))
    add(SettingEntry(SettingsSection.HISTORY, "Guardar historial", "registro consultas activar desactivar"))
    add(SettingEntry(SettingsSection.HISTORY, "Tiempo que se conserva el historial", "conservar retencion 1 3 6 24 horas"))
    add(SettingEntry(SettingsSection.HISTORY, "Borrar historial", "eliminar limpiar"))
    add(SettingEntry(SettingsSection.BATTERY, "Que Android no cierre el filtro", "bateria ahorro optimizacion sin restricciones se apaga"))
    add(SettingEntry(SettingsSection.BATTERY, "VPN siempre activa", "arranque inicio encender reiniciar automatico bloquear conexiones sin vpn"))
    add(SettingEntry(SettingsSection.ABOUT, "Web y política de privacidad", "pagina privacidad datos permisos ayuda"))
    add(SettingEntry(SettingsSection.APP_UPDATES, "Canal de actualizaciones", "estable beta nightly version actualizar app instalar"))
    add(SettingEntry(SettingsSection.HELP, "Qué no puede bloquear", "youtube twitch instagram tiktok x twitter spotify redes sociales hueco brave firefox ublock"))
    add(SettingEntry(SettingsSection.ABOUT, "Acerca de", "version licencia codigo fuente github privacidad"))
}

/** Busca sin distinguir mayúsculas ni tildes; todas las palabras de [query] deben aparecer. */
fun searchSettings(index: List<SettingEntry>, query: String): List<SettingEntry> {
    val words = normalize(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    return index.filter { e ->
        val text = normalize("${e.title} ${e.section.title} ${e.keywords}")
        words.all { it in text }
    }
}

private fun normalize(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

@Composable
fun SettingsScreen(
    section: SettingsSection?,
    onOpenSection: (SettingsSection?) -> Unit,
    onBack: () -> Unit,
    onChangeStrategy: () -> Unit,
    onEditApps: () -> Unit,
) {
    // La categoría abierta la guarda quien llama: así, al volver de elegir apps, sigue abierta.
    BackHandler(enabled = section != null) { onOpenSection(null) }

    if (section == null) {
        SettingsHome(onBack = onBack, onOpen = onOpenSection)
    } else {
        SectionScreen(section, onBack = { onOpenSection(null) }, onChangeStrategy, onEditApps)
    }
}

/** Pantalla principal de Ajustes: buscador + categorías agrupadas. */
@Composable
private fun SettingsHome(onBack: () -> Unit, onOpen: (SettingsSection) -> Unit) {
    val settings = BloqueadorApp.instance.settings
    var query by rememberSaveable { mutableStateOf("") }
    val index = remember { settingsIndex(android.os.Build.MANUFACTURER) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Ajustes", onBack)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Buscar en ajustes") },
            singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("✕") } },
            modifier = Modifier.fillMaxWidth(),
        )

        if (query.isNotBlank()) {
            val results = searchSettings(index, query)
            if (results.isEmpty()) {
                Text("No hay ningún ajuste que coincida.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Card(Modifier.fillMaxWidth()) {
                    results.forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider()
                        NavRow(e.title, e.section.title) { onOpen(e.section) }
                    }
                }
            }
            return@Column
        }

        SettingsSection.entries.groupBy { it.group }.forEach { (group, sections) ->
            Text(
                group,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Card(Modifier.fillMaxWidth()) {
                sections.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider()
                    NavRow(s.title, sectionSummary(s, settings)) { onOpen(s) }
                }
            }
        }
    }
}

/** Fila de navegación: título, resumen y flecha. */
@Composable
private fun NavRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Text("›", style = MaterialTheme.typography.titleLarge)
    }
}

/** Resumen del valor actual que se ve bajo cada categoría. */
private fun sectionSummary(s: SettingsSection, settings: AppSettings): String {
    val app = BloqueadorApp.instance
    return when (s) {
        SettingsSection.MODE -> "${settings.mode.label} · ${settings.mode.source.label}"
        SettingsSection.SECURITY ->
            if (settings.threatProtection) "Activada · nivel ${settings.threatLevel.label}" else "Desactivada"
        SettingsSection.LAYERS -> {
            val on = ExtraLayers.available(android.os.Build.MANUFACTURER).filter { it.id in settings.layers }
            if (on.isEmpty()) "Ninguna" else on.joinToString { it.label }
        }
        SettingsSection.APPS -> strategySummary(settings)
        SettingsSection.RULES -> "${settings.allowedDomains.size} permitidos · ${settings.blockedDomains.size} bloqueados"
        SettingsSection.LIST_UPDATES -> settings.updateFrequency.label +
            (app.repository.lastUpdated.takeIf { it > 0 }?.let { " · actualizadas " + relativeTime(it) } ?: "")
        SettingsSection.HISTORY -> if (settings.historyEnabled) "Activado · ${settings.historyRetention.label}" else "Desactivado"
        SettingsSection.BATTERY -> "Ahorro de batería y VPN siempre activa"
        SettingsSection.APP_UPDATES -> "${settings.updateChannel.label} · versión ${BuildConfig.VERSION_NAME}"
        SettingsSection.HELP -> "YouTube, redes sociales, huecos en webs…"
        SettingsSection.ABOUT -> "Versión, licencia y código fuente"
    }
}

/** Pantalla de una categoría, con el indicador de "aplicando cambios" común. */
@Composable
private fun SectionScreen(
    section: SettingsSection,
    onBack: () -> Unit,
    onChangeStrategy: () -> Unit,
    onEditApps: () -> Unit,
) {
    val app = BloqueadorApp.instance
    val scope = rememberCoroutineScope()
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
                if (isActive) applying = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(section.title, onBack)
        if (applying) {
            Column {
                Text("Aplicando cambios…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when (section) {
            SettingsSection.MODE -> ModeSection { applyLists(download = true) }
            SettingsSection.SECURITY -> SecuritySection { applyLists(download = true) }
            SettingsSection.LAYERS -> LayersSection { on -> applyLists(download = on) }
            SettingsSection.APPS -> AppsSection(onChangeStrategy, onEditApps)
            SettingsSection.RULES -> RulesSection { applyLists(download = false) }
            SettingsSection.LIST_UPDATES -> ListUpdatesSection()
            SettingsSection.HISTORY -> HistorySection()
            SettingsSection.BATTERY -> BatterySection()
            SettingsSection.APP_UPDATES -> AppUpdateSection()
            SettingsSection.HELP -> HelpSection()
            SettingsSection.ABOUT -> AboutSection()
        }
    }
}

@Composable
private fun ModeSection(onChanged: () -> Unit) {
    val settings = BloqueadorApp.instance.settings
    var mode by remember { mutableStateOf(settings.mode) }
    SectionCard("¿Cuánto quieres bloquear?") {
        BlockMode.entries.forEach { m ->
            RadioRow(
                selected = m == mode,
                title = m.label + (if (m == BlockMode.BALANCED) " (recomendado)" else "") + " · ${m.source.label}",
                description = m.description,
                onClick = {
                    if (m != mode) {
                        mode = m
                        settings.mode = m
                        onChanged()
                    }
                },
            )
        }
    }
}

@Composable
private fun SecuritySection(onChanged: () -> Unit) {
    val app = BloqueadorApp.instance
    val settings = app.settings
    var enabled by remember { mutableStateOf(settings.threatProtection) }
    var level by remember { mutableStateOf(settings.threatLevel) }
    var alerts by remember { mutableStateOf(settings.threatAlerts) }
    val blockedRecently by androidx.compose.runtime.produceState<Int?>(null) { value = app.history.threatsBlocked() }

    SectionCard("Qué hace") {
        Text(
            "Bloquea dominios conocidos de phishing (webs que imitan a tu banco, a Correos o a una tienda " +
                "para robarte datos), malware, estafas, webs falsas y criptominado. Funciona en todas las " +
                "apps: navegador, correo, mensajería, SMS con enlaces…",
            style = MaterialTheme.typography.bodyMedium,
        )
        SwitchRow(
            title = "Protección contra amenazas",
            description = "Recomendado: déjala activada.",
            checked = enabled,
        ) { on ->
            enabled = on
            settings.threatProtection = on
            onChanged()
        }
        if (enabled) {
            blockedRecently?.let { n ->
                Text(
                    if (n == 0) "No se ha bloqueado ninguna amenaza en las últimas ${settings.historyRetention.label}."
                    else "Amenazas bloqueadas en las últimas ${settings.historyRetention.label}: $n. Las ves en Historial → Amenazas.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (enabled) {
        SectionCard("Nivel") {
            ThreatLevel.entries.forEach { l ->
                RadioRow(
                    selected = l == level,
                    title = l.label + if (l == ThreatLevel.STANDARD) " (recomendado)" else "",
                    description = l.description,
                ) {
                    if (l != level) {
                        level = l
                        settings.threatLevel = l
                        onChanged()
                    }
                }
            }
        }
        SectionCard("Avisos") {
            SwitchRow(
                title = "Avisar cuando se bloquee un sitio peligroso",
                description = "Un bloqueo es silencioso: la web simplemente no carga. El aviso te dice qué se " +
                    "bloqueó y qué app lo pidió. Como mucho un aviso por minuto.",
                checked = alerts,
            ) { on ->
                alerts = on
                settings.threatAlerts = on
            }
        }
    }
    SectionCard("De dónde salen los datos") {
        Text(
            "Listas públicas \"Threat Intelligence Feeds\" de Hagezi, que reúnen decenas de fuentes de " +
                "seguridad y se actualizan varias veces al día. La app las descarga directamente de GitHub y " +
                "las comprueba en el móvil: no envía a nadie las webs que visitas. Las listas de webs falsas y " +
                "de ventanas emergentes maliciosas ya van incluidas en el nivel de bloqueo Equilibrado.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Ninguna lista es perfecta: una web de phishing recién creada puede tardar unas horas en aparecer. " +
                "Desconfía igualmente de enlaces que te piden datos o pagos con prisa.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LayersSection(onChanged: (on: Boolean) -> Unit) {
    val settings = BloqueadorApp.instance.settings
    var layers by remember { mutableStateOf(settings.layers) }
    Text(
        "Listas que se suman a la del nivel de bloqueo. Cuantas más actives, más se bloquea y más memoria " +
            "usa la app.",
        style = MaterialTheme.typography.bodySmall,
    )
    SectionCard("Listas") {
        ExtraLayers.available(android.os.Build.MANUFACTURER).forEach { layer ->
            SwitchRow(
                title = layer.label + if (layer.id == "vendor") " (recomendado)" else "",
                description = layer.description,
                checked = layer.id in layers,
            ) { on ->
                layers = if (on) layers + layer.id else layers - layer.id
                settings.layers = layers
                onChanged(on)
            }
        }
    }
}

@Composable
private fun AppsSection(onChangeStrategy: () -> Unit, onEditApps: () -> Unit) {
    val context = LocalContext.current
    val settings = BloqueadorApp.instance.settings
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(strategySummary(settings)) }
    var strategy by remember { mutableStateOf(settings.strategy) }
    SectionCard("Qué apps pasan por el filtro") {
        Text(summary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onChangeStrategy) { Text("Cambiar estrategia") }
            if (strategy != AppStrategy.ALL) OutlinedButton(onClick = onEditApps) { Text("Editar lista") }
        }
        TextButton(onClick = {
            scope.launch {
                // Recomendado: todo filtrado salvo las apps de banca y pagos instaladas.
                val sensitive = InstalledApps.load(context).filter { it.sensitive }.map { it.packageName }.toSet()
                val recommended = if (sensitive.isEmpty()) AppStrategy.ALL else AppStrategy.ALL_EXCEPT
                applyAppSelection(context, recommended, sensitive.takeIf { it.isNotEmpty() })
                strategy = settings.strategy
                summary = strategySummary(settings)
            }
        }) { Text("Restaurar recomendado (todas menos banca y pagos)") }
    }
}

@Composable
private fun RulesSection(onChanged: () -> Unit) {
    val settings = BloqueadorApp.instance.settings
    var allowed by remember { mutableStateOf(settings.allowedDomains) }
    var blocked by remember { mutableStateOf(settings.blockedDomains) }
    Text(
        "También puedes permitir o bloquear un dominio desde el historial, tocando una entrada.",
        style = MaterialTheme.typography.bodySmall,
    )
    DomainListCard(
        title = "Permitir siempre",
        help = "Dominios que nunca se bloquean, aunque estén en una lista. Incluye sus subdominios.",
        domains = allowed,
    ) {
        allowed = it
        settings.allowedDomains = it
        onChanged()
    }
    DomainListCard(
        title = "Bloquear siempre",
        help = "Dominios que quieres bloquear siempre. Incluye sus subdominios.",
        domains = blocked,
    ) {
        blocked = it
        settings.blockedDomains = it
        onChanged()
    }
}

@Composable
private fun ListUpdatesSection() {
    val context = LocalContext.current
    val app = BloqueadorApp.instance
    val settings = app.settings
    val repo = app.repository
    val scope = rememberCoroutineScope()
    var frequency by remember { mutableStateOf(settings.updateFrequency) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    SectionCard("Cada cuánto se comprueban") {
        UpdateFrequency.entries.forEach { f ->
            RadioRow(selected = f == frequency, title = f.label, description = f.description) {
                if (f != frequency) {
                    frequency = f
                    settings.updateFrequency = f
                    ListUpdateScheduler.schedule(context, f, replace = true)
                }
            }
        }
        Text(
            "Solo se descarga algo si la lista ha cambiado, y nunca con la batería baja.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    SectionCard("Estado") {
        Text(
            if (repo.lastCheck > 0) "Última comprobación: " + relativeTime(repo.lastCheck)
            else "Aún no se ha hecho ninguna comprobación automática.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (repo.consecutiveFailures > 0) {
            Text(
                "Fallos seguidos: ${repo.consecutiveFailures}. ${repo.lastError.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(enabled = !checking, onClick = {
            checking = true
            message = "Comprobando…"
            scope.launch {
                val result = repo.downloadAll()
                repo.recordCheck(result)
                if (result.changed && app.vpnState.value is VpnState.Running) app.reloadEngine()
                message = when {
                    !result.ok -> "Error: ${result.errors.joinToString("; ")}"
                    result.changed -> "Listas actualizadas"
                    else -> "Las listas ya estaban al día"
                }
                checking = false
            }
        }) { Text("Comprobar ahora") }
    }
}

@Composable
private fun HistorySection() {
    val context = LocalContext.current
    val app = BloqueadorApp.instance
    val settings = app.settings
    val scope = rememberCoroutineScope()
    var historyOn by remember { mutableStateOf(settings.historyEnabled) }
    var retention by remember { mutableStateOf(settings.historyRetention) }

    SectionCard("Historial de consultas") {
        SwitchRow(
            title = "Guardar historial",
            description = "Solo en este móvil. Se borra solo pasado el tiempo elegido.",
            checked = historyOn,
        ) { on ->
            historyOn = on
            settings.historyEnabled = on
            if (!on) scope.launch { app.history.clear() }
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
}

@Composable
private fun BatterySection() {
    val context = LocalContext.current
    val optimized = remember { BatteryGuide.isOptimized(context) }
    SectionCard("Que Android no cierre el filtro") {
        Text(
            if (optimized) {
                "Ahora mismo Android puede cerrar Garbillo para ahorrar batería. Garbillo apenas gasta " +
                    "(está dormido hasta que llega una consulta), así que es seguro excluirlo: busca " +
                    "Garbillo y elige \"No optimizar\" o \"Sin restricciones\"."
            } else {
                "Garbillo ya está excluido del ahorro de batería. El filtro seguirá activo aunque no uses el móvil."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        BatteryGuide.manufacturerSteps()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = { BatteryGuide.openSettings(context) }) { Text("Abrir ajustes de batería") }
    }
    val alwaysOn = BloqueadorApp.instance.settings.alwaysOnVpn
    SectionCard("VPN siempre activa (recomendado)") {
        Text(
            when (alwaysOn) {
                true -> "Activada: Android arranca el filtro solo al encender el móvil y lo vuelve a abrir si se cierra."
                false -> "Sin activar. Con ella, Android arranca el filtro solo al encender el móvil y lo vuelve a " +
                    "abrir si algo lo cierra."
                null -> "Con ella, Android arranca el filtro solo al encender el móvil y lo vuelve a abrir si algo lo cierra."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "En Ajustes de Android → Red → VPN, toca el engranaje junto a Garbillo y activa \"VPN siempre activa\". " +
                "No actives \"Bloquear conexiones sin VPN\": si el filtro se reinicia, te quedarías sin internet unos segundos.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
            }
        }) { Text("Abrir ajustes de VPN") }
    }
    SectionCard("Consumo medido") {
        Text(
            "En pruebas de 24 horas el filtro gastó entre el 0,1 % y el 0,4 % de la batería al día.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HelpSection() {
    SectionCard("Lo que no puede quitar") {
        Text(LIMITS_TEXT, style = MaterialTheme.typography.bodyMedium)
    }
    SectionCard("Donde mejor funciona") {
        Text(
            "Juegos y apps gratuitas, publicidad del propio sistema del móvil y rastreadores en todas las " +
                "apps. Para ver qué se ha bloqueado y decidir si te interesaba, usa el historial: \"Ver en la web\" " +
                "te enseña lo que ese dominio pone en la página.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AboutSection() {
    val uri = LocalUriHandler.current
    SectionCard("Garbillo") {
        Text("Versión ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text(
            "Garbillo es un bloqueador de publicidad y rastreadores para todo el móvil, sin root. Funciona dentro del " +
                "móvil: no envía tus datos a ningún servidor y no recoge estadísticas.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Software libre, licencia GPLv3. Listas de bloqueo de Hagezi y otros autores.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { uri.openUri(WEB_URL) }) { Text("Web de Garbillo") }
        OutlinedButton(onClick = { uri.openUri("${WEB_URL}privacidad/") }) { Text("Política de privacidad") }
        OutlinedButton(onClick = { uri.openUri("https://github.com/${BuildConfig.UPDATE_REPO}") }) {
            Text("Código fuente en GitHub")
        }
    }
}

private const val WEB_URL = "https://flowmaticc.github.io/utilities/bloqueador-android/"

/** Fila con título, descripción e interruptor; toda la fila cambia el interruptor. */
@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DomainListCard(
    title: String,
    help: String,
    domains: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    var input by rememberSaveable(title) { mutableStateOf("") }
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
        if (domains.isEmpty()) Text("Ninguno todavía.", style = MaterialTheme.typography.bodySmall)
        domains.sorted().forEach { domain ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(domain, Modifier.weight(1f))
                TextButton(onClick = { onChange(domains - domain) }) { Text("Quitar") }
            }
        }
    }
}
