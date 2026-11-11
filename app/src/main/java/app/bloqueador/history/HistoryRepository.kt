package app.bloqueador.history

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.OsConstants
import app.bloqueador.lists.BlocklistRepository
import app.bloqueador.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Acceso al historial: escritura por lotes, limpieza por antigüedad y consultas para la UI.
 * La limpieza se hace al escribir y al abrir el historial, sin tareas periódicas propias
 * (no despierta el móvil para nada).
 */
class HistoryRepository(private val context: Context, private val settings: AppSettings) {

    private val db by lazy { HistoryDatabase.create(context) }
    private val dao get() = db.dao()

    suspend fun write(events: List<HistoryEvent>) {
        if (!settings.historyEnabled) return
        dao.insertAll(events)
        cleanup()
    }

    /** Borra lo que supera el tiempo de conservación. */
    suspend fun cleanup() {
        val limit = System.currentTimeMillis() - settings.historyRetention.millis
        dao.deleteOlderThan(limit)
        withContext(Dispatchers.IO) {
            java.io.File(context.cacheDir, "thumbs").listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
        }
    }

    suspend fun clear() {
        dao.deleteAll()
        withContext(Dispatchers.IO) { java.io.File(context.cacheDir, "thumbs").listFiles()?.forEach { it.delete() } }
    }

    fun observe(filter: HistoryFilter): Flow<List<HistoryEvent>> {
        val code = when (filter) {
            HistoryFilter.ALL -> 0
            HistoryFilter.BLOCKED -> 1
            HistoryFilter.ALLOWED -> 2
            HistoryFilter.VISUAL -> 3
        }
        return dao.observe(System.currentTimeMillis() - settings.historyRetention.millis, code, MAX_ROWS)
    }

    companion object {
        const val MAX_ROWS = 1000
    }
}

/**
 * Averigua qué app hizo una consulta DNS (Android 10+). En Android el resolvedor del sistema
 * atribuye cada socket DNS a la app que pidió la resolución, así que el dueño del socket es la app.
 */
class AppResolver(private val context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val packageByUid = ConcurrentHashMap<Int, String>()

    /** Paquete dueño de la conexión UDP origen→destino, o null si no se puede saber. */
    fun ownerPackage(source: ByteArray, sourcePort: Int, destination: ByteArray, destinationPort: Int): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val uid = try {
            cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(source), sourcePort),
                InetSocketAddress(InetAddress.getByAddress(destination), destinationPort),
            )
        } catch (e: Exception) {
            // SecurityException si dejamos de ser la VPN activa; IllegalArgumentException si no aplica.
            return null
        }
        if (uid == Process.INVALID_UID) return null
        return packageForUid(uid)
    }

    private fun packageForUid(uid: Int): String? {
        packageByUid[uid]?.let { return it }
        val pkg = when (uid) {
            Process.ROOT_UID, Process.SYSTEM_UID -> SYSTEM_PACKAGE
            else -> context.packageManager.getPackagesForUid(uid)?.minOrNull()
        } ?: return null
        packageByUid[uid] = pkg
        return pkg
    }

    companion object {
        /** Marca para consultas del propio sistema (no de una app concreta). */
        const val SYSTEM_PACKAGE = "android"
    }
}

/** Dónde está una regla: en qué listas activas o si es del usuario (para "Ver detalle"). */
object RuleOrigin {

    suspend fun find(rule: String, settings: AppSettings, repository: BlocklistRepository, context: Context): List<String> =
        withContext(Dispatchers.IO) {
            val origins = mutableListOf<String>()
            val domain = rule.removePrefix("||").removeSuffix("^")
            if (rule.startsWith("||") && domain in settings.blockedDomains) origins += "Tus bloqueos propios"
            if (domain == "use-application-dns.net") origins += "Regla interna (desactiva el DNS cifrado de Firefox)"
            for (source in repository.activeSources()) {
                val file = java.io.File(context.filesDir, "lists/${source.id}.txt")
                if (!file.isFile) continue
                val found = file.bufferedReader().useLines { lines ->
                    lines.any { line ->
                        val l = line.trim()
                        l == rule || (!rule.startsWith("||") && l.endsWith(" $rule")) ||
                            (!rule.startsWith("||") && l == rule)
                    }
                }
                if (found) origins += source.label
            }
            origins
        }
}
