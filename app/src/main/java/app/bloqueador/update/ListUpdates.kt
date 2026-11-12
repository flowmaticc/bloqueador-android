package app.bloqueador.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.bloqueador.BloqueadorApp
import app.bloqueador.R
import app.bloqueador.VpnState
import app.bloqueador.settings.UpdateFrequency
import app.bloqueador.ui.MainActivity
import java.util.concurrent.TimeUnit

/** Programación de la comprobación periódica de listas. */
object ListUpdateScheduler {
    private const val WORK_NAME = "list-update"

    /**
     * Programa (o cancela, si es manual) la tarea periódica.
     * @param replace true cuando el usuario cambia la frecuencia: se aplica el nuevo intervalo.
     *   false al arrancar la app: se respeta la programación existente para no reiniciar el reloj.
     */
    fun schedule(context: Context, frequency: UpdateFrequency, replace: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (frequency.hours == 0L) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ListUpdateWorker>(frequency.hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            // Al estrenar la programación no se ejecuta enseguida: el servicio ya descarga lo que falte.
            .setInitialDelay(1, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(
            WORK_NAME,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

/**
 * Comprueba las listas en segundo plano (con `ETag`, así que casi siempre es un 304 sin descarga).
 * Si algo cambia y el filtro está activo, recarga el motor en caliente sin tocar el túnel.
 * Es silenciosa: solo avisa si falla [FAILURES_BEFORE_NOTICE] veces seguidas.
 */
class ListUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BloqueadorApp
        val result = app.repository.downloadAll()

        if (result.changed && app.vpnState.value is VpnState.Running) {
            try {
                app.reloadEngine()
                Log.i(TAG, "Listas cambiadas: motor recargado (${app.engine.matcher.size} reglas)")
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo recargar el motor", e)
            }
        }

        if (result.ok) {
            app.repository.recordCheck(result)
            UpdateNotifier.clear(applicationContext)
            return Result.success()
        }
        Log.w(TAG, "Fallo al actualizar: ${result.errors}")
        // Un par de reintentos cercanos (con espera creciente) por si era un corte puntual.
        if (runAttemptCount < MAX_RETRIES) return Result.retry()
        app.repository.recordCheck(result)
        if (shouldNotify(app.repository.consecutiveFailures)) {
            UpdateNotifier.notifyFailure(applicationContext)
        }
        // Se da por terminada: la siguiente comprobación será en el próximo periodo.
        return Result.success()
    }

    companion object {
        private const val TAG = "ListUpdateWorker"
        const val MAX_RETRIES = 2
        const val FAILURES_BEFORE_NOTICE = 3

        /** Se avisa al llegar a 3 periodos fallidos seguidos y luego solo cada 3 más, para no insistir. */
        fun shouldNotify(consecutiveFailures: Int): Boolean =
            consecutiveFailures >= FAILURES_BEFORE_NOTICE && consecutiveFailures % FAILURES_BEFORE_NOTICE == 0
    }
}

/** Aviso informativo cuando las listas llevan tiempo sin poder actualizarse. */
object UpdateNotifier {
    private const val CHANNEL_ID = "list_updates"
    private const val NOTIFICATION_ID = 2

    fun notifyFailure(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Actualización de listas", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("No se pudieron actualizar las listas")
            .setContentText("El filtro sigue funcionando con las listas que ya tienes.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "El filtro sigue funcionando con las listas que ya tienes. " +
                        "Se volverá a intentar automáticamente; también puedes pulsar \"Actualizar ahora\"."
                )
            )
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permiso de notificaciones retirado entre la comprobación y el aviso: no es grave.
        }
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
