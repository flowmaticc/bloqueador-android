package app.bloqueador.vpn

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.bloqueador.BloqueadorApp
import app.bloqueador.R

/**
 * "Bloquear conexiones sin VPN" (Android 8+) con Garbillo como VPN siempre activa deja el móvil SIN
 * INTERNET: Android solo deja salir lo que pasa por el túnel, y el túnel de Garbillo solo lleva el DNS.
 * Comprobado en un Samsung A7 con Android 10 (ERR_NETWORK_ACCESS_DENIED en Chrome).
 *
 * No hay forma de que funcione con esa opción, así que se avisa con una notificación (el usuario puede
 * no abrir la app y no entender por qué no tiene internet) y en la pantalla principal.
 */
object LockdownWarning {
    private const val CHANNEL_ID = "filter_alerts" // el mismo canal que los demás avisos del filtro
    private const val NOTIFICATION_ID = 7

    /** Guarda el estado y muestra o quita la notificación solo si ha cambiado. */
    fun update(context: Context, lockdown: Boolean) {
        val settings = (context.applicationContext as BloqueadorApp).settings
        if (settings.vpnLockdown == lockdown) return
        settings.vpnLockdown = lockdown
        if (lockdown) notify(context) else NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun openVpnSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun notify(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Avisos del filtro", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val text = "Está activado \"Bloquear conexiones sin VPN\" y con Garbillo eso corta todo internet. " +
            "Toca, entra en el engranaje de Garbillo y desactiva esa opción (deja \"VPN siempre activa\")."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_garbillo)
            .setContentTitle("Sin internet por un ajuste de Android")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true) // se quita sola al desactivar la opción
            .setContentIntent(
                PendingIntent.getActivity(
                    context, NOTIFICATION_ID, Intent(Settings.ACTION_VPN_SETTINGS), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Sin permiso de notificaciones: queda el aviso de la pantalla principal.
        }
    }
}
