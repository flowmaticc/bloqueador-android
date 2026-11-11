package app.bloqueador.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.bloqueador.BloqueadorApp
import app.bloqueador.apps.SensitiveApps
import app.bloqueador.visual.BlockerAccessibilityService

/** Bloqueo por elemento visual (sección 9): opcional, granular por app, con aviso previo. */
@Composable
fun VisualBlockScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = BloqueadorApp.instance.visualRules
    val state by store.state.collectAsState()
    var enabled by remember { mutableStateOf(BlockerAccessibilityService.isEnabled(context)) }
    var disclosure by remember { mutableStateOf(false) }

    // Al volver de Ajustes de Android se comprueba si el usuario lo activó.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            enabled = BlockerAccessibilityService.isEnabled(context)
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
        ScreenHeader("Bloqueo visual", onBack)
        Text(
            "Para lo que el filtro DNS no puede quitar: marcas un elemento concreto dentro de una app " +
                "(un banner, un botón \"Saltar\"…) y a partir de entonces se tapa o se pulsa solo.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionCard("Estado") {
            Text(
                if (enabled) "Activado en Ajustes de Android." else "Desactivado. Es opcional: el filtro DNS funciona igual.",
            )
            if (enabled) {
                Button(onClick = {
                    BlockerAccessibilityService.instance?.startSelection()
                    // Se manda al usuario a la pantalla de inicio para que abra la app que quiera.
                    context.startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Marcar un elemento") }
                Text(
                    "Abre la app, toca el botón flotante y después el elemento. Podrás elegir ocultarlo o pulsarlo.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { openAccessibilitySettings(context) }) { Text("Desactivar en Ajustes") }
            } else {
                Button(onClick = { disclosure = true }) { Text("Activar") }
            }
            if (Build.VERSION.SDK_INT < 30) {
                Text(
                    "En esta versión de Android no se pueden guardar miniaturas de lo bloqueado; " +
                        "el historial mostrará solo el nombre del elemento.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        val byApp = state.rules.groupBy { it.packageName }
        if (byApp.isEmpty()) {
            SectionCard("Elementos marcados") { Text("Ninguno todavía.") }
        }
        byApp.forEach { (pkg, rules) ->
            SectionCard(appLabel(context, pkg)) {
                if (SensitiveApps.isSensitive(pkg)) {
                    Text(
                        "App de banca o pagos: no se recomienda usar el bloqueo visual aquí.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Activo en esta app", Modifier.weight(1f))
                    Switch(
                        checked = pkg !in state.pausedApps,
                        onCheckedChange = { on -> store.setPaused(pkg, !on) },
                    )
                }
                rules.forEach { rule ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.label)
                            Text(rule.action.label, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { store.remove(rule.id) }) { Text("Quitar") }
                    }
                }
            }
        }
    }

    if (disclosure) {
        // Aviso destacado que exige Google Play antes de pedir un servicio de accesibilidad.
        AlertDialog(
            onDismissRequest = { disclosure = false },
            title = { Text("Antes de activarlo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bloqueador usará el servicio de accesibilidad de Android para:")
                    Text("• Ver la estructura de la pantalla solo en las apps donde marques un elemento.")
                    Text("• Tapar o pulsar únicamente los elementos que tú marques.")
                    Text("• Guardar una miniatura del elemento bloqueado en tu historial (Android 11+), que se borra sola.")
                    Text(
                        "No lee lo que escribes, no guarda el contenido de tus apps y nada sale del móvil. " +
                            "Puedes desactivarlo cuando quieras.",
                    )
                    Text(
                        "Se abrirá Ajustes → Accesibilidad: entra en \"Bloqueador · bloqueo visual\" y actívalo.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    disclosure = false
                    openAccessibilitySettings(context)
                }) { Text("Aceptar e ir a Ajustes") }
            },
            dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Ahora no") } },
        )
    }
}

private fun openAccessibilitySettings(context: android.content.Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
