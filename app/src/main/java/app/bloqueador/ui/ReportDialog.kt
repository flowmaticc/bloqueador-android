package app.bloqueador.ui

import androidx.compose.ui.res.stringResource
import app.bloqueador.R
import app.bloqueador.UiText
import app.bloqueador.intentar
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.bloqueador.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Motivos de un reporte (los mismos que acepta el servicio de reportes). */
enum class ReportReason(val id: String, val label: UiText) {
    AD("anuncio", UiText.res(R.string.reason_ad)),
    BROKEN("rota", UiText.res(R.string.reason_broken)),
    DANGER("peligro", UiText.res(R.string.reason_danger)),
    OTHER("otro", UiText.res(R.string.reason_other)),
}

/**
 * Pide que se analice [domain]. Solo se envían el dominio, el motivo, la versión de la app y la nota
 * si se escribe; nada más (ni historial, ni apps, ni identificadores).
 */
@Composable
fun ReportDialog(domain: String, suggested: ReportReason, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf(suggested) }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.report_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(domain, style = MaterialTheme.typography.titleSmall)
                ReportReason.entries.forEach { r ->
                    RadioRow(selected = r == reason, title = r.label.get(), description = "") { reason = r }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(300) },
                    label = { Text(stringResource(R.string.report_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.report_privacy), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !sending, onClick = {
                sending = true
                scope.launch {
                    val ok = sendReport(domain, reason, note)
                    Toast.makeText(
                        context,
                        if (ok) context.getString(R.string.report_thanks, domain) else context.getString(R.string.report_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                    sending = false
                    if (ok) onDismiss()
                }
            }) { Text(stringResource(if (sending) R.string.report_sending else R.string.report_send)) }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private suspend fun sendReport(domain: String, reason: ReportReason, note: String): Boolean = withContext(Dispatchers.IO) {
    intentar {
        val conn = (URL(BuildConfig.REPORT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject()
            .put("dominio", domain)
            .put("motivo", reason.id)
            .put("origen", "app")
            .put("version", BuildConfig.VERSION_NAME)
            .put("nota", note.trim())
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            conn.responseCode == 200
        } finally {
            conn.disconnect() // también si falla a mitad (sin red, tiempo agotado)
        }
    }.getOrDefault(false)
}
