package app.bloqueador.ui

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
enum class ReportReason(val id: String, val label: String) {
    AD("anuncio", "Pasa publicidad o rastreo"),
    BROKEN("rota", "Algo deja de funcionar"),
    DANGER("peligro", "Parece peligroso (phishing, estafa…)"),
    OTHER("otro", "Otro"),
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
        title = { Text("Reportar para analizar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(domain, style = MaterialTheme.typography.titleSmall)
                ReportReason.entries.forEach { r ->
                    RadioRow(selected = r == reason, title = r.label, description = "") { reason = r }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(300) },
                    label = { Text("Nota (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Se envía solo este dominio, el motivo y la nota. Sirve para mejorar las listas de " +
                        "todos. Nada de tu historial ni de tus apps.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !sending, onClick = {
                sending = true
                scope.launch {
                    val ok = sendReport(domain, reason, note)
                    Toast.makeText(
                        context,
                        if (ok) "Gracias: se analizará $domain" else "No se pudo enviar. Prueba más tarde.",
                        Toast.LENGTH_SHORT,
                    ).show()
                    sending = false
                    if (ok) onDismiss()
                }
            }) { Text(if (sending) "Enviando…" else "Enviar") }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancelar") } },
    )
}

private suspend fun sendReport(domain: String, reason: ReportReason, note: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
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
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = conn.responseCode == 200
        conn.disconnect()
        ok
    }.getOrDefault(false)
}
