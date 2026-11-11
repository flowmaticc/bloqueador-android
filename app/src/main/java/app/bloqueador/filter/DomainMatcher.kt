package app.bloqueador.filter

/**
 * Motor de coincidencia inmutable.
 *
 * - [exact]: dominios de listas hosts / dominios planos → solo coincide el dominio exacto.
 * - [suffix]: reglas adblock `||dominio^` → coincide el dominio y todos sus subdominios.
 * - [allowSuffix]: excepciones `@@||dominio^` → tienen prioridad sobre cualquier bloqueo.
 *
 * Coste por consulta: O(nº de etiquetas del dominio) búsquedas binarias, sin crear objetos salvo
 * el texto de la regla cuando hay bloqueo.
 */
class DomainMatcher(
    private val exact: DomainHashSet,
    private val suffix: DomainHashSet,
    private val allowSuffix: DomainHashSet,
) {
    /** Reglas de bloqueo distintas cargadas. */
    val size: Int get() = exact.size + suffix.size

    fun isBlocked(domain: String): Boolean = matchRule(domain) != null

    /**
     * Regla que bloquea [domain], escrita como en las listas (`||ads.com^` o `ads.com`),
     * o null si el dominio se permite.
     */
    fun matchRule(domain: String): String? {
        if (findSuffix(domain, allowSuffix) >= 0) return null
        if (domain in ALWAYS_BLOCKED) return "||$domain^"
        if (domain in exact) return domain
        val at = findSuffix(domain, suffix)
        return if (at >= 0) "||${domain.substring(at)}^" else null
    }

    /** Excepción `@@||…^` que permite [domain], o null si no hay ninguna. */
    fun allowRule(domain: String): String? {
        val at = findSuffix(domain, allowSuffix)
        return if (at >= 0) "@@||${domain.substring(at)}^" else null
    }

    /** Posición donde empieza el sufijo más largo de [domain] presente en [set], o -1. */
    private fun findSuffix(domain: String, set: DomainHashSet): Int {
        if (set.size == 0) return -1
        var start = 0
        while (true) {
            if (set.contains(domain, start)) return start
            val dot = domain.indexOf('.', start)
            if (dot < 0) return -1
            start = dot + 1
        }
    }

    companion object {
        /**
         * Dominio canario: si se bloquea, Firefox desactiva su DNS-over-HTTPS por defecto
         * y vuelve a usar el DNS del sistema (es decir, el nuestro).
         */
        val ALWAYS_BLOCKED = setOf("use-application-dns.net")

        val EMPTY = DomainMatcher(DomainHashSet.EMPTY, DomainHashSet.EMPTY, DomainHashSet.EMPTY)
    }
}
