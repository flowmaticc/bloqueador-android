package app.bloqueador.filter

import java.util.concurrent.atomic.AtomicLong

/**
 * Punto único de decisión "¿se bloquea este dominio?". El motor se sustituye en caliente con
 * [update] (al cargar listas o añadir excepciones) sin tocar el túnel.
 */
class FilterEngine(cacheSize: Int = 2048) {

    @Volatile
    var matcher: DomainMatcher = DomainMatcher.EMPTY
        private set

    /** Dominio → regla que lo bloquea, o [ALLOWED] si se permite. */
    private val cache = LruCache<String, String>(cacheSize)

    val queries = AtomicLong()
    val blocked = AtomicLong()
    val cacheHits = AtomicLong()

    fun update(newMatcher: DomainMatcher) {
        matcher = newMatcher
        cache.clear()
    }

    fun isBlocked(domain: String): Boolean = decide(domain) != null

    /** Regla que bloquea [domain] (y cuenta la consulta), o null si se permite. */
    fun decide(domain: String): String? {
        queries.incrementAndGet()
        val cached = cache[domain]
        val rule = if (cached != null) {
            cacheHits.incrementAndGet()
            cached.takeIf { it != ALLOWED }
        } else {
            matcher.matchRule(domain).also { cache.put(domain, it ?: ALLOWED) }
        }
        if (rule != null) blocked.incrementAndGet()
        return rule
    }

    fun resetCounters() {
        queries.set(0); blocked.set(0); cacheHits.set(0)
    }

    private companion object {
        const val ALLOWED = ""
    }
}

/** Caché LRU mínima y segura entre hilos. */
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

    @get:Synchronized
    val size: Int get() = map.size
}
