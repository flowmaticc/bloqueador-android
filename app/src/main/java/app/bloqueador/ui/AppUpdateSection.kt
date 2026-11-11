package app.bloqueador.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

    fun check() {
        busy = true
        status = "Buscando…"
        available = null
        scope.launch {
            when (val r = updater.check(channel)) {
                is UpdateCheck.Available -> {
                    available = r.info
                    status = null
                }
                UpdateCheck.UpToDate -> status = "Tienes la última versión del canal ${channel.label}."
                is UpdateCheck.Failed -> status = r.message
            }
            settings.lastAppUpdateCheck = System.currentTimeMillis()
            busy = false
        }
    }

    fun install(info: UpdateInfo) {
        if (!updater.canInstall()) {
            status = "Permite a Bloqueador instalar actualizaciones y vuelve a pulsar \"Instalar\"."
            updater.openInstallPermissionSettings()
            return
        }
        busy = true
        progress = 0f
        status = "Descargando ${info.versionName}…"
        scope.launch {
            status = try {
                val apk = updater.download(info) { progress = it }
                updater.install(apk)
                "Instalando… Android te pedirá confirmación."
            } catch (e: Exception) {
                "No se pudo actualizar: ${e.message}"
            }
            progress = -1f
            busy = false
        }
    }

    SectionCard("Actualizaciones de la app") {
        Text(
            "Versión instalada: ${BuildConfig.VERSION_NAME} (${updater.installedVersionCode})",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Canal de actualizaciones:", style = MaterialTheme.typography.bodyMedium)
        UpdateChannel.entries.forEach { c ->
            RadioRow(
                selected = c == channel,
                title = c.label + if (c == UpdateChannel.STABLE) " (recomendado)" else "",
                description = c.description,
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
                "Para volver a Estable desde una versión más nueva habrá que esperar a que salga una " +
                    "estable superior (Android no permite instalar una versión anterior).",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        available?.let { info ->
            Text("Nueva versión disponible: ${info.versionName}", style = MaterialTheme.typography.titleSmall)
            if (info.notes.isNotBlank()) {
                Text(info.notes.take(600), style = MaterialTheme.typography.bodySmall)
            }
            Button(enabled = !busy, onClick = { install(info) }) { Text("Descargar e instalar") }
        }
        if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = ::check) { Text("Buscar ahora") }
        }
        Text(
            "Se comprueba solo una vez al día y te avisa con una notificación. Nunca se instala nada " +
                "sin que lo confirmes.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
