package app.bloqueador.ui

import app.bloqueador.AppLanguage
import app.bloqueador.UiText
import app.bloqueador.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import app.bloqueador.update.DownloadPolicy
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
enum class SettingsSection(@androidx.annotation.StringRes val title: Int, @androidx.annotation.StringRes val group: Int) {
    MODE(R.string.sec_mode, R.string.group_protection),
    SECURITY(R.string.sec_security, R.string.group_protection),
    LAYERS(R.string.sec_layers, R.string.group_protection),
    APPS(R.string.sec_apps, R.string.group_protection),
    RULES(R.string.sec_rules, R.string.group_protection),
    ENCRYPTED_DNS(R.string.sec_encrypted_dns, R.string.group_protection),
    LIST_UPDATES(R.string.sec_list_updates, R.string.group_operation),
    HISTORY(R.string.sec_history, R.string.group_operation),
    BATTERY(R.string.sec_battery, R.string.group_operation),
    DIAGNOSTICS(R.string.sec_diagnostics, R.string.group_operation),
    LANGUAGE(R.string.sec_language, R.string.group_app),
    APP_UPDATES(R.string.sec_app_updates, R.string.group_app),
    ERROR_REPORTS(R.string.sec_error_reports, R.string.group_app),
    HELP(R.string.sec_help, R.string.group_app),
    ABOUT(R.string.sec_about, R.string.group_app),
}

/** Secciones que muestra esta variante (la de F-Droid no tiene actualizador propio). */
fun visibleSections(selfUpdate: Boolean = BuildConfig.SELF_UPDATE): List<SettingsSection> =
    SettingsSection.entries.filter { selfUpdate || it != SettingsSection.APP_UPDATES }

/**
 * Un ajuste concreto para el buscador: dónde está, su título y palabras con las que se puede encontrar.
 * Título y palabras están en los ficheros de textos, así que el buscador funciona en cada idioma.
 */
data class SettingEntry(val section: SettingsSection, val title: UiText, val keywords: UiText)

/** Índice del buscador. Al añadir un ajuste nuevo, añadirlo aquí también. */
fun settingsIndex(manufacturer: String?): List<SettingEntry> = buildList {
    fun add(section: SettingsSection, title: Int, keywords: Int) = add(SettingEntry(section, UiText.res(title), UiText.res(keywords)))
    add(SettingsSection.MODE, R.string.sec_mode, R.string.idx_mode_kw)
    add(SettingsSection.SECURITY, R.string.sec_security, R.string.idx_security_kw)
    add(SettingsSection.SECURITY, R.string.idx_threat_alerts, R.string.idx_threat_alerts_kw)
    add(SettingsSection.LAYERS, R.string.sec_layers, R.string.idx_layers_kw)
    ExtraLayers.available(manufacturer).forEach {
        add(SettingEntry(SettingsSection.LAYERS, it.label, UiText.res(R.string.kw_pair, it.description, it.id)))
    }
    add(SettingsSection.APPS, R.string.sec_apps, R.string.idx_apps_kw)
    add(SettingsSection.APPS, R.string.idx_restore, R.string.idx_restore_kw)
    add(SettingsSection.RULES, R.string.idx_allow, R.string.idx_allow_kw)
    add(SettingsSection.RULES, R.string.idx_block, R.string.idx_block_kw)
    add(SettingsSection.ENCRYPTED_DNS, R.string.sec_encrypted_dns, R.string.idx_dns_kw)
    add(SettingsSection.LIST_UPDATES, R.string.idx_frequency, R.string.idx_frequency_kw)
    add(SettingsSection.LIST_UPDATES, R.string.idx_check_now, R.string.idx_check_now_kw)
    add(SettingsSection.LIST_UPDATES, R.string.idx_wifi, R.string.idx_wifi_kw)
    add(SettingsSection.ERROR_REPORTS, R.string.sec_error_reports, R.string.idx_reports_kw)
    add(SettingsSection.HISTORY, R.string.idx_history_save, R.string.idx_history_save_kw)
    add(SettingsSection.HISTORY, R.string.idx_history_keep, R.string.idx_history_keep_kw)
    add(SettingsSection.HISTORY, R.string.idx_history_clear, R.string.idx_history_clear_kw)
    add(SettingsSection.BATTERY, R.string.battery_card_title, R.string.idx_battery_kw)
    add(SettingsSection.BATTERY, R.string.idx_boot, R.string.idx_boot_kw)
    add(SettingsSection.BATTERY, R.string.idx_always_on, R.string.idx_always_on_kw)
    add(SettingsSection.DIAGNOSTICS, R.string.sec_diagnostics, R.string.idx_diagnostics_kw)
    add(SettingsSection.LANGUAGE, R.string.sec_language, R.string.idx_language_kw)
    add(SettingsSection.ABOUT, R.string.idx_web, R.string.idx_web_kw)
    add(SettingsSection.APP_UPDATES, R.string.idx_channel, R.string.idx_channel_kw)
    add(SettingsSection.HELP, R.string.idx_limits, R.string.idx_limits_kw)
    add(SettingsSection.ABOUT, R.string.sec_about, R.string.idx_about_kw)
}

/**
 * Busca sin distinguir mayúsculas ni tildes; todas las palabras de [query] deben aparecer.
 * [text] traduce cada texto al idioma actual (en la app, con el contexto; en los tests, con el fichero).
 */
fun searchSettings(index: List<SettingEntry>, query: String, text: (UiText) -> String): List<SettingEntry> {
    val words = normalize(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    return index.filter { e ->
        val all = normalize("${text(e.title)} ${text(UiText.res(e.section.title))} ${text(e.keywords)}")
        words.all { it in all }
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
    val context = LocalContext.current
    val settings = BloqueadorApp.instance.settings
    var query by rememberSaveable { mutableStateOf("") }
    val index = remember {
        val visible = visibleSections()
        settingsIndex(android.os.Build.MANUFACTURER).filter { it.section in visible }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(stringResource(R.string.set_title), onBack)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.set_search)) },
            singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("✕") } },
            modifier = Modifier.fillMaxWidth(),
        )

        if (query.isNotBlank()) {
            val results = searchSettings(index, query) { it.get(context) }
            if (results.isEmpty()) {
                Text(stringResource(R.string.set_no_match), style = MaterialTheme.typography.bodyMedium)
            } else {
                Card(Modifier.fillMaxWidth()) {
                    results.forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider()
                        NavRow(e.title.get(), stringResource(e.section.title)) {
                            // Se cierra el teclado antes de cambiar de pantalla: si no, la nueva
                            // reserva su hueco y en algunos Android queda cortada.
                            focus.clearFocus()
                            keyboard?.hide()
                            onOpen(e.section)
                        }
                    }
                }
            }
            return@Column
        }

        visibleSections().groupBy { it.group }.forEach { (group, sections) ->
            Text(
                stringResource(group),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Card(Modifier.fillMaxWidth()) {
                sections.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider()
                    NavRow(stringResource(s.title), sectionSummary(context, s, settings)) { onOpen(s) }
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
private fun sectionSummary(context: android.content.Context, s: SettingsSection, settings: AppSettings): String {
    val app = BloqueadorApp.instance
    fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    return when (s) {
        SettingsSection.MODE -> str(R.string.mode_title_with_list, settings.mode.label.get(context), settings.mode.source.label.get(context))
        SettingsSection.SECURITY ->
            if (settings.threatProtection) str(R.string.sum_on_level, settings.threatLevel.label.get(context)) else str(R.string.sum_off_f)
        SettingsSection.LAYERS -> {
            val on = ExtraLayers.available(android.os.Build.MANUFACTURER).filter { it.id in settings.layers }
            if (on.isEmpty()) str(R.string.sum_none) else on.joinToString { it.label.get(context) }
        }
        SettingsSection.APPS -> strategySummary(context, settings)
        SettingsSection.RULES -> str(R.string.sum_rules, settings.allowedDomains.size, settings.blockedDomains.size)
        SettingsSection.ENCRYPTED_DNS ->
            settings.encryptedDns?.let { str(R.string.sum_dns_on, it.label.get(context)) } ?: str(R.string.sum_dns_off)
        SettingsSection.LIST_UPDATES -> app.repository.lastUpdated.takeIf { it > 0 }
            ?.let { str(R.string.sum_updated, settings.updateFrequency.label.get(context), relativeTime(context, it)) }
            ?: settings.updateFrequency.label.get(context)
        SettingsSection.HISTORY ->
            if (settings.historyEnabled) str(R.string.sum_history_on, settings.historyRetention.label.get(context)) else str(R.string.sum_off_m)
        SettingsSection.BATTERY -> str(R.string.sum_battery)
        SettingsSection.DIAGNOSTICS -> str(R.string.sum_diagnostics)
        SettingsSection.LANGUAGE -> AppLanguage.current(context)?.let(AppLanguage::displayName)
            ?: str(R.string.sum_language_system, AppLanguage.displayName(java.util.Locale.getDefault().language))
        SettingsSection.APP_UPDATES -> str(R.string.sum_app_updates, settings.updateChannel.label.get(context), BuildConfig.VERSION_NAME)
        SettingsSection.ERROR_REPORTS -> when (settings.errorReports) {
            true -> str(R.string.sum_reports_on)
            false -> str(R.string.sum_reports_off)
            null -> str(R.string.sum_reports_undecided)
        }
        SettingsSection.HELP -> str(R.string.sum_help)
        SettingsSection.ABOUT -> str(R.string.sum_about)
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
    val context = LocalContext.current
    val app = BloqueadorApp.instance
    val scope = rememberCoroutineScope()
    var applying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    /**
     * Descarga lo que falte (si [download]) y recarga el motor. Un cambio nuevo cancela el anterior.
     * Si la descarga falla, [revert] deja el ajuste como estaba: así nunca se ve activada una lista
     * que no está en el móvil.
     */
    fun applyLists(download: Boolean, revert: (() -> Unit)? = null) {
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
                        if (revert != null) {
                            revert()
                            error = context.getString(R.string.set_list_failed_reverted)
                        } else {
                            error = context.getString(R.string.set_download_failed, e.message ?: "")
                        }
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
        ScreenHeader(stringResource(section.title), onBack)
        if (applying) {
            Column {
                Text(stringResource(R.string.set_applying), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when (section) {
            SettingsSection.MODE -> ModeSection { revert -> applyLists(download = true, revert) }
            SettingsSection.SECURITY -> SecuritySection { revert -> applyLists(download = true, revert) }
            SettingsSection.LAYERS -> LayersSection { on, revert -> applyLists(download = on, revert.takeIf { on }) }
            SettingsSection.APPS -> AppsSection(onChangeStrategy, onEditApps)
            SettingsSection.RULES -> RulesSection { applyLists(download = false) }
            SettingsSection.ENCRYPTED_DNS -> EncryptedDnsSection()
            SettingsSection.LIST_UPDATES -> ListUpdatesSection()
            SettingsSection.HISTORY -> HistorySection()
            SettingsSection.BATTERY -> BatterySection()
            SettingsSection.DIAGNOSTICS -> DiagnosticsSection()
            SettingsSection.LANGUAGE -> LanguageSection()
            SettingsSection.APP_UPDATES -> AppUpdateSection()
            SettingsSection.ERROR_REPORTS -> ErrorReportsSection()
            SettingsSection.HELP -> HelpSection()
            SettingsSection.ABOUT -> AboutSection()
        }
    }
}

@Composable
private fun ModeSection(onChanged: (revert: () -> Unit) -> Unit) {
    val settings = BloqueadorApp.instance.settings
    var mode by remember { mutableStateOf(settings.mode) }
    SectionCard(stringResource(R.string.mode_question)) {
        BlockMode.entries.forEach { m ->
            RadioRow(
                selected = m == mode,
                title = stringResource(
                    R.string.mode_title_with_list,
                    if (m == BlockMode.BALANCED) stringResource(R.string.common_recommended, m.label.get()) else m.label.get(),
                    m.source.label.get(),
                ),
                description = m.description.get(),
                onClick = {
                    if (m != mode) {
                        val previous = mode
                        mode = m
                        settings.mode = m
                        onChanged {
                            mode = previous
                            settings.mode = previous
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SecuritySection(onChanged: (revert: () -> Unit) -> Unit) {
    val app = BloqueadorApp.instance
    val settings = app.settings
    var enabled by remember { mutableStateOf(settings.threatProtection) }
    var level by remember { mutableStateOf(settings.threatLevel) }
    var alerts by remember { mutableStateOf(settings.threatAlerts) }
    val blockedRecently by androidx.compose.runtime.produceState<Int?>(null) { value = app.history.threatsBlocked() }

    SectionCard(stringResource(R.string.sec_what)) {
        Text(
            stringResource(R.string.sec_what_text),
            style = MaterialTheme.typography.bodyMedium,
        )
        SwitchRow(
            title = stringResource(R.string.sec_security),
            description = stringResource(R.string.sec_switch_desc),
            checked = enabled,
        ) { on ->
            enabled = on
            settings.threatProtection = on
            onChanged {
                // Solo se deshace al activarla (desactivar no descarga nada).
                if (on) {
                    enabled = false
                    settings.threatProtection = false
                }
            }
        }
        if (enabled) {
            blockedRecently?.let { n ->
                Text(
                    if (n == 0) stringResource(R.string.sec_none_blocked, settings.historyRetention.label.get())
                    else stringResource(R.string.sec_blocked_n, settings.historyRetention.label.get(), n),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (enabled) {
        SectionCard(stringResource(R.string.sec_level)) {
            ThreatLevel.entries.forEach { l ->
                RadioRow(
                    selected = l == level,
                    title = if (l == ThreatLevel.STANDARD) stringResource(R.string.common_recommended, l.label.get()) else l.label.get(),
                    description = l.description.get(),
                ) {
                    if (l != level) {
                        val previous = level
                        level = l
                        settings.threatLevel = l
                        onChanged {
                            level = previous
                            settings.threatLevel = previous
                        }
                    }
                }
            }
        }
        SectionCard(stringResource(R.string.sec_alerts)) {
            SwitchRow(
                title = stringResource(R.string.sec_alerts_switch),
                description = stringResource(R.string.sec_alerts_desc),
                checked = alerts,
            ) { on ->
                alerts = on
                settings.threatAlerts = on
            }
        }
    }
    SectionCard(stringResource(R.string.sec_sources)) {
        Text(
            stringResource(R.string.sec_sources_text),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.sec_sources_limits),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LayersSection(onChanged: (on: Boolean, revert: () -> Unit) -> Unit) {
    val settings = BloqueadorApp.instance.settings
    var layers by remember { mutableStateOf(settings.layers) }
    Text(
        stringResource(R.string.layers_intro),
        style = MaterialTheme.typography.bodySmall,
    )
    SectionCard(stringResource(R.string.layers_lists)) {
        ExtraLayers.available(android.os.Build.MANUFACTURER).forEach { layer ->
            SwitchRow(
                title = if (layer.id == "vendor") stringResource(R.string.common_recommended, layer.label.get()) else layer.label.get(),
                description = layer.description.get(),
                checked = layer.id in layers,
            ) { on ->
                layers = if (on) layers + layer.id else layers - layer.id
                settings.layers = layers
                onChanged(on) {
                    layers = settings.layers - layer.id
                    settings.layers = layers
                }
            }
        }
    }
}

@Composable
private fun AppsSection(onChangeStrategy: () -> Unit, onEditApps: () -> Unit) {
    val context = LocalContext.current
    val settings = BloqueadorApp.instance.settings
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(strategySummary(context, settings)) }
    var strategy by remember { mutableStateOf(settings.strategy) }
    SectionCard(stringResource(R.string.apps_which)) {
        Text(summary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onChangeStrategy) { Text(stringResource(R.string.apps_change_strategy)) }
            if (strategy != AppStrategy.ALL) OutlinedButton(onClick = onEditApps) { Text(stringResource(R.string.apps_edit_list)) }
        }
        TextButton(onClick = {
            scope.launch {
                // Recomendado: todo filtrado salvo las apps de banca y pagos instaladas.
                val sensitive = InstalledApps.load(context).filter { it.sensitive }.map { it.packageName }.toSet()
                val recommended = if (sensitive.isEmpty()) AppStrategy.ALL else AppStrategy.ALL_EXCEPT
                applyAppSelection(context, recommended, sensitive.takeIf { it.isNotEmpty() })
                strategy = settings.strategy
                summary = strategySummary(context, settings)
            }
        }) { Text(stringResource(R.string.apps_restore)) }
    }
}

@Composable
private fun RulesSection(onChanged: () -> Unit) {
    val settings = BloqueadorApp.instance.settings
    var allowed by remember { mutableStateOf(settings.allowedDomains) }
    var blocked by remember { mutableStateOf(settings.blockedDomains) }
    Text(
        stringResource(R.string.rules_intro),
        style = MaterialTheme.typography.bodySmall,
    )
    DomainListCard(
        title = stringResource(R.string.hist_allow_always),
        help = stringResource(R.string.rules_allow_help),
        domains = allowed,
    ) {
        allowed = it
        settings.allowedDomains = it
        onChanged()
    }
    DomainListCard(
        title = stringResource(R.string.hist_block_always),
        help = stringResource(R.string.rules_block_help),
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

    SectionCard(stringResource(R.string.lu_how_often)) {
        UpdateFrequency.entries.forEach { f ->
            RadioRow(selected = f == frequency, title = f.label.get(), description = f.description.get()) {
                if (f != frequency) {
                    frequency = f
                    settings.updateFrequency = f
                    ListUpdateScheduler.schedule(context, f)
                }
            }
        }
        Text(
            stringResource(R.string.lu_only_changed),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    var wifiOnly by remember { mutableStateOf(settings.downloadsWifiOnly) }
    SectionCard(stringResource(R.string.lu_network)) {
        SwitchRow(
            title = stringResource(R.string.lu_wifi_only),
            description = stringResource(R.string.lu_wifi_only_desc),
            checked = wifiOnly,
        ) { on ->
            wifiOnly = on
            settings.downloadsWifiOnly = on
            DownloadPolicy.reschedule(context)
        }
    }
    SectionCard(stringResource(R.string.lu_status)) {
        Text(
            if (repo.lastCheck > 0) stringResource(R.string.lu_last_check, relativeTime(context, repo.lastCheck))
            else stringResource(R.string.lu_never_checked),
            style = MaterialTheme.typography.bodySmall,
        )
        if (repo.consecutiveFailures > 0) {
            Text(
                stringResource(R.string.lu_failures, repo.consecutiveFailures, repo.lastError.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(enabled = !checking, onClick = {
            checking = true
            message = context.getString(R.string.lu_checking)
            scope.launch {
                val result = repo.downloadAll()
                repo.recordCheck(result)
                if (result.changed && app.vpnState.value is VpnState.Running) app.reloadEngine()
                message = when {
                    !result.ok -> context.getString(R.string.lu_error, result.errors.joinToString("; "))
                    result.changed -> context.getString(R.string.home_lists_updated)
                    else -> context.getString(R.string.home_lists_current)
                }
                checking = false
            }
        }) { Text(stringResource(R.string.lu_check_now)) }
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

    SectionCard(stringResource(R.string.hs_title)) {
        SwitchRow(
            title = stringResource(R.string.idx_history_save),
            description = stringResource(R.string.hs_save_desc),
            checked = historyOn,
        ) { on ->
            historyOn = on
            settings.historyEnabled = on
            if (!on) scope.launch { app.history.clear() }
        }
        if (historyOn) {
            Text(stringResource(R.string.hs_keep_for), style = MaterialTheme.typography.bodyMedium)
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
                        label = { Text(r.label.get()) },
                    )
                }
            }
        }
        OutlinedButton(onClick = {
            scope.launch {
                app.history.clear()
                Toast.makeText(context, context.getString(R.string.hs_cleared), Toast.LENGTH_SHORT).show()
            }
        }) { Text(stringResource(R.string.hs_clear_now)) }
    }
}

@Composable
private fun EncryptedDnsSection() {
    val settings = BloqueadorApp.instance.settings
    var selected by remember { mutableStateOf(settings.encryptedDns) }
    SectionCard(stringResource(R.string.sec_encrypted_dns)) {
        Text(
            stringResource(R.string.dns_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
        RadioRow(
            selected = selected == null,
            title = stringResource(R.string.dns_off),
            description = stringResource(R.string.dns_off_desc),
            onClick = { selected = null; settings.encryptedDns = null },
        )
        app.bloqueador.vpn.DotProvider.entries.forEach { p ->
            RadioRow(
                selected = selected == p,
                title = p.label.get(),
                description = p.description.get(),
                onClick = { selected = p; settings.encryptedDns = p },
            )
        }
        Text(
            stringResource(R.string.dns_note),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Idioma de la app: el del móvil (por defecto) o uno de los disponibles (carpetas res/values-xx). */
@Composable
private fun LanguageSection() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppLanguage.current(context)) }
    fun choose(tag: String?) {
        if (tag == selected) return
        selected = tag
        AppLanguage.set(context, tag)
        // Android 13+ vuelve a crear la pantalla él solo; en 8 a 12 hay que hacerlo a mano.
        if (android.os.Build.VERSION.SDK_INT < 33) (context as? android.app.Activity)?.recreate()
    }
    SectionCard(stringResource(R.string.sec_language)) {
        Text(stringResource(R.string.lang_intro), style = MaterialTheme.typography.bodySmall)
        RadioRow(
            selected = selected == null,
            title = stringResource(R.string.lang_system),
            description = "",
            onClick = { choose(null) },
        )
        AppLanguage.available().forEach { (tag, name) ->
            RadioRow(selected = selected == tag, title = name, description = "", onClick = { choose(tag) })
        }
    }
}

@Composable
private fun BatterySection() {
    val context = LocalContext.current
    val optimized = remember { BatteryGuide.isOptimized(context) }
    SectionCard(stringResource(R.string.battery_card_title)) {
        Text(
            stringResource(if (optimized) R.string.bat_optimized else R.string.bat_excluded),
            style = MaterialTheme.typography.bodyMedium,
        )
        BatteryGuide.manufacturerSteps(context)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = { BatteryGuide.openSettings(context) }) { Text(stringResource(R.string.bat_open)) }
    }
    val alwaysOn = BloqueadorApp.instance.settings.alwaysOnVpn
    SectionCard(stringResource(R.string.idx_boot)) {
        Text(
            stringResource(R.string.bat_boot_text),
            style = MaterialTheme.typography.bodyMedium,
        )
        BatteryGuide.autostartSteps(context)?.let { steps ->
            Text(
                stringResource(R.string.bat_autostart_needed, steps),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { BatteryGuide.openAutostart(context) }) { Text(stringResource(R.string.bat_open_autostart)) }
        }
    }
    SectionCard(stringResource(R.string.bat_always_on_title)) {
        Text(
            when (alwaysOn) {
                true -> stringResource(R.string.bat_always_on_yes)
                false -> stringResource(R.string.bat_always_on_no)
                null -> stringResource(R.string.bat_always_on_unknown)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.bat_always_on_how),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
            }
        }) { Text(stringResource(R.string.home_open_vpn_settings)) }
    }
    // Perfil de trabajo, Espacio privado (Android 15) o "Carpeta segura": cada perfil tiene sus propias
    // VPN, así que sus apps no pasan por Garbillo.
    val otherProfiles = remember {
        runCatching { context.getSystemService(android.os.UserManager::class.java).userProfiles.size - 1 }.getOrDefault(0)
    }
    if (otherProfiles > 0) {
        SectionCard(stringResource(R.string.bat_profiles_title)) {
            Text(
                stringResource(R.string.bat_profiles_text),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    SectionCard(stringResource(R.string.bat_measured_title)) {
        Text(
            stringResource(R.string.bat_measured_text),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HelpSection() {
    SectionCard(stringResource(R.string.onb_limits_title)) {
        Text(stringResource(R.string.limits_text), style = MaterialTheme.typography.bodyMedium)
    }
    SectionCard(stringResource(R.string.help_best)) {
        Text(
            stringResource(R.string.help_best_text),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AboutSection() {
    val uri = LocalUriHandler.current
    SectionCard("Garbillo") {
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        Text(
            stringResource(R.string.about_text),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodySmall)
        val web = stringResource(R.string.url_web)
        val privacy = stringResource(R.string.url_privacy)
        OutlinedButton(onClick = { uri.openUri(web) }) { Text(stringResource(R.string.about_web)) }
        OutlinedButton(onClick = { uri.openUri(privacy) }) { Text(stringResource(R.string.about_privacy)) }
        OutlinedButton(onClick = { uri.openUri("https://github.com/${BuildConfig.UPDATE_REPO}") }) {
            Text(stringResource(R.string.about_source))
        }
    }
}


/** Fila con título, descripción e interruptor; toda la fila cambia el interruptor. */
@Composable
internal fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
                label = { Text(stringResource(R.string.domain_hint)) },
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
            }) { Text(stringResource(R.string.domain_add)) }
        }
        if (invalid) Text(stringResource(R.string.domain_invalid), color = MaterialTheme.colorScheme.error)
        if (domains.isEmpty()) Text(stringResource(R.string.domain_none), style = MaterialTheme.typography.bodySmall)
        domains.sorted().forEach { domain ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(domain, Modifier.weight(1f))
                TextButton(onClick = { onChange(domains - domain) }) { Text(stringResource(R.string.domain_remove)) }
            }
        }
    }
}
