package app.bloqueador.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Una entrada del historial (sección 7). Varias consultas iguales (mismo dominio, app y resultado)
 * dentro de la misma ventana de escritura se agrupan en una fila con [count].
 */
@Entity(tableName = "dns_events", indices = [Index("timestamp")])
data class HistoryEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Momento de la primera consulta del grupo (ms epoch). */
    val timestamp: Long,
    val domain: String,
    /** Paquete de la app que hizo la consulta; null si Android no lo indica (< Android 10). */
    val appPackage: String?,
    val blocked: Boolean,
    /** "dns" (filtro DNS) o "accessibility" (bloqueo visual). */
    val source: String,
    /** Regla que provocó el bloqueo, p. ej. `||ads.com^`; null si se permitió. */
    val matchedRule: String?,
    val count: Int,
    /** Miniatura del elemento bloqueado (solo accesibilidad, Android 11+). */
    val thumbnailPath: String? = null,
)

/** Qué entradas mostrar en la lista. */
enum class HistoryFilter(val label: String) { BLOCKED("Bloqueadas"), ALLOWED("Permitidas"), ALL("Todas"), VISUAL("Visuales") }

@Dao
interface HistoryDao {
    @Insert
    suspend fun insertAll(events: List<HistoryEvent>)

    /** Entradas desde [since], de la más reciente a la más antigua. filter: 0 todas, 1 bloqueadas, 2 permitidas. */
    @Query(
        """
        SELECT * FROM dns_events
        WHERE timestamp >= :since
          AND (:filter = 0 OR (:filter = 1 AND blocked = 1) OR (:filter = 2 AND blocked = 0)
               OR (:filter = 3 AND source = 'accessibility'))
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    fun observe(since: Long, filter: Int, limit: Int): Flow<List<HistoryEvent>>

    @Query("DELETE FROM dns_events WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM dns_events")
    suspend fun deleteAll()
}

@Database(entities = [HistoryEvent::class], version = 2, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dao(): HistoryDao

    companion object {
        fun create(context: Context): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, "history.db")
                // El historial es efímero (TTL de horas): ante un cambio de esquema se recrea sin más.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
