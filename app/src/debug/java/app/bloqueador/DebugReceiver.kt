package app.bloqueador

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.bloqueador.update.ListUpdateWorker

/**
 * Solo depuración. Ejecuta ahora la actualización de listas, igual que la tarea periódica:
 *   adb shell am broadcast -a app.bloqueador.debug.RUN_UPDATE -p app.bloqueador
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<ListUpdateWorker>().build())
    }
}
