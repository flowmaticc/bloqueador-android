package app.bloqueador.history

import kotlin.math.abs

/**
 * Adivina en qué web estaba el usuario cuando se consultó un dominio, a partir de las otras
 * consultas permitidas de la misma app en esos segundos. El filtro DNS solo ve nombres de dominio,
 * nunca la página: esto es una estimación y el usuario elige entre las candidatas.
 */
object SiteGuess {

    /** Sufijos de dos niveles habituales (no es la Public Suffix List completa, ni hace falta). */
    private val TWO_LEVEL_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.es", "org.es", "gob.es", "nom.es", "edu.es",
        "com.ar", "com.br", "com.mx", "com.co", "com.pe", "com.tr", "com.cn", "com.au", "net.au",
        "co.jp", "co.nz", "co.in", "co.za",
    )

    /** Dominios de infraestructura, anuncios o servicios comunes: nunca son "la web que visitabas". */
    private val NOT_A_SITE = setOf(
        "gstatic.com", "googleapis.com", "googleusercontent.com", "ggpht.com", "gvt1.com", "gvt2.com",
        "googlevideo.com", "ytimg.com", "app-measurement.com", "doubleclick.net", "googlesyndication.com",
        "googletagmanager.com", "googletagservices.com", "google-analytics.com", "googleadservices.com",
        "cloudfront.net", "akamaized.net", "akamaihd.net", "akamaiedge.net", "edgekey.net", "edgesuite.net",
        "fastly.net", "fastly-edge.com", "jsdelivr.net", "cloudflare.com", "cloudflare.net",
        "cloudflareinsights.com", "unpkg.com", "bootstrapcdn.com", "fbcdn.net", "twimg.com",
        "amazonaws.com", "azureedge.net", "windows.net", "sentry.io", "nr-data.net", "newrelic.com",
        "hotjar.com", "cookielaw.org", "onetrust.com", "consensu.org", "privacy-mgmt.com", "didomi.io",
        "quantcast.com", "trustarc.com", "usercentrics.eu", "recaptcha.net", "hcaptcha.com",
    )

    /** Primer nivel típico de servidores auxiliares (cdn., static., api.…): se prefieren otros. */
    private val AUX_LABEL = Regex("""^(cdn|static|img|images|media|assets|api|fonts|cache|edge|ads?|track(ing)?|analytics|stats|metrics|pixel|logs?|beacon|telemetry|push|ws|sync)[0-9-]*\.""")

    /** "a.b.marca.com" → "marca.com"; "x.bbc.co.uk" → "bbc.co.uk". */
    fun registrable(host: String): String {
        val labels = host.trimEnd('.').lowercase().split('.')
        if (labels.size <= 2) return labels.joinToString(".")
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in TWO_LEVEL_SUFFIXES) labels.takeLast(3).joinToString(".") else lastTwo
    }

    /**
     * Hasta [max] hosts candidatos (de más a menos probable) para el dominio [target] consultado en
     * [at]. [nearby] son consultas permitidas de la misma app alrededor de ese momento: (host, hora).
     */
    fun candidates(target: String, at: Long, nearby: List<Pair<String, Long>>, max: Int = 5): List<String> {
        val targetSite = registrable(target)
        return nearby
            .filter { (host, _) -> '.' in host && host != target }
            .groupBy { (host, _) -> registrable(host) }
            .filterKeys { it != targetSite && it !in NOT_A_SITE }
            .map { (site, entries) ->
                val hosts = entries.map { it.first }.toSet()
                val host = when {
                    "www.$site" in hosts -> "www.$site"
                    site in hosts -> site
                    else -> hosts.firstOrNull { !AUX_LABEL.containsMatchIn(it) } ?: site
                }
                // Lo consultado justo antes pesa más que lo de después; una portada (www.) más que un
                // servidor auxiliar.
                var score = entries.minOf { (_, t) -> if (t <= at) at - t else (t - at) * 3 }
                if (host == "www.$site" || host == site) score -= 20_000
                if (AUX_LABEL.containsMatchIn(host)) score += 30_000
                host to score
            }
            .sortedBy { it.second }
            .take(max)
            .map { it.first }
    }

    /** ¿Coincide [host] con [domain] o es un subdominio suyo? */
    fun matches(host: String, domain: String): Boolean =
        host.equals(domain, ignoreCase = true) || host.lowercase().endsWith("." + domain.lowercase())
}
