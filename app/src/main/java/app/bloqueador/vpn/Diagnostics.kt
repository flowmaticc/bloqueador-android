package app.bloqueador.vpn

import app.bloqueador.dns.DnsMessage

/** Estado interno del filtro para la pantalla Diagnóstico (lo rellena el servicio; solo lectura). */
data class DiagnosticsInfo(
    /** Tipo de la red real de salida, o null si no hay ninguna. */
    val network: NetworkKind?,
    /** Servidores DNS que anuncia esa red (los que se usan sin DNS cifrado). */
    val networkDns: List<String>,
    /** Quién resuelve ahora las consultas permitidas. */
    val resolver: ResolverKind,
    /** Servicio de DNS cifrado elegido en Ajustes (aunque ahora no se use), o null. */
    val encryptedProvider: String?,
    /** Momento de la última respuesta del resolvedor en uso (0 = ninguna aún). */
    val lastAnswerAt: Long,
    /** ¿Captura el túnel los DNS públicos conocidos? (no con DNS privado fijo). */
    val publicDnsCaptured: Boolean,
    /** Apps sin internet activas en el túnel actual. */
    val noInternetApps: Int,
)

enum class NetworkKind { WIFI, CELLULAR, ETHERNET, OTHER }

enum class ResolverKind {
    /** DNS cifrado (DoT) al servicio elegido. */
    ENCRYPTED,
    /** DNS de la red (sin DNS cifrado activado). */
    NETWORK,
    /** DNS de la red porque Android aún no la ha validado (portal cautivo): luego vuelve al cifrado. */
    NETWORK_UNVALIDATED,
}

/** Resultado de una consulta de prueba que recorre el filtro entero, igual que la de una app. */
sealed interface SelfTestResult {
    /** Respuesta con dirección: [address] y tiempo total en ms. */
    data class Resolved(val address: String, val millis: Long) : SelfTestResult
    /** Respondida con la dirección vacía del bloqueo (0.0.0.0). */
    data class Blocked(val millis: Long) : SelfTestResult
    /** Respondida con un error (código DNS [rcode]: 2 = SERVFAIL, 3 = no existe…). */
    data class Failed(val rcode: Int) : SelfTestResult
    /** Sin respuesta en el tiempo de espera. */
    data object Timeout : SelfTestResult
    /** El filtro no está activo. */
    data object NotRunning : SelfTestResult
}

internal object SelfTest {
    /** Consulta A de [name] con identificador [id]. */
    fun query(name: String, id: Int): ByteArray {
        val labels = name.trim('.').split('.')
        val out = ByteArray(12 + labels.sumOf { it.length + 1 } + 1 + 4)
        DnsMessage.put16(out, 0, id)
        DnsMessage.put16(out, 2, 0x0100) // consulta estándar, recursión deseada
        DnsMessage.put16(out, 4, 1)
        var p = 12
        for (l in labels) {
            out[p++] = l.length.toByte()
            l.forEach { out[p++] = it.code.toByte() }
        }
        p++
        DnsMessage.put16(out, p, DnsMessage.TYPE_A)
        DnsMessage.put16(out, p + 2, DnsMessage.CLASS_IN)
        return out
    }

    /** Interpreta la respuesta: primera dirección IPv4 de la sección de respuestas. */
    fun interpret(response: ByteArray, millis: Long): SelfTestResult {
        if (response.size < 12) return SelfTestResult.Failed(-1)
        val rcode = response[3].toInt() and 0x0F
        if (rcode != 0) return SelfTestResult.Failed(rcode)
        val answers = DnsMessage.u16(response, 6)
        var pos = DnsMessage.parseQuestion(response)?.end ?: return SelfTestResult.Failed(-1)
        repeat(answers) {
            pos = skipName(response, pos) ?: return SelfTestResult.Failed(-1)
            if (pos + 10 > response.size) return SelfTestResult.Failed(-1)
            val type = DnsMessage.u16(response, pos)
            val len = DnsMessage.u16(response, pos + 8)
            val data = pos + 10
            if (data + len > response.size) return SelfTestResult.Failed(-1)
            if (type == DnsMessage.TYPE_A && len == 4) {
                val ip = (0 until 4).joinToString(".") { (response[data + it].toInt() and 0xFF).toString() }
                return if (ip == "0.0.0.0") SelfTestResult.Blocked(millis) else SelfTestResult.Resolved(ip, millis)
            }
            pos = data + len
        }
        return SelfTestResult.Failed(0) // sin dirección (NODATA)
    }

    /** Salta un nombre (con o sin compresión) y devuelve dónde sigue, o null si no cabe. */
    private fun skipName(d: ByteArray, start: Int): Int? {
        var pos = start
        while (pos < d.size) {
            val len = d[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if (len and 0xC0 == 0xC0) return if (pos + 2 <= d.size) pos + 2 else null
            pos += 1 + len
        }
        return null
    }
}
