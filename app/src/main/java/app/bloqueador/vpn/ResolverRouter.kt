package app.bloqueador.vpn

import app.bloqueador.dns.UdpPacket

/**
 * Elige en cada consulta quién la resuelve:
 * - DNS cifrado desactivado → [udp] (el DNS de la red, como siempre).
 * - DNS cifrado activado → el [DotResolver] del servicio elegido. Si se cambia de servicio en Ajustes,
 *   se cierra el anterior y se abre el nuevo en la siguiente consulta.
 * - Excepción: mientras la red no esté validada por Android (una wifi con portal cautivo antes de
 *   iniciar sesión), se usa el DNS de la red: si no, no se podría abrir la página de acceso. En cuanto
 *   Android la valida, se vuelve al cifrado. No hay otro "plan B": si el servicio cifrado no responde,
 *   no se cae en silencio al DNS sin cifrar (se avisa de que el DNS no responde).
 */
class ResolverRouter(
    private val udp: DnsResolver,
    private val provider: () -> DotProvider?,
    private val networkValidated: () -> Boolean,
    private val createDot: (DotProvider) -> DnsResolver,
) : DnsResolver {

    @Volatile private var dot: Pair<DotProvider, DnsResolver>? = null

    override fun start() = udp.start()

    override fun stop() {
        udp.stop()
        synchronized(this) {
            dot?.second?.stop()
            dot = null
        }
    }

    /** El resolvedor que toca ahora mismo. */
    internal fun current(): DnsResolver {
        val wanted = provider()
        if (wanted == null || !networkValidated()) return udp
        dot?.let { (p, r) -> if (p == wanted) return r }
        synchronized(this) {
            dot?.let { (p, r) -> if (p == wanted) return r else r.stop() }
            val r = createDot(wanted).also { it.start() }
            dot = wanted to r
            return r
        }
    }

    override fun forward(request: UdpPacket, domain: String, app: String?) = current().forward(request, domain, app)

    override val inFlight: Int get() = udp.inFlight + (dot?.second?.inFlight ?: 0)

    override fun isUnresponsive(now: Long): Boolean = current().isUnresponsive(now)

    /** Quién resuelve ahora y cuándo contestó por última vez (sin abrir conexiones nuevas). */
    fun status(): Pair<ResolverKind, Long> {
        val wanted = provider()
        return when {
            wanted == null -> ResolverKind.NETWORK to udp.lastAnswerAt
            !networkValidated() -> ResolverKind.NETWORK_UNVALIDATED to udp.lastAnswerAt
            else -> ResolverKind.ENCRYPTED to (dot?.takeIf { it.first == wanted }?.second?.lastAnswerAt ?: 0L)
        }
    }

    override fun networkChanged() {
        udp.networkChanged()
        dot?.second?.networkChanged()
    }
}
