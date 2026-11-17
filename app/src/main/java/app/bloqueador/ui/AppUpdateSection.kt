package app.bloqueador.ui

import app.bloqueador.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.bloqueador.BloqueadorApp
import app.bloqueador.BuildConfig
import app.bloqueador.appupdate.AppUpdater
import app.bloqueador.appupdate.UpdateChannel
import app.bloqueador.appupdate.UpdateCheck
import app.bloqueador.appupdate.UpdateInfo
import kotlinx.coroutines.launch

/** Ajustes → Actualizaciones de la app: canal, comprobación e instalación. */
@Composable
fun AppUpdateSection() {
    val context = LocalContext.current
    val settings = BloqueadorApp.instance.settings
    val updater = remember { AppUpdater(context) }
    val scope = rememberCoroutineScope()

    var channel by remember { mutableStateOf(settings.updateChannel) }
    var status by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<UpdateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(-1f) }

    // El resultado final de la instalación llega por el receptor del sistema.
    val installResult by app.bloqueador.appupdate.InstallStatus.message.collectAsState()
    androidx.compose.runtime.LaunchedEffect(installResult) {
        installResult?.let {
            status = it
            app.bloqueador.appupdate.InstallStatus.message.value = null
        }
    }

    fun check() {
        busy = true
        status = context.getString(R.string.upd_searching)
        available = null
        scope.launch {
            when (val r = updater.check(channel)) {
                is UpdateCheck.Available -> {
                    available = r.info
                    status = null
                }
                UpdateCheck.UpToDate -> status = context.getString(R.string.upd_up_to_date, channel.label.get(context))
                is UpdateCheck.Failed -> status = r.message
            }
            settings.lastAppUpdateCheck = System.currentTimeMillis()
            busy = false
        }
    }

    fun install(info: UpdateInfo) {
        if (!updater.canInstall()) {
            status = context.getString(R.string.upd_allow_install)
            updater.openInstallPermissionSettings()
            return
        }
        busy = true
        progress = 0f
        status = context.getString(R.string.upd_downloading, info.versionName)
        app.bloqueador.appupdate.InstallStatus.message.value = null
        scope.launch {
            status = try {
                val apk = updater.download(info) { progress = it }
                updater.install(apk)
                context.getString(R.string.upd_installing)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                app.bloqueador.diag.ErrorReports.record(context, "actualizacion_app", e)
                context.getString(R.string.upd_failed, e.message ?: "")
            }
            progress = -1f
            busy = false
        }
    }

    SectionCard(stringResource(R.string.upd_title)) {
        Text(
            stringResource(R.string.upd_installed_version, BuildConfig.VERSION_NAME, updater.installedVersionCode),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(R.string.upd_channel), style = MaterialTheme.typography.bodyMedium)
        UpdateChannel.entries.forEach { c ->
            RadioRow(
                selected = c == channel,
                title = if (c == UpdateChannel.STABLE) stringResource(R.string.common_recommended, c.label.get()) else c.label.get(),
                description = c.description.get(),
                onClick = {
                    channel = c
                    settings.updateChannel = c
                    available = null
                    status = null
                },
            )
        }
        if (channel != UpdateChannel.STABLE) {
            Text(
                stringResource(R.string.upd_back_to_stable),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        available?.let { info ->
            Text(stringResource(R.string.upd_available, info.versionName), style = MaterialTheme.typography.titleSmall)
            if (info.isUrgentFor(updater.installedVersionCode)) {
                Text(
                    stringResource(R.string.upd_urgent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            info.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            // En el idioma de la app (español o, para el resto, inglés).
            val text = info.notesFor(context.resources.configuration.locales[0].language)
            if (text.isNotBlank()) {
                // Entera; solo si fuera muy larga se corta, y siempre al final de una línea.
                val notes = if (text.length <= 2000) text
                else text.take(2000).substringBeforeLast('\n') + "\n…"
                Text(notes, style = MaterialTheme.typography.bodySmall)
            }
            Button(enabled = !busy, onClick = { install(info) }) { Text(stringResource(R.string.upd_download_install)) }
        }
        if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = ::check) { Text(stringResource(R.string.upd_check_now)) }
        }
        Text(
            stringResource(R.string.upd_daily_note),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
