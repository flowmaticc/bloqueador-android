package app.bloqueador.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.bloqueador.BloqueadorApp
import app.bloqueador.R
import app.bloqueador.ui.MainActivity
import app.bloqueador.ui.appLabel
import java.util.concurrent.ConcurrentHashMap

/**
 * Avisa cuando se bloquea un sitio peligroso. Un bloqueo por DNS es silencioso (la web "no carga"),
 * así que sin aviso el usuario no sabría que se ha evitado un phishing o una descarga de malware.
 *
 * Para no molestar: como mucho un aviso por dominio cada [PER_DOMAIN_MS] y uno cada [GLOBAL_MS].
 */
class ThreatAlerts(private val context: Context) {

    private val lastByDomain = ConcurrentHashMap<String, Long>()
    @Volatile private var lastAny = 0L

    fun onThreat(domain: String, appPackage: String?) {
        if (!BloqueadorApp.instance.settings.threatAlerts) return
        val now = System.currentTimeMillis()
        val last = lastByDomain[domain] ?: 0L
        if (now - last < PER_DOMAIN_MS || now - lastAny < GLOBAL_MS) return
        lastByDomain[domain] = now
        lastAny = now
        if (lastByDomain.size > 500) lastByDomain.entries.removeIf { now - it.value > PER_DOMAIN_MS }

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_ch_threats), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.notif_ch_threats_desc) }
        )
        val app = appLabel(context, appPackage)
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_THREATS, true)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_garbillo)
            .setContentTitle(context.getString(R.string.notif_threat_title))
            .setContentText(context.getString(R.string.notif_threat_short, domain, app))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.notif_threat_text, domain, app),
                ),
            )
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            // En la pantalla de bloqueo, sin dominio ni app: quien tenga el móvil delante no ve qué se visitaba.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_garbillo)
                    .setContentTitle(context.getString(R.string.notif_threat_title))
                    .setContentText(context.getString(R.string.notif_threat_locked))
                    .build()
            )
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) } // sin permiso de notificaciones: no pasa nada
    }

    private companion object {
        const val CHANNEL_ID = "amenazas"
        const val NOTIFICATION_ID = 4001
        const val PER_DOMAIN_MS = 6 * 60 * 60 * 1000L
        const val GLOBAL_MS = 60 * 1000L
    }
}
