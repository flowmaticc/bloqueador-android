package app.bloqueador.ui

import app.bloqueador.R
import androidx.compose.ui.res.stringResource
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
    fun manufacturerSteps(context: Context): String? = when (Build.MANUFACTURER.lowercase()) {
        "samsung" -> R.string.battery_samsung
        "xiaomi", "redmi", "poco" -> R.string.battery_xiaomi
        "oppo", "realme", "oneplus" -> R.string.battery_oppo
        else -> null
    }?.let(context::getString)

    /**
     * Fabricantes que, además del ahorro de batería, bloquean que una app se active sola (al encender
     * el móvil o tras actualizarla) si no tiene permiso de "Inicio automático". null si no hace falta.
     */
    fun autostartSteps(context: Context): String? = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> R.string.autostart_xiaomi
        "huawei", "honor" -> R.string.autostart_huawei
        "oppo", "realme", "oneplus" -> R.string.autostart_oppo
        "vivo", "iqoo" -> R.string.autostart_vivo
        else -> null
    }?.let(context::getString)

    /** Abre la pantalla de inicio automático del fabricante, o la ficha de la app si no se puede. */
    fun openAutostart(context: Context) {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        )
        for ((pkg, cls) in candidates) {
            val intent = Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
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

/**
 * Aviso de "Inicio automático" para los fabricantes que lo exigen. Va aparte del de batería, que
 * desaparece en cuanto se quita la optimización: este paso es distinto y hay que verlo igualmente.
 */
@Composable
fun AutostartCard() {
    val context = LocalContext.current
    val steps = remember { BatteryGuide.autostartSteps(context) } ?: return
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var dismissed by remember { mutableStateOf(prefs.getBoolean("autostart_guide_dismissed", false)) }
    if (dismissed) return

    SectionCard(stringResource(R.string.autostart_card_title)) {
        Text(
            stringResource(R.string.autostart_card_text),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(steps, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { BatteryGuide.openAutostart(context) }) { Text(stringResource(R.string.common_open_settings)) }
            TextButton(onClick = {
                dismissed = true
                prefs.edit().putBoolean("autostart_guide_dismissed", true).apply()
            }) { Text(stringResource(R.string.common_done_it)) }
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

    SectionCard(stringResource(R.string.battery_card_title)) {
        Text(
            stringResource(R.string.battery_card_text),
            style = MaterialTheme.typography.bodyMedium,
        )
        BatteryGuide.manufacturerSteps(context)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { BatteryGuide.openSettings(context) }) { Text(stringResource(R.string.common_open_settings)) }
            TextButton(onClick = {
                dismissed = true
                prefs.edit().putBoolean("battery_guide_dismissed", true).apply()
            }) { Text(stringResource(R.string.common_done_it)) }
        }
    }
}
