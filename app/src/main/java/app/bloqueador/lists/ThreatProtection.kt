package app.bloqueador.lists

import app.bloqueador.R
import app.bloqueador.UiText
/**
 * Protección contra amenazas: phishing, malware, estafas, criptominado y webs falsas, con las
 * listas "Threat Intelligence Feeds" de Hagezi.
 *
 * Va aparte de la lista de publicidad para poder distinguir sus bloqueos (avisar al usuario y
 * marcarlos en el historial). La lista Pro ya incluye las de webs falsas y ventanas emergentes
 * maliciosas de Hagezi; las de amenazas añaden cientos de miles de dominios que Pro no tiene.
 */
enum class ThreatLevel(val label: UiText, val description: UiText, val source: ListSource) {
    STANDARD(
        UiText.res(R.string.threat_standard), UiText.res(R.string.threat_standard_desc),
        ListSource("amenazas", UiText.res(R.string.list_tif_medium), UiText.EMPTY, "$HAGEZI_URL/tif.medium.txt"),
    ),
    MAX(
        UiText.res(R.string.threat_max), UiText.res(R.string.threat_max_desc),
        ListSource("amenazas_max", UiText.res(R.string.list_tif_full), UiText.EMPTY, "$HAGEZI_URL/tif.txt"),
    ),
}

internal const val HAGEZI_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock"
