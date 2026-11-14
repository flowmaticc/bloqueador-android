package app.bloqueador.filter

import java.util.concurrent.atomic.AtomicLong

/**
 * Punto único de decisión "¿se bloquea este dominio?". Los motores se sustituyen en caliente con
 * [update] (al cargar listas o añadir excepciones) sin tocar el túnel.
 *
 * Hay dos motores: [matcher] (publicidad y rastreo) y [threats] (phishing, malware, estafas). Un
 * bloqueo por amenaza se devuelve con el prefijo [THREAT_PREFIX] para poder avisar y marcarlo.
 */
class FilterEngine(private val cacheSize: Int = 2048) {

    /**
     * Motores y su caché van juntos y se sustituyen de una vez: una consulta que coincide con una
     * recarga decide y guarda en la caché del mismo estado, nunca deja en la nueva un resultado viejo.
     */
    private class State(val filters: Filters, cacheSize: Int) {
        /** Dominio → regla que lo bloquea, o [ALLOWED] si se permite. */
        val cache = LruCache<String, String>(cacheSize)
    }

    @Volatile
    private var state = State(Filters(DomainMatcher.EMPTY), cacheSize)

    val matcher: DomainMatcher get() = state.filters.main
    val threats: DomainMatcher get() = state.filters.threats

    val queries = AtomicLong()
    val blocked = AtomicLong()
    val threatsBlocked = AtomicLong()
    val cacheHits = AtomicLong()

    fun update(filters: Filters) {
        state = State(filters, cacheSize)
    }

    fun isBlocked(domain: String): Boolean = decide(domain) != null

    /** Regla que bloquea [domain] (y cuenta la consulta), o null si se permite. */
    fun decide(domain: String): String? {
        queries.incrementAndGet()
        val s = state
        val cached = s.cache[domain]
        val rule = if (cached != null) {
            cacheHits.incrementAndGet()
            cached.takeIf { it != ALLOWED }
        } else {
            peek(s.filters, domain).also { s.cache.put(domain, it ?: ALLOWED) }
        }
        if (rule != null) {
            blocked.incrementAndGet()
            if (isThreat(rule)) threatsBlocked.incrementAndGet()
        }
        return rule
    }

    /** Como [decide] pero sin caché ni contadores (para la vista previa y las comprobaciones). */
    fun peek(domain: String): String? = peek(state.filters, domain)

    private fun peek(f: Filters, domain: String): String? =
        f.threats.matchRule(domain)?.let { THREAT_PREFIX + it } ?: f.main.matchRule(domain)

    fun resetCounters() {
        queries.set(0); blocked.set(0); threatsBlocked.set(0); cacheHits.set(0)
    }

    /** Motores que se cargan juntos. */
    class Filters(val main: DomainMatcher, val threats: DomainMatcher = DomainMatcher.EMPTY)

    companion object {
        private const val ALLOWED = ""

        /** Prefijo de las reglas que vienen de la protección contra amenazas. */
        const val THREAT_PREFIX = "amenaza:"

        fun isThreat(rule: String?): Boolean = rule?.startsWith(THREAT_PREFIX) == true

        /** La regla tal como aparece en la lista (sin el prefijo de amenaza). */
        fun plainRule(rule: String): String = rule.removePrefix(THREAT_PREFIX)
    }
}

/** Caché LRU mínima y sincronizada. */
class LruCache<K, V>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > maxSize
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun clear() = map.clear()

    val size: Int
        @Synchronized get() = map.size
}
