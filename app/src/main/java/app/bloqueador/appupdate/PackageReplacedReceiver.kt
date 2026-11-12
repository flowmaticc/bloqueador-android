package app.bloqueador.appupdate

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.bloqueador.BloqueadorApp
import app.bloqueador.BuildConfig
import app.bloqueador.R
import app.bloqueador.ui.MainActivity
import app.bloqueador.vpn.DnsVpnService

/**
 * Android cierra la app al actualizarla y no la vuelve a abrir. Aquí se reactiva el filtro si el
 * usuario lo tenía encendido (MY_PACKAGE_REPLACED permite iniciar el servicio en primer plano) y se
 * avisa de la nueva versión.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as BloqueadorApp
        if (app.settings.filterWanted && VpnService.prepare(context) == null) {
            runCatching { DnsVpnService.start(context) }
                .onFailure { Log.w("PackageReplaced", "No se pudo reactivar el filtro", it) }
        }
        notifyUpdated(context)
    }

    private fun notifyUpdated(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Actualizaciones de la app", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_garbillo)
            .setContentTitle("Garbillo actualizado a la versión ${BuildConfig.VERSION_NAME}")
            .setContentText("Todo sigue como estaba.")
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 4, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Sin permiso de notificaciones: no es grave.
        }
    }

    private companion object {
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 5
    }
}
