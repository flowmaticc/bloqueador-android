package app.bloqueador.dns

/** Pregunta DNS extraída de una consulta. [end] es el índice (en el payload) justo tras la pregunta. */
data class DnsQuestion(val name: String, val type: Int, val dnsClass: Int, val end: Int)

/**
 * Parser y constructor mínimo de mensajes DNS (RFC 1035). Solo lo necesario para filtrar:
 * leer la primera pregunta de una consulta y fabricar una respuesta de bloqueo.
 */
object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val CLASS_IN = 1
    const val RCODE_NXDOMAIN = 3
    const val BLOCK_TTL_SECONDS = 60

    private const val HEADER_SIZE = 12
    private const val MAX_NAME_LENGTH = 253

    /** Devuelve la primera pregunta si [data] es una consulta estándar válida; si no, null. */
    fun parseQuery(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): DnsQuestion? {
        if (offset < 0 || length < HEADER_SIZE + 5 || offset + length > data.size) return null
        val flags = u16(data, offset + 2)
        val isResponse = flags and 0x8000 != 0
        val opcode = (flags shr 11) and 0xF
        if (isResponse || opcode != 0) return null
        return parseQuestion(data, offset, length)
    }

    /**
     * Primera pregunta de un mensaje DNS (consulta o respuesta), o null si no la tiene o no es válida.
     * Solo se aceptan nombres con caracteres ASCII imprimibles: es lo que usan los nombres de host
     * reales, y evita guardar o mostrar bytes de control.
     */
    fun parseQuestion(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): DnsQuestion? {
        if (offset < 0 || length < HEADER_SIZE + 5 || offset + length > data.size) return null
        val limit = offset + length
        if (u16(data, offset + 4) < 1) return null // QDCOUNT

        val name = StringBuilder()
        var pos = offset + HEADER_SIZE
        while (true) {
            if (pos >= limit) return null
            val labelLen = data[pos].toInt() and 0xFF
            pos++
            if (labelLen == 0) break
            if (labelLen and 0xC0 != 0) return null // punteros de compresión: no válidos en una consulta
            if (pos + labelLen > limit) return null
            if (name.isNotEmpty()) name.append('.')
            for (i in pos until pos + labelLen) {
                val c = (data[i].toInt() and 0xFF).toChar()
                if (c <= ' ' || c > '~' || c == '.') return null
                name.append(if (c in 'A'..'Z') c + 32 else c)
            }
            if (name.length > MAX_NAME_LENGTH) return null
            pos += labelLen
        }
        if (pos + 4 > limit) return null
        val type = u16(data, pos)
        val dnsClass = u16(data, pos + 2)
        return DnsQuestion(name.toString(), type, dnsClass, pos + 4 - offset)
    }

    /**
     * Respuesta de bloqueo para la consulta [query] (solo el payload DNS):
     * A → 0.0.0.0, AAAA → ::, cualquier otro tipo → NXDOMAIN.
     * Se descartan las secciones adicionales de la consulta (p. ej. EDNS).
     */
    fun buildBlockedResponse(query: ByteArray, question: DnsQuestion, queryOffset: Int = 0): ByteArray {
        val addrLen = when {
            question.dnsClass != CLASS_IN -> 0
            question.type == TYPE_A -> 4
            question.type == TYPE_AAAA -> 16
            else -> 0
        }
        val answerSize = if (addrLen > 0) 2 + 2 + 2 + 4 + 2 + addrLen else 0
        val out = ByteArray(question.end + answerSize)
        System.arraycopy(query, queryOffset, out, 0, question.end)

        val queryFlags = u16(query, queryOffset + 2)
        val rd = queryFlags and 0x0100
        val rcode = if (addrLen > 0) 0 else RCODE_NXDOMAIN
        put16(out, 2, 0x8000 or rd or 0x0080 or rcode) // QR=1, RD copiado, RA=1
        put16(out, 4, 1)                                // QDCOUNT
        put16(out, 6, if (addrLen > 0) 1 else 0)        // ANCOUNT
        put16(out, 8, 0)                                // NSCOUNT
        put16(out, 10, 0)                               // ARCOUNT

        if (addrLen > 0) {
            var p = question.end
            put16(out, p, 0xC00C); p += 2                // puntero al nombre de la pregunta
            put16(out, p, question.type); p += 2
            put16(out, p, CLASS_IN); p += 2
            put32(out, p, BLOCK_TTL_SECONDS); p += 4
            put16(out, p, addrLen)                       // RDATA = ceros (0.0.0.0 o ::)
        }
        return out
    }

    internal fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    internal fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 8).toByte()
        b[i + 1] = v.toByte()
    }

    private fun put32(b: ByteArray, i: Int, v: Int) {
        put16(b, i, v ushr 16)
        put16(b, i + 2, v and 0xFFFF)
    }
}
