package app.bloqueador.ui

import app.bloqueador.R
import androidx.compose.ui.res.stringResource
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
    SectionCard(stringResource(R.string.onb_help_title)) {
        Text(stringResource(R.string.error_reports_what), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.error_reports_not), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { chooseErrorReports(context, true); asked = true }) { Text(stringResource(R.string.reports_yes_help)) }
            TextButton(onClick = { chooseErrorReports(context, false); asked = true }) { Text(stringResource(R.string.onb_reports_no)) }
        }
    }
}

/** Sección de Ajustes: activar o no, ver el informe pendiente y borrarlo. */
@Composable
internal fun ErrorReportsSection() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(BloqueadorApp.instance.settings.errorReports == true) }
    var preview by remember { mutableStateOf<String?>(null) }

    SectionCard(stringResource(R.string.reports_section_title)) {
        Text(stringResource(R.string.error_reports_what), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.error_reports_not), style = MaterialTheme.typography.bodySmall)
        SwitchRow(
            title = stringResource(R.string.reports_switch),
            description = stringResource(if (on) R.string.reports_on else R.string.reports_off),
            checked = on,
        ) { v ->
            on = v
            chooseErrorReports(context, v)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { preview = ErrorReports.preview(context) }) { Text(stringResource(R.string.reports_preview)) }
            TextButton(onClick = {
                ErrorReports.clear(context)
                preview = ErrorReports.preview(context)
            }) { Text(stringResource(R.string.reports_clear)) }
        }
    }

    preview?.let { text ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.reports_next)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.common_close)) } },
        )
    }
}
