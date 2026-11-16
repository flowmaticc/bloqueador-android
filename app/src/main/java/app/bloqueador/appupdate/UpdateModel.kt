package app.bloqueador.appupdate

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Canal de actualizaciones de la app. Beta incluye también las estables: siempre se ofrece la más
 * nueva de las dos. (Hasta la 0.3.x hubo un tercer canal, Nightly, que ahora es Beta.)
 */
enum class UpdateChannel(val label: String, val description: String) {
    STABLE("Estable", "Solo versiones finales, ya probadas."),
    BETA("Beta", "Cada cambio nuevo en cuanto se hace, antes de ser estable. Para probar lo último; puede fallar.");

    /** ¿Acepta este canal una publicación del canal [other]? */
    fun accepts(other: UpdateChannel): Boolean = other.ordinal <= ordinal

    companion object {
        /** Lee el canal guardado. "NIGHTLY" (versiones anteriores) pasa a Beta. */
        fun fromSaved(name: String?): UpdateChannel = when (name) {
            "NIGHTLY" -> BETA
            else -> entries.firstOrNull { it.name == name } ?: STABLE
        }

        /** Canal escrito en update.json ("stable", "beta"; "nightly" antiguo cuenta como beta). */
        fun fromManifest(value: String?): UpdateChannel = if (value == "stable") STABLE else BETA
    }
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
    /** Pre-release (la beta continua, o la antigua `nightly`) → Beta; el resto → Estable. */
    val channel: UpdateChannel
        get() = if (prerelease) UpdateChannel.BETA else UpdateChannel.STABLE

    val manifestUrl: String? get() = assets[MANIFEST_NAME]

    companion object {
        const val BETA_TAG = "beta"
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
    /** Aviso opcional que se muestra junto a la actualización (p. ej. "corrige un fallo grave"). */
    val notice: String? = null,
    /** Las versiones por debajo de esta tienen un fallo de seguridad: se avisa con más insistencia. */
    val minSafeVersionCode: Long = 0,
) {
    fun isUrgentFor(installed: Long): Boolean = installed < minSafeVersionCode
}

/** Dónde están los update.json de cada canal (ficheros estáticos: no cuentan contra la API de GitHub). */
object UpdateUrls {
    fun stableManifest(base: String, repo: String) = "$base/$repo/releases/latest/download/${Release.MANIFEST_NAME}"
    fun betaManifest(base: String, repo: String) = "$base/$repo/releases/download/${Release.BETA_TAG}/${Release.MANIFEST_NAME}"
    fun asset(base: String, repo: String, tag: String, name: String) = "$base/$repo/releases/download/$tag/$name"
}

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
                notes = plainText(o.optString("body").takeIf { it != "null" }.orEmpty()),
                assets = (0 until assets.length()).associate { j ->
                    val a = assets.getJSONObject(j)
                    a.getString("name") to a.getString("browser_download_url")
                },
            )
        }
    }

    /**
     * Notas de GitHub (Markdown) a texto llano: sin `**`, `#`, ni enlaces en formato Markdown. Las
     * líneas sangradas continúan la anterior (en el Markdown se cortan solo para leerlo cómodo), así
     * el texto ocupa el ancho de la pantalla sin saltos a mitad de frase.
     */
    internal fun plainText(md: String): String {
        val out = mutableListOf<String>()
        for (raw in md.lines()) {
            val continuation = raw.isNotBlank() && raw.first().isWhitespace() && out.lastOrNull()?.isNotEmpty() == true
            val line = raw.trim().trimStart('#', ' ')
                .replace("**", "")
                .replace(Regex("""\[([^]]*)]\(([^)]*)\)"""), "$1")
                .replace(Regex("^[*-] "), "• ")
            if (continuation) out[out.lastIndex] = out.last() + " " + line else out += line
        }
        return out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /**
     * La publicación más reciente que acepta [channel]. No se compara por versión: si alguien pasa
     * de Nightly a Estable, la estable más reciente puede ser "más antigua" y simplemente no se ofrece
     * (Android no permite instalar una versión inferior sin desinstalar).
     */
    fun pick(releases: List<Release>, channel: UpdateChannel): Release? =
        releases.filter { !it.draft && it.manifestUrl != null && channel.accepts(it.channel) }
            .maxByOrNull { it.publishedAt }

    /** update.json leído a través de la API (respaldo): el APK tiene que estar en esa publicación. */
    fun parseManifest(json: String, release: Release): UpdateInfo {
        val o = JSONObject(json)
        return UpdateInfo(
            versionCode = o.getLong("versionCode"),
            versionName = o.getString("versionName"),
            apkUrl = release.assets[o.getString("apk")] ?: error("Falta el APK ${o.getString("apk")}"),
            sha256 = o.getString("sha256").lowercase(),
            channel = release.channel,
            notes = o.optString("notes").takeIf { it.isNotBlank() }?.let(::plainText) ?: release.notes,
            notice = notice(o),
            minSafeVersionCode = o.optLong("minSafeVersionCode", 0),
        )
    }

    private val SAFE_NAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,120}$""")

    /**
     * update.json estático (lo normal). El APK se busca en la publicación que dice `tag`, con un nombre
     * sin barras ni "..": un manifiesto manipulado no puede apuntar a otro sitio.
     */
    fun parseStaticManifest(json: String, base: String, repo: String): UpdateInfo {
        val o = JSONObject(json)
        val tag = o.getString("tag")
        val apk = o.getString("apk")
        check(SAFE_NAME.matches(tag) && ".." !in tag) { "Etiqueta no válida: $tag" }
        check(SAFE_NAME.matches(apk) && ".." !in apk && apk.endsWith(".apk")) { "Nombre de APK no válido: $apk" }
        return UpdateInfo(
            versionCode = o.getLong("versionCode"),
            versionName = o.getString("versionName"),
            apkUrl = UpdateUrls.asset(base, repo, tag, apk),
            sha256 = o.getString("sha256").lowercase(),
            channel = UpdateChannel.fromManifest(o.optString("channel")),
            notes = plainText(o.optString("notes")),
            notice = notice(o),
            minSafeVersionCode = o.optLong("minSafeVersionCode", 0),
        )
    }

    private fun notice(o: JSONObject): String? =
        o.optString("notice").takeIf { it.isNotBlank() && it != "null" }?.take(500)

    /** De las candidatas que se pudieron leer, la de versión más alta (null si ninguna). */
    fun newest(candidates: List<UpdateInfo>): UpdateInfo? = candidates.maxByOrNull { it.versionCode }
}
