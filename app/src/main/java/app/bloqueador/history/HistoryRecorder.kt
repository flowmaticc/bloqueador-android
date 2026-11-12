package app.bloqueador.history

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Una consulta tal como la ve el filtro, antes de agruparla. */
data class DnsRecord(
    val timestamp: Long,
    val domain: String,
    val appPackage: String?,
    val blocked: Boolean,
    val rule: String?,
)

/**
 * Agrupa consultas iguales y las escribe por lotes (para ahorrar batería: no escribir en disco en cada
 * consulta). La primera consulta de un lote programa una escritura a [flushDelayMs]; sin consultas
 * no hay temporizadores ni escrituras.
 */
class HistoryRecorder(
    private val scope: CoroutineScope,
    private val flushDelayMs: Long = 5_000,
    private val write: suspend (List<HistoryEvent>) -> Unit,
) {
    private val queue = ConcurrentLinkedQueue<DnsRecord>()
    private val scheduled = AtomicBoolean(false)

    fun record(r: DnsRecord) {
        queue.add(r)
        if (scheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(flushDelayMs)
                flush()
            }
        }
    }

    /** Escribe lo pendiente ya (al parar el filtro). */
    suspend fun flush() {
        scheduled.set(false)
        val batch = ArrayList<DnsRecord>()
        while (true) batch.add(queue.poll() ?: break)
        if (batch.isEmpty()) return
        try {
            write(aggregate(batch))
        } catch (e: Exception) {
            Log.w("HistoryRecorder", "No se pudo guardar el historial", e)
        }
    }

    companion object {
        /** Agrupa por (dominio, app, resultado); conserva la hora de la primera y la regla. */
        fun aggregate(records: List<DnsRecord>): List<HistoryEvent> =
            records.groupBy { Triple(it.domain, it.appPackage, it.blocked) }
                .map { (key, group) ->
                    HistoryEvent(
                        timestamp = group.minOf { it.timestamp },
                        domain = key.first,
                        appPackage = key.second,
                        blocked = key.third,
                        source = "dns",
                        matchedRule = group.firstNotNullOfOrNull { it.rule },
                        count = group.size,
                    )
                }
                .sortedBy { it.timestamp }
    }
}
