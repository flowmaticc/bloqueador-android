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
