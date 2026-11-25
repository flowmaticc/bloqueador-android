package app.bloqueador.dns

/** Datagrama UDP leído de la interfaz tun (IPv4 o IPv6). Las direcciones van en bytes crudos. */
class UdpPacket(
    val ipVersion: Int,
    val source: ByteArray,
    val destination: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val data: ByteArray,
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    fun payload(): ByteArray = data.copyOfRange(payloadOffset, payloadOffset + payloadLength)
}

/** Lectura y construcción de paquetes IPv4/IPv6 + UDP para la interfaz tun. */
object IpPacket {
    private const val PROTO_UDP = 17
    private const val PROTO_TCP = 6
    private const val TCP_HEADER = 20
    private const val IPV4_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val UDP_HEADER = 8

    /** Devuelve el datagrama UDP contenido en [data] (sin copiar), o null si no es UDP válido. */
    fun parseUdp(data: ByteArray, length: Int): UdpPacket? {
        if (length < 1) return null
        return when ((data[0].toInt() and 0xFF) shr 4) {
            4 -> parseV4(data, length)
            6 -> parseV6(data, length)
            else -> null
        }
    }

    private fun parseV4(d: ByteArray, length: Int): UdpPacket? {
        if (length < IPV4_HEADER) return null
        val ihl = (d[0].toInt() and 0x0F) * 4
        if (ihl < IPV4_HEADER || length < ihl + UDP_HEADER) return null
        if ((d[9].toInt() and 0xFF) != PROTO_UDP) return null
        val fragment = DnsMessage.u16(d, 6)
        if (fragment and 0x3FFF != 0) return null // fragmentado (MF o offset): DNS no lo usa
        val total = minOf(DnsMessage.u16(d, 2), length)
        return udp(4, d, d.copyOfRange(12, 16), d.copyOfRange(16, 20), ihl, total)
    }

    private fun parseV6(d: ByteArray, length: Int): UdpPacket? {
        if (length < IPV6_HEADER + UDP_HEADER) return null
        if ((d[6].toInt() and 0xFF) != PROTO_UDP) return null // no seguimos cabeceras de extensión
        val total = minOf(IPV6_HEADER + DnsMessage.u16(d, 4), length)
        return udp(6, d, d.copyOfRange(8, 24), d.copyOfRange(24, 40), IPV6_HEADER, total)
    }

    private fun udp(v: Int, d: ByteArray, src: ByteArray, dst: ByteArray, udpStart: Int, ipEnd: Int): UdpPacket? {
        if (ipEnd < udpStart + UDP_HEADER) return null
        val udpLen = DnsMessage.u16(d, udpStart + 4)
        if (udpLen < UDP_HEADER || udpStart + udpLen > ipEnd) return null
        return UdpPacket(
            ipVersion = v,
            source = src,
            destination = dst,
            sourcePort = DnsMessage.u16(d, udpStart),
            destinationPort = DnsMessage.u16(d, udpStart + 2),
            data = d,
            payloadOffset = udpStart + UDP_HEADER,
            payloadLength = udpLen - UDP_HEADER,
        )
    }

    /** Paquete de respuesta a [request]: direcciones y puertos intercambiados, con [payload]. */
    fun buildUdpReply(request: UdpPacket, payload: ByteArray): ByteArray =
        buildUdp(
            ipVersion = request.ipVersion,
            source = request.destination,
            destination = request.source,
            sourcePort = request.destinationPort,
            destinationPort = request.sourcePort,
            payload = payload,
        )

    fun buildUdp(
        ipVersion: Int,
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val headerLen = if (ipVersion == 4) IPV4_HEADER else IPV6_HEADER
        val udpLen = UDP_HEADER + payload.size
        val out = ByteArray(headerLen + udpLen)

        if (ipVersion == 4) {
            out[0] = 0x45
            DnsMessage.put16(out, 2, out.size)
            out[6] = 0x40 // DF
            out[8] = 64   // TTL
            out[9] = PROTO_UDP.toByte()
            System.arraycopy(source, 0, out, 12, 4)
            System.arraycopy(destination, 0, out, 16, 4)
            DnsMessage.put16(out, 10, checksum(0, out, 0, IPV4_HEADER))
        } else {
            out[0] = 0x60
            DnsMessage.put16(out, 4, udpLen)
            out[6] = PROTO_UDP.toByte()
            out[7] = 64 // hop limit
            System.arraycopy(source, 0, out, 8, 16)
            System.arraycopy(destination, 0, out, 24, 16)
        }

        val u = headerLen
        DnsMessage.put16(out, u, sourcePort)
        DnsMessage.put16(out, u + 2, destinationPort)
        DnsMessage.put16(out, u + 4, udpLen)
        System.arraycopy(payload, 0, out, u + UDP_HEADER, payload.size)

        // Pseudo-cabecera: origen + destino + protocolo + longitud UDP
        var sum = sumWords(0, source, 0, source.size)
        sum = sumWords(sum, destination, 0, destination.size)
        sum += PROTO_UDP + udpLen
        var udpChecksum = checksum(sum, out, u, udpLen)
        if (udpChecksum == 0) udpChecksum = 0xFFFF
        DnsMessage.put16(out, u + 6, udpChecksum)
        return out
    }

    /**
     * Si [data] es un segmento TCP, el RST que lo rechaza (direcciones y puertos intercambiados), para
     * que quien conecta falle al momento en lugar de esperar. null si no es TCP o ya es un RST.
     * RFC 9293 §3.10.7.1: si el segmento trae ACK, el RST lleva seq = su ACK; si no, seq = 0 y
     * ACK = su seq + longitud (SYN y FIN cuentan 1).
     */
    fun buildTcpReset(data: ByteArray, length: Int): ByteArray? {
        if (length < 1) return null
        val v = (data[0].toInt() and 0xFF) shr 4
        val ipHeader: Int
        val ipEnd: Int
        when (v) {
            4 -> {
                if (length < IPV4_HEADER) return null
                ipHeader = (data[0].toInt() and 0x0F) * 4
                if (ipHeader < IPV4_HEADER || (data[9].toInt() and 0xFF) != PROTO_TCP) return null
                if (DnsMessage.u16(data, 6) and 0x1FFF != 0) return null // fragmento sin cabecera TCP
                ipEnd = minOf(DnsMessage.u16(data, 2), length)
            }
            6 -> {
                ipHeader = IPV6_HEADER
                if (length < IPV6_HEADER || (data[6].toInt() and 0xFF) != PROTO_TCP) return null
                ipEnd = minOf(IPV6_HEADER + DnsMessage.u16(data, 4), length)
            }
            else -> return null
        }
        if (ipEnd < ipHeader + TCP_HEADER) return null
        val t = ipHeader
        val flags = data[t + 13].toInt() and 0xFF
        if (flags and 0x04 != 0) return null // a un RST no se responde
        val dataOffset = ((data[t + 12].toInt() and 0xFF) shr 4) * 4
        if (dataOffset < TCP_HEADER || t + dataOffset > ipEnd) return null
        val segLen = (ipEnd - t - dataOffset) + (if (flags and 0x02 != 0) 1 else 0) + (if (flags and 0x01 != 0) 1 else 0)
        val hasAck = flags and 0x10 != 0
        val seq = if (hasAck) u32(data, t + 8) else 0L
        val ack = if (hasAck) 0L else (u32(data, t + 4) + segLen) and 0xFFFFFFFFL
        val outFlags = if (hasAck) 0x04 else 0x14 // RST, o RST+ACK

        val src = if (v == 4) data.copyOfRange(16, 20) else data.copyOfRange(24, 40) // su destino
        val dst = if (v == 4) data.copyOfRange(12, 16) else data.copyOfRange(8, 24)  // su origen
        val headerLen = if (v == 4) IPV4_HEADER else IPV6_HEADER
        val out = ByteArray(headerLen + TCP_HEADER)
        if (v == 4) {
            out[0] = 0x45
            DnsMessage.put16(out, 2, out.size)
            out[6] = 0x40
            out[8] = 64
            out[9] = PROTO_TCP.toByte()
            System.arraycopy(src, 0, out, 12, 4)
            System.arraycopy(dst, 0, out, 16, 4)
            DnsMessage.put16(out, 10, checksum(0, out, 0, IPV4_HEADER))
        } else {
            out[0] = 0x60
            DnsMessage.put16(out, 4, TCP_HEADER)
            out[6] = PROTO_TCP.toByte()
            out[7] = 64
            System.arraycopy(src, 0, out, 8, 16)
            System.arraycopy(dst, 0, out, 24, 16)
        }
        val o = headerLen
        DnsMessage.put16(out, o, DnsMessage.u16(data, t + 2))     // puerto origen = su destino
        DnsMessage.put16(out, o + 2, DnsMessage.u16(data, t))     // puerto destino = su origen
        put32(out, o + 4, seq)
        put32(out, o + 8, ack)
        out[o + 12] = (5 shl 4).toByte()
        out[o + 13] = outFlags.toByte()
        var sum = sumWords(0, src, 0, src.size)
        sum = sumWords(sum, dst, 0, dst.size)
        sum += PROTO_TCP + TCP_HEADER
        DnsMessage.put16(out, o + 16, checksum(sum, out, o, TCP_HEADER))
        return out
    }

    private fun u32(d: ByteArray, i: Int): Long =
        (DnsMessage.u16(d, i).toLong() shl 16) or DnsMessage.u16(d, i + 2).toLong()

    private fun put32(d: ByteArray, i: Int, value: Long) {
        DnsMessage.put16(d, i, ((value ushr 16) and 0xFFFF).toInt())
        DnsMessage.put16(d, i + 2, (value and 0xFFFF).toInt())
    }

    /** Suma de comprobación de Internet (RFC 1071) partiendo de una suma previa [initial]. */
    internal fun checksum(initial: Int, data: ByteArray, offset: Int, length: Int): Int {
        var sum = sumWords(initial, data, offset, length)
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun sumWords(initial: Int, data: ByteArray, offset: Int, length: Int): Int {
        var sum = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += DnsMessage.u16(data, i)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        // Plegado parcial para no desbordar con payloads grandes
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum
    }
}
