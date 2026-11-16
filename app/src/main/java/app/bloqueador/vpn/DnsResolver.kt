package app.bloqueador.vpn

import app.bloqueador.dns.UdpPacket

/**
 * Quien resuelve las consultas que el filtro deja pasar. Hay dos: [DnsForwarder] (UDP al DNS de la
 * red, lo de siempre) y [DotResolver] (DNS cifrado por TLS a un servicio elegido). [ResolverRouter]
 * elige uno u otro en cada consulta.
 */
interface DnsResolver {
    fun start()
    fun stop()

    /** Resuelve [request] (consulta de [domain] hecha por [app]) y entrega la respuesta por su cuenta. */
    fun forward(request: UdpPacket, domain: String, app: String? = null)

    /** Consultas enviadas que aún esperan respuesta. */
    val inFlight: Int

    /** true si se pregunta pero nadie contesta desde hace un rato (para avisar en la pantalla principal). */
    fun isUnresponsive(now: Long = System.currentTimeMillis()): Boolean
}
