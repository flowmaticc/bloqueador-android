package app.bloqueador.lists

/**
 * Protección contra amenazas: phishing, malware, estafas, criptominado y webs falsas, con las
 * listas "Threat Intelligence Feeds" de Hagezi.
 *
 * Va aparte de la lista de publicidad para poder distinguir sus bloqueos (avisar al usuario y
 * marcarlos en el historial). La lista Pro ya incluye las de webs falsas y ventanas emergentes
 * maliciosas de Hagezi; las de amenazas añaden cientos de miles de dominios que Pro no tiene.
 */
enum class ThreatLevel(val label: String, val description: String, val source: ListSource) {
    STANDARD(
        "Estándar",
        "La recomendada para móviles: las amenazas más activas. Unos 10 MB de memoria.",
        ListSource("amenazas", "Hagezi Threat Intelligence (media)", "", "$HAGEZI_URL/tif.medium.txt"),
    ),
    MAX(
        "Máxima",
        "Todas las amenazas conocidas (más de un millón de dominios). Unos 40 MB de memoria: " +
            "solo para móviles con memoria de sobra.",
        ListSource("amenazas_max", "Hagezi Threat Intelligence (completa)", "", "$HAGEZI_URL/tif.txt"),
    ),
}

internal const val HAGEZI_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock"
