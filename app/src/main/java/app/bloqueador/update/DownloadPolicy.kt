package app.bloqueador.update

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.NetworkType
import app.bloqueador.BloqueadorApp
import app.bloqueador.appupdate.AppUpdateWorker
import app.bloqueador.diag.ErrorReportWorker

/**
 * Cuándo se descarga en segundo plano: con "Descargar solo con wifi" (por defecto), las tareas
 * automáticas esperan a una red sin límite de datos y Android las lanza en cuanto el móvil se conecta
 * a una wifi. Lo que el usuario pide a mano se descarga siempre.
 */
object DownloadPolicy {

    /** true si la red actual tiene límite de datos (datos móviles o una wifi marcada como medida). */
    fun isMetered(context: Context): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    /** ¿Puede descargar ahora una tarea automática? */
    fun automaticAllowed(context: Context): Boolean =
        !BloqueadorApp.instance.settings.downloadsWifiOnly || !isMetered(context)

    /** Red que exigen las tareas en segundo plano. */
    fun networkType(): NetworkType =
        if (BloqueadorApp.instance.settings.downloadsWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

    /** Vuelve a programar todas las tareas de fondo con la preferencia actual. */
    fun reschedule(context: Context) {
        ListUpdateScheduler.schedule(context, BloqueadorApp.instance.settings.updateFrequency)
        AppUpdateWorker.schedule(context)
        ErrorReportWorker.schedule(context)
    }
}
