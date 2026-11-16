package app.bloqueador.diag

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.bloqueador.BloqueadorApp
import app.bloqueador.BuildConfig
import app.bloqueador.update.DownloadPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Envía una vez al día los errores anotados por [ErrorReports], solo si el usuario los aceptó y con la
 * red que permite [DownloadPolicy]. Si no hay nada pendiente no se conecta a nada.
 */
class ErrorReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BloqueadorApp
        if (app.settings.errorReports != true) {
            ErrorReports.clear(applicationContext)
            return Result.success()
        }
        val payload = ErrorReports.payload(applicationContext) ?: return Result.success()
        return try {
            val code = withContext(Dispatchers.IO) { post(payload.toString()) }
            when {
                code in 200..299 -> { ErrorReports.clear(applicationContext); Result.success() }
                code == 413 || code == 400 -> { ErrorReports.clear(applicationContext); Result.success() } // no se aceptará nunca
                else -> Result.retry()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "No se pudo enviar el informe: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    private fun post(body: String): Int {
        val conn = URL(BuildConfig.ERROR_URL).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("User-Agent", "Garbillo-Android")
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "ErrorReportWorker"
        private const val WORK_NAME = "error-reports"

        /** Programa el envío diario si el usuario aceptó los informes; si no, lo cancela. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (BloqueadorApp.instance.settings.errorReports != true) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<ErrorReportWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(DownloadPolicy.networkType())
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(6, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
