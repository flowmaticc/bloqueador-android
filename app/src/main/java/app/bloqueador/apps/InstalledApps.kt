package app.bloqueador.apps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val label: String,
    /** App del sistema sin icono en el cajón de apps: se oculta salvo que el usuario pida verlas. */
    val hiddenSystem: Boolean,
    val sensitive: Boolean,
)

object InstalledApps {

    /** Apps instaladas con permiso de Internet (las únicas a las que afecta el filtro), sin la propia. */
    suspend fun load(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        // Una sola consulta para saber qué apps tienen icono en el cajón (en vez de una por app).
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapTo(HashSet()) { it.activityInfo.packageName }

        // Una sola consulta con los permisos pedidos por cada app (en vez de checkPermission por app).
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

        packages.asSequence()
            .filter { it.packageName != context.packageName }
            .filter { pkg -> pkg.requestedPermissions?.contains(Manifest.permission.INTERNET) == true }
            .mapNotNull { pkg ->
                val info = pkg.applicationInfo ?: return@mapNotNull null
                val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
                val visible = pkg.packageName in launchable
                // Las apps ocultas del sistema no se muestran por defecto: no cargamos su nombre
                // real hasta que haga falta (es lo más lento con cientos de apps).
                AppEntry(
                    packageName = pkg.packageName,
                    label = if (system && !visible) pkg.packageName else pm.getApplicationLabel(info).toString(),
                    hiddenSystem = system && !visible,
                    sensitive = SensitiveApps.isSensitive(pkg.packageName),
                )
            }
            .sortedWith(compareBy<AppEntry> { it.hiddenSystem }.thenBy { it.label.lowercase() })
            .toList()
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
