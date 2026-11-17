package app.bloqueador.vpn

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
import app.bloqueador.R
import app.bloqueador.diag.ErrorReports
import app.bloqueador.ui.MainActivity

/**
 * Al encender el móvil, vuelve a activar el filtro si el usuario lo tenía encendido.
 *
 * Android entrega BOOT_COMPLETED tras el primer desbloqueo, cuando los datos de la app ya se pueden
 * leer. Si "VPN siempre activa" está activada, Android arranca también el servicio por su cuenta: el
 * servicio ignora el segundo arranque. Si el permiso VPN ya no es de Garbillo (otra VPN lo tomó), no
 * se hace nada. Si Android no deja arrancarlo, se avisa con una notificación para activarlo con un toque.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as BloqueadorApp
        // Solo si el usuario lo dejó encendido y Garbillo sigue teniendo el permiso VPN.
        if (!app.settings.filterWanted || VpnService.prepare(context) != null) return
        runCatching { DnsVpnService.start(context) }
            .onFailure {
                Log.w(TAG, "No se pudo activar el filtro al encender el móvil", it)
                ErrorReports.record(context, "arranque_bloqueado", it)
                notifyNotStarted(context)
            }
    }

    private fun notifyNotStarted(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_ch_filter_alerts), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_garbillo)
            .setContentTitle(context.getString(R.string.notif_boot_title))
            .setContentText(context.getString(R.string.notif_boot_text))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 6, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Sin permiso de notificaciones: no es grave.
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val CHANNEL_ID = "filter_alerts"
        private const val NOTIFICATION_ID = 6
    }
}
