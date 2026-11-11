package app.bloqueador.filter

/**
 * Lee listas en los formatos habituales y acumula las reglas en un [Builder]:
 *
 * - adblock: `||dominio^` (bloquea subdominios) y `@@||dominio^` (excepción)
 * - hosts:   `0.0.0.0 dominio` / `127.0.0.1 dominio` (coincidencia exacta)
 * - dominios: `dominio` (coincidencia exacta)
 *
 * Se ignoran comentarios (`#`, `!`), cabeceras (`[Adblock Plus]`) y reglas que un filtro
 * DNS no puede aplicar (rutas, comodines, opciones `$`, reglas cosméticas `##`).
 */
object BlocklistParser {

    /** Acumula reglas en forma compacta (huellas de 64 bits); ver [DomainHashSet]. */
    class Builder {
        val exact = DomainHashSet.Builder()
        val suffix = DomainHashSet.Builder()
        val allowSuffix = DomainHashSet.Builder()

        /** Reglas de bloqueo leídas (con posibles duplicados). Sirve para validar descargas. */
        val ruleCount: Int get() = exact.added + suffix.added

        fun build() = DomainMatcher(exact.build(), suffix.build(), allowSuffix.build())
    }

    private val IGNORED_HOSTS = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0",
    )

    fun parse(lines: Sequence<String>, into: Builder = Builder()): Builder {
        for (raw in lines) parseLine(raw, into)
        return into
    }

    fun parseLine(raw: String, into: Builder) {
        val line = raw.trim()
        if (line.isEmpty() || line[0] == '#' || line[0] == '!' || line[0] == '[') return

        when {
            line.startsWith("@@||") -> adblockDomain(line, 4)?.let { into.allowSuffix.add(it) }
            line.startsWith("||") -> adblockDomain(line, 2)?.let { into.suffix.add(it) }
            else -> {
                // Un '#' solo es comentario si va tras un espacio; si no (p. ej. `dominio##.banner`)
                // es una regla cosmética de navegador y se descarta.
                val hash = line.indexOf('#')
                if (hash > 0 && !line[hash - 1].isWhitespace()) return
                val noComment = (if (hash > 0) line.substring(0, hash) else line).trim()
                val parts = noComment.split(' ', '\t').filter { it.isNotEmpty() }
                val host = when (parts.size) {
                    1 -> parts[0]
                    2 -> if (isSinkAddress(parts[0])) parts[1] else return
                    else -> return
                }
                normalize(host)?.let { into.exact.add(it) }
            }
        }
    }

    private fun adblockDomain(line: String, prefix: Int): String? {
        val end = line.indexOf('^', prefix)
        if (end < 0 || end != line.length - 1) return null // con opciones `$...` o rutas: no es DNS puro
        return normalize(line.substring(prefix, end))
    }

    private fun isSinkAddress(s: String) =
        s == "0.0.0.0" || s == "127.0.0.1" || s == "::" || s == "::1" || s == "0:0:0:0:0:0:0:0"

    /** Dominio en minúsculas y sin punto final, o null si no es un nombre de host válido. */
    internal fun normalize(host: String): String? {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || h.length > 253 || h in IGNORED_HOSTS) return null
        if ('.' !in h) return null // TLD sueltos o nombres locales: demasiado agresivo
        for (c in h) {
            val ok = c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_'
            if (!ok) return null
        }
        if (h.startsWith('.') || ".." in h) return null
        return h
    }
}
