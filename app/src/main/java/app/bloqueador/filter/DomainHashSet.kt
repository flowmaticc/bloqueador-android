package app.bloqueador.filter

import java.util.Arrays

/**
 * Conjunto de dominios guardado como huellas de 64 bits ordenadas (búsqueda binaria).
 *
 * Ocupa 8 bytes por dominio frente a ~100 bytes de un `HashSet<String>` (objeto String, array de
 * caracteres, nodo de la tabla). Con 600.000 reglas: ~5 MB en vez de ~80 MB.
 *
 * Con huellas de 64 bits, la probabilidad de que un dominio cualquiera coincida por azar con alguno
 * de 600.000 es de ~3·10⁻¹⁴: en la práctica, nula.
 */
class DomainHashSet private constructor(private val hashes: LongArray) {

    val size: Int get() = hashes.size

    operator fun contains(domain: String): Boolean = contains(domain, 0)

    /** ¿Está el sufijo de [domain] que empieza en [start]? Sin crear subcadenas. */
    fun contains(domain: String, start: Int): Boolean =
        hashes.isNotEmpty() && Arrays.binarySearch(hashes, hash(domain, start)) >= 0

    /** Acumulador que crece según se leen las listas; [build] ordena y quita duplicados. */
    class Builder {
        private var data = LongArray(1024)
        private var count = 0

        /** Entradas añadidas (con posibles duplicados entre listas). */
        val added: Int get() = count

        fun add(domain: String) {
            if (count == data.size) data = data.copyOf(data.size * 2)
            data[count++] = hash(domain, 0)
        }

        fun build(): DomainHashSet {
            if (count == 0) return EMPTY
            val sorted = data.copyOf(count)
            Arrays.sort(sorted)
            var unique = 1
            for (i in 1 until sorted.size) {
                if (sorted[i] != sorted[unique - 1]) sorted[unique++] = sorted[i]
            }
            return DomainHashSet(if (unique == sorted.size) sorted else sorted.copyOf(unique))
        }
    }

    companion object {
        val EMPTY = DomainHashSet(LongArray(0))

        fun of(domains: Collection<String>): DomainHashSet =
            Builder().apply { domains.forEach(::add) }.build()

        /** FNV-1a de 64 bits sobre los caracteres desde [start], con mezcla final (fmix64). */
        internal fun hash(s: String, start: Int): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (i in start until s.length) {
                h = h xor s[i].code.toLong()
                h *= 0x100000001b3L
            }
            h = h xor (h ushr 33)
            h *= -0xae502812aa7333L // 0xff51afd7ed558ccd
            h = h xor (h ushr 33)
            h *= -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
            return h xor (h ushr 33)
        }
    }
}
