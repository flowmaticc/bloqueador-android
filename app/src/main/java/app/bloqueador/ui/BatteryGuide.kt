package app.bloqueador.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/**
 * Guía para que el ahorro de energía del sistema no cierre el filtro.
 *
 * El servicio VPN en primer plano ya está protegido por Android, pero algunos fabricantes añaden
 * su propia capa de ahorro que cierra apps "dormidas". Solo se muestra si la app aún está sujeta a
 * la optimización de batería y el usuario no la ha descartado.
 */
object BatteryGuide {

    fun isOptimized(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Pasos adicionales según el fabricante (además de "Sin restricciones"). */
    fun manufacturerSteps(): String? = when (Build.MANUFACTURER.lowercase()) {
        "samsung" ->
            "Samsung: Ajustes → Batería → Límites de uso en segundo plano → \"Aplicaciones que nunca " +
                "entran en suspensión\" → añade Bloqueador."
        "xiaomi", "redmi", "poco" ->
            "Xiaomi: Ajustes → Aplicaciones → Bloqueador → activa \"Inicio automático\" y en " +
                "\"Ahorro de batería\" elige \"Sin restricciones\"."
        "huawei", "honor" ->
            "Huawei/Honor: Ajustes → Batería → Inicio de aplicaciones → Bloqueador → \"Gestionar " +
                "manualmente\" y activa las tres opciones."
        "oppo", "realme", "oneplus" ->
            "OPPO/Realme/OnePlus: Ajustes → Batería → Bloqueador → permite la actividad en segundo plano."
        else -> null
    }

    /** Lista de optimización de batería del sistema (no se usa la petición directa, que Play limita). */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** Tarjeta de la pantalla principal; desaparece cuando ya no hace falta o si se descarta. */
@Composable
fun BatteryGuideCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var optimized by remember { mutableStateOf(BatteryGuide.isOptimized(context)) }
    var dismissed by remember { mutableStateOf(prefs.getBoolean("battery_guide_dismissed", false)) }

    // Al volver de Ajustes se comprueba si el usuario ya lo cambió.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { optimized = BatteryGuide.isOptimized(context) }
    }
    if (!optimized || dismissed) return

    SectionCard("Que Android no cierre el filtro") {
        Text(
            "El ahorro de batería del sistema puede cerrar el filtro cuando no usas el móvil. " +
                "Bloqueador apenas gasta (está dormido hasta que llega una consulta), así que es seguro " +
                "excluirlo: busca Bloqueador en la lista y elige \"No optimizar\" o \"Sin restricciones\".",
            style = MaterialTheme.typography.bodyMedium,
        )
        BatteryGuide.manufacturerSteps()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { BatteryGuide.openSettings(context) }) { Text("Abrir ajustes") }
            TextButton(onClick = {
                dismissed = true
                prefs.edit().putBoolean("battery_guide_dismissed", true).apply()
            }) { Text("Ya lo hice") }
        }
    }
}
