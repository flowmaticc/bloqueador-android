package app.bloqueador.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.bloqueador.BloqueadorApp
import app.bloqueador.diag.ErrorReportWorker
import app.bloqueador.diag.ErrorReports

/** Qué se envía y qué no, dicho igual en la bienvenida, en Ajustes y en la tarjeta de inicio. */
internal const val ERROR_REPORTS_WHAT =
    "Si algo falla (la app se cierra, una lista no se descarga, una actualización no se instala…), Garbillo " +
        "puede enviar un aviso anónimo para que lo arreglemos. Lleva el tipo de fallo, el sitio del código " +
        "donde ocurrió, la versión de Garbillo y de Android, la marca del móvil y el nivel de bloqueo."

internal const val ERROR_REPORTS_NOT =
    "Nunca lleva webs, historial, tus apps, tu red, ningún identificador del móvil ni el texto de los " +
        "errores. No se guarda la IP. Se envía como mucho una vez al día y, si eliges \"solo con wifi\", " +
        "solo con wifi. Puedes ver el informe exacto antes de que salga y desactivarlo cuando quieras."

/** Guarda la elección del usuario y programa (o cancela) el envío diario. */
internal fun chooseErrorReports(context: android.content.Context, on: Boolean) {
    BloqueadorApp.instance.settings.errorReports = on
    if (!on) ErrorReports.clear(context)
    ErrorReportWorker.schedule(context)
}

/** Pregunta una sola vez a quien ya usaba Garbillo antes de que existieran los informes. */
@Composable
fun ErrorReportsQuestionCard() {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(BloqueadorApp.instance.settings.errorReports != null) }
    if (asked) return
    SectionCard("¿Ayudas a mejorar Garbillo?") {
        Text(ERROR_REPORTS_WHAT, style = MaterialTheme.typography.bodyMedium)
        Text(ERROR_REPORTS_NOT, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { chooseErrorReports(context, true); asked = true }) { Text("Sí, ayudar") }
            TextButton(onClick = { chooseErrorReports(context, false); asked = true }) { Text("No, gracias") }
        }
    }
}

/** Sección de Ajustes: activar o no, ver el informe pendiente y borrarlo. */
@Composable
internal fun ErrorReportsSection() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(BloqueadorApp.instance.settings.errorReports == true) }
    var preview by remember { mutableStateOf<String?>(null) }

    SectionCard("Informes de errores anónimos") {
        Text(ERROR_REPORTS_WHAT, style = MaterialTheme.typography.bodyMedium)
        Text(ERROR_REPORTS_NOT, style = MaterialTheme.typography.bodySmall)
        SwitchRow(
            title = "Enviar informes de errores",
            description = if (on) "Activado. Gracias." else "Desactivado: no se anota ni se envía nada.",
            checked = on,
        ) { v ->
            on = v
            chooseErrorReports(context, v)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { preview = ErrorReports.preview(context) }) { Text("Ver qué se enviaría") }
            TextButton(onClick = {
                ErrorReports.clear(context)
                preview = ErrorReports.preview(context)
            }) { Text("Borrar lo pendiente") }
        }
    }

    preview?.let { text ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Próximo informe") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Cerrar") } },
        )
    }
}
