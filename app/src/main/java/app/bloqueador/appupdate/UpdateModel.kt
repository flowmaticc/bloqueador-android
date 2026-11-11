package app.bloqueador.appupdate

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Canal de actualizaciones de la app (como en navegadores y sistemas operativos):
 * cada canal incluye también todo lo de los canales más estables.
 */
enum class UpdateChannel(val label: String, val description: String) {
    STABLE("Estable", "Solo versiones finales, ya probadas."),
    BETA("Beta", "Versiones de prueba antes de ser estables. Pueden tener algún fallo."),
    NIGHTLY("Nightly", "Cada cambio nuevo en cuanto se hace. Para probar lo último; puede fallar.");

    /** ¿Acepta este canal una publicación del canal [other]? */
    fun accepts(other: UpdateChannel): Boolean = other.ordinal <= ordinal
}

/** Publicación de GitHub Releases, con lo que necesita el actualizador. */
data class Release(
    val tag: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val publishedAt: Instant,
    val notes: String,
    /** Nombre del fichero adjunto → URL de descarga directa. */
    val assets: Map<String, String>,
) {
    /** `nightly` → Nightly; pre-release → Beta; el resto → Estable. */
    val channel: UpdateChannel
        get() = when {
            tag == NIGHTLY_TAG -> UpdateChannel.NIGHTLY
            prerelease -> UpdateChannel.BETA
            else -> UpdateChannel.STABLE
        }

    val manifestUrl: String? get() = assets[MANIFEST_NAME]

    companion object {
        const val NIGHTLY_TAG = "nightly"
        const val MANIFEST_NAME = "update.json"
    }
}

/** Contenido de `update.json`, que GitHub Actions adjunta a cada publicación. */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val channel: UpdateChannel,
    val notes: String,
)

object UpdateParser {

    fun parseReleases(json: String): List<Release> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val assets = o.optJSONArray("assets") ?: JSONArray()
            Release(
                tag = o.getString("tag_name"),
                prerelease = o.optBoolean("prerelease"),
                draft = o.optBoolean("draft"),
                publishedAt = o.optString("published_at").takeIf { it.isNotEmpty() && it != "null" }
                    ?.let(Instant::parse) ?: Instant.EPOCH,
                notes = o.optString("body").takeIf { it != "null" }.orEmpty(),
                assets = (0 until assets.length()).associate { j ->
                    val a = assets.getJSONObject(j)
                    a.getString("name") to a.getString("browser_download_url")
                },
            )
        }
    }

    /**
     * La publicación más reciente que acepta [channel]. No se compara por versión: si alguien pasa
     * de Nightly a Estable, la estable más reciente puede ser "más antigua" y simplemente no se ofrece
     * (Android no permite instalar una versión inferior sin desinstalar).
     */
    fun pick(releases: List<Release>, channel: UpdateChannel): Release? =
        releases.filter { !it.draft && it.manifestUrl != null && channel.accepts(it.channel) }
            .maxByOrNull { it.publishedAt }

    fun parseManifest(json: String, release: Release): UpdateInfo {
        val o = JSONObject(json)
        return UpdateInfo(
            versionCode = o.getLong("versionCode"),
            versionName = o.getString("versionName"),
            apkUrl = release.assets[o.getString("apk")] ?: error("Falta el APK ${o.getString("apk")}"),
            sha256 = o.getString("sha256").lowercase(),
            channel = release.channel,
            notes = release.notes,
        )
    }
}
