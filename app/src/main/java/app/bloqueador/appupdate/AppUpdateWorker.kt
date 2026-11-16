package app.bloqueador.appupdate

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.bloqueador.BloqueadorApp
import app.bloqueador.R
import app.bloqueador.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Comprobación diaria de nuevas versiones de la app. Solo avisa (una vez por versión); instalar
 * siempre lo decide el usuario desde Ajustes → Actualizaciones de la app.
 */
class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BloqueadorApp
        val settings = app.settings
        val result = AppUpdater(applicationContext).check(settings.updateChannel)
        settings.lastAppUpdateCheck = System.currentTimeMillis()
        if (result is UpdateCheck.Available && result.info.versionCode != settings.notifiedVersionCode) {
            val urgent = result.info.isUrgentFor(AppUpdater(applicationContext).installedVersionCode)
            notify(applicationContext, result.info, urgent)
            settings.notifiedVersionCode = result.info.versionCode
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "app-update"
        private const val CHANNEL_ID = "app_updates"
        private const val URGENT_CHANNEL_ID = "app_updates_security"
        private const val NOTIFICATION_ID = 3
        const val EXTRA_OPEN_UPDATES = "open_updates"

        /** Programa la comprobación diaria con la red que pide [DownloadPolicy] (sin reiniciar su reloj). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(app.bloqueador.update.DownloadPolicy.networkType())
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(2, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        private fun notify(context: Context, info: UpdateInfo, urgent: Boolean) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Actualizaciones de la app", NotificationManager.IMPORTANCE_LOW)
            )
            // Las de seguridad van por un canal que sí suena (el usuario puede silenciarlo aparte).
            nm.createNotificationChannel(
                NotificationChannel(URGENT_CHANNEL_ID, "Actualizaciones de seguridad", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_UPDATES, true)
            val text = when {
                urgent -> "Corrige un fallo de seguridad de tu versión. Toca para instalarla."
                info.notice != null -> info.notice
                else -> "Toca para ver los cambios e instalarla."
            }
            val notification = NotificationCompat.Builder(context, if (urgent) URGENT_CHANNEL_ID else CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_garbillo)
                .setContentTitle(
                    if (urgent) "Actualización importante de Garbillo: ${info.versionName}"
                    else "Nueva versión de Garbillo: ${info.versionName}"
                )
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .build()
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                // Sin permiso de notificaciones: el usuario lo verá al abrir Ajustes.
            }
        }
    }
}
