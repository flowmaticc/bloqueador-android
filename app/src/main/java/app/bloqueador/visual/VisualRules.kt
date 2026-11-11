package app.bloqueador.visual

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Qué hacer con un elemento marcado (sección 9). */
enum class VisualAction(val label: String, val description: String) {
    HIDE("Ocultar", "Se tapa con un recuadro. No se puede pulsar lo que hay debajo."),
    CLICK("Pulsar", "Se pulsa automáticamente (útil para botones \"Cerrar\" o \"Saltar\")."),
}

/** Atributos de un elemento de pantalla tal como los da la accesibilidad. */
data class NodeSnapshot(
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
)

/**
 * Elemento que el usuario marcó en una app. Se reconoce por su `resource-id` si lo tiene (lo más
 * estable); si no, por su descripción o su texto exactos.
 */
data class VisualRule(
    val id: String,
    val packageName: String,
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val action: VisualAction,
    val createdAt: Long,
) {
    /** Qué atributo identifica el elemento, en orden de preferencia. */
    val key: Key
        get() = when {
            !viewId.isNullOrEmpty() -> Key.VIEW_ID
            !contentDescription.isNullOrEmpty() -> Key.DESCRIPTION
            else -> Key.TEXT
        }

    enum class Key { VIEW_ID, DESCRIPTION, TEXT }

    fun matches(node: NodeSnapshot): Boolean {
        if (className != null && node.className != null && className != node.className) return false
        return when (key) {
            Key.VIEW_ID -> node.viewId == viewId
            Key.DESCRIPTION -> node.contentDescription == contentDescription
            Key.TEXT -> !text.isNullOrEmpty() && node.text == text
        }
    }

    /** Descripción legible para listas e historial. */
    val label: String
        get() = when (key) {
            Key.VIEW_ID -> "Elemento " + viewId!!.substringAfter(":id/")
            Key.DESCRIPTION -> "\"$contentDescription\""
            Key.TEXT -> "\"$text\""
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("package", packageName)
        .put("viewId", viewId).put("text", text).put("desc", contentDescription)
        .put("class", className).put("action", action.name).put("createdAt", createdAt)

    companion object {
        /** Un elemento solo se puede marcar si tiene algo con lo que reconocerlo después. */
        fun canIdentify(node: NodeSnapshot): Boolean =
            !node.viewId.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty() || !node.text.isNullOrEmpty()

        fun from(packageName: String, node: NodeSnapshot, action: VisualAction, now: Long = System.currentTimeMillis()) =
            VisualRule(
                id = UUID.randomUUID().toString(),
                packageName = packageName,
                viewId = node.viewId?.takeIf { it.isNotEmpty() },
                // Solo se guarda el texto si es lo que identifica al elemento (puede ser privado).
                text = if (node.viewId.isNullOrEmpty() && node.contentDescription.isNullOrEmpty()) node.text else null,
                contentDescription = if (node.viewId.isNullOrEmpty()) node.contentDescription else null,
                className = node.className,
                action = action,
                createdAt = now,
            )

        fun fromJson(o: JSONObject) = VisualRule(
            id = o.getString("id"),
            packageName = o.getString("package"),
            viewId = o.optString("viewId").takeIf { it.isNotEmpty() && it != "null" },
            text = o.optString("text").takeIf { it.isNotEmpty() && it != "null" },
            contentDescription = o.optString("desc").takeIf { it.isNotEmpty() && it != "null" },
            className = o.optString("class").takeIf { it.isNotEmpty() && it != "null" },
            action = VisualAction.entries.firstOrNull { it.name == o.optString("action") } ?: VisualAction.HIDE,
            createdAt = o.optLong("createdAt"),
        )
    }
}

/** Estado completo del bloqueo visual: reglas y apps en pausa. Se guarda en un JSON pequeño. */
data class VisualState(val rules: List<VisualRule>, val pausedApps: Set<String>) {
    /** Apps con al menos una regla y sin pausar: las únicas que el servicio vigila. */
    val activeApps: Set<String> get() = rules.map { it.packageName }.toSet() - pausedApps

    fun rulesFor(pkg: String) = if (pkg in pausedApps) emptyList() else rules.filter { it.packageName == pkg }
}

class VisualRuleStore(context: Context) {
    private val file = File(context.filesDir, "visual_rules.json")
    private val _state = MutableStateFlow(read())
    val state: StateFlow<VisualState> = _state

    fun add(rule: VisualRule) = update { it.copy(rules = it.rules + rule) }

    fun remove(id: String) = update { it.copy(rules = it.rules.filterNot { r -> r.id == id }) }

    fun setPaused(pkg: String, paused: Boolean) =
        update { it.copy(pausedApps = if (paused) it.pausedApps + pkg else it.pausedApps - pkg) }

    fun removeApp(pkg: String) =
        update { it.copy(rules = it.rules.filterNot { r -> r.packageName == pkg }, pausedApps = it.pausedApps - pkg) }

    @Synchronized
    private fun update(change: (VisualState) -> VisualState) {
        val next = change(_state.value)
        val json = JSONObject()
            .put("rules", JSONArray().apply { next.rules.forEach { put(it.toJson()) } })
            .put("paused", JSONArray(next.pausedApps.toList()))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete(); tmp.renameTo(file)
        }
        _state.value = next
    }

    private fun read(): VisualState = try {
        val o = JSONObject(file.readText())
        val arr = o.optJSONArray("rules") ?: JSONArray()
        val paused = o.optJSONArray("paused") ?: JSONArray()
        VisualState(
            rules = (0 until arr.length()).map { VisualRule.fromJson(arr.getJSONObject(it)) },
            pausedApps = (0 until paused.length()).map { paused.getString(it) }.toSet(),
        )
    } catch (e: Exception) {
        VisualState(emptyList(), emptySet())
    }
}
