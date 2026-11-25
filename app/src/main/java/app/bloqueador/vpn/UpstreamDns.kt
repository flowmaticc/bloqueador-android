package app.bloqueador.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Sigue los servidores DNS de la red real (no VPN) por la que Android sale a internet, para
 * reenviarles las consultas permitidas. Así respetamos el DNS del operador / router del usuario y no
 * enviamos nada a terceros. Solo si no se conoce ninguno se usa el respaldo (Quad9, sin registros).
 *
 * Con WiFi y datos a la vez se usa una sola red, y sus servidores y su validación son siempre de esa
 * misma red (ver [start] y [NetworkTracker]).
 */
class UpstreamDns(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** Android 12+: Android dice cuál es la mejor red. Antes: se escuchan todas y se elige aquí. */
    private val exact = Build.VERSION.SDK_INT >= 31
    private val tracker = NetworkTracker<Network>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Se leen ya sus datos: entre este aviso y los siguientes no se usaría el respaldo (Quad9)
            // con nombres internos de la red.
            val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return // no debería llegar
            tracker.onAvailable(network, exclusive = exact)
            runCatching { cm.getLinkProperties(network) }.getOrNull()?.let { tracker.onDns(network, dnsOf(it)) }
            caps?.let { tracker.onCapabilities(network, isValidated(it), rankOf(it)) }
            logIfChanged()
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            tracker.onDns(network, dnsOf(lp))
            logIfChanged()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            tracker.onCapabilities(network, isValidated(caps), rankOf(caps))
            logIfChanged()
        }

        override fun onLost(network: Network) {
            tracker.onLost(network)
            logIfChanged()
        }
    }

    @Volatile
    private var logged: String? = null
    @Volatile
    private var lastChosen: Network? = null

    /** Se llama cuando cambia la red de salida (no solo sus DNS). */
    @Volatile
    var onNetworkChanged: (() -> Unit)? = null

    /** Registro interno (logcat) de la red elegida y sus DNS, solo cuando cambian. */
    private fun logIfChanged() {
        val network = tracker.network
        if (network != lastChosen) {
            lastChosen = network
            if (network != null) onNetworkChanged?.invoke()
        }
        val line = "red=$network dns=${tracker.dns().map { it.hostAddress }}"
        if (line != logged) {
            logged = line
            Log.i(TAG, "Red de salida: $line")
        }
    }

    fun start() {
        // No vale registerDefaultNetworkCallback: para la app que crea la VPN, Android da la propia VPN
        // como red predeterminada (aunque la app esté excluida del túnel).
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        if (exact) {
            // Solo avisa de la mejor red real, la misma que usa Android. Sin permisos extra.
            cm.registerBestMatchingNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        } else {
            // requestNetwork pediría el permiso CHANGE_NETWORK_STATE: se escuchan todas y se elige
            // con el mismo criterio que Android (ver NetworkTracker.best).
            cm.registerNetworkCallback(request, callback)
        }
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    /** Red de salida actual (null si no hay ninguna). */
    val lastNetwork: Network? get() = tracker.network

    /**
     * ¿Ha comprobado Android que la red actual tiene internet de verdad? false con un portal cautivo sin
     * iniciar sesión. Si aún no se sabe, se da por buena (para no desactivar el cifrado sin motivo).
     */
    fun isValidated(): Boolean = tracker.isValidated()

    /**
     * Servidores a probar en orden: IPv4 primero (más fiable a través de cualquier red), luego IPv6.
     * El respaldo (Quad9) solo si la red no anuncia ningún DNS: así los nombres internos de la red
     * (router, impresoras, intranets) nunca salen a un tercero.
     */
    fun current(): List<InetAddress> = choose(tracker.dns())

    /** Tipo de la red de salida (para el diagnóstico), o null si no hay ninguna. */
    fun networkKind(): NetworkKind? {
        val n = tracker.network ?: return null
        val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return NetworkKind.OTHER
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
            else -> NetworkKind.OTHER
        }
    }

    /** Servidores DNS que anuncia la red de salida (sin el respaldo). */
    fun networkDns(): List<String> = tracker.dns().mapNotNull { it.hostAddress }

    companion object {
        private const val TAG = "UpstreamDns"

        internal fun choose(network: List<InetAddress>): List<InetAddress> =
            if (network.isEmpty()) FALLBACK else network.sortedBy { if (it is Inet4Address) 0 else 1 }

        private fun dnsOf(lp: LinkProperties): List<InetAddress> =
            lp.dnsServers.filterNot { it.isLinkLocalAddress && it !is Inet4Address }

        private fun isValidated(caps: NetworkCapabilities): Boolean =
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)

        /** Preferencia de Android entre redes validadas: cable, luego WiFi, luego datos móviles. */
        private fun rankOf(caps: NetworkCapabilities): Int = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
            else -> 0
        }

        val FALLBACK: List<InetAddress> = listOf(
            InetAddress.getByAddress(byteArrayOf(9, 9, 9, 9)),
            InetAddress.getByAddress(byteArrayOf(149.toByte(), 112, 112, 112)),
        )
    }
}

/**
 * Redes reales conocidas y cuál se usa para salir. Los DNS y la validación que se devuelven son
 * siempre de la **misma** red: nunca se mezclan los DNS de una con la validación de otra, y los avisos
 * tardíos de una red que ya no es la elegida no cambian nada. Genérico para poder probarlo sin Android.
 *
 * - `exclusive` (Android 12+, la red la elige Android): cada red nueva sustituye a las anteriores.
 * - Si no, se elige como Android: primero las validadas, luego cable > WiFi > datos; a igualdad, la
 *   que se conectó antes (Android no cambia de red sin motivo).
 */
internal class NetworkTracker<N : Any> {
    private class Info(val order: Long, val dns: List<InetAddress> = emptyList(), val validated: Boolean? = null, val rank: Int = 0)

    private val networks = LinkedHashMap<N, Info>()
    private var counter = 0L

    @Volatile
    private var chosen: Pair<N, Info>? = null

    val network: N? get() = chosen?.first

    @Synchronized
    fun onAvailable(network: N, exclusive: Boolean = false) {
        if (exclusive) networks.keys.retainAll { it == network }
        if (network !in networks) networks[network] = Info(counter++)
        update()
    }

    @Synchronized
    fun onDns(network: N, dns: List<InetAddress>) {
        val i = networks[network] ?: return
        networks[network] = Info(i.order, dns, i.validated, i.rank)
        update()
    }

    @Synchronized
    fun onCapabilities(network: N, validated: Boolean, rank: Int = 0) {
        val i = networks[network] ?: return
        networks[network] = Info(i.order, i.dns, validated, rank)
        update()
    }

    @Synchronized
    fun onLost(network: N) {
        networks.remove(network)
        update()
    }

    private fun update() {
        val best = networks.entries.minWithOrNull(
            compareByDescending<Map.Entry<N, Info>> { it.value.validated != false }
                .thenByDescending { it.value.rank }
                .thenBy { it.value.order },
        )
        chosen = best?.let { it.key to it.value }
    }

    fun dns(): List<InetAddress> = chosen?.second?.dns ?: emptyList()

    fun isValidated(): Boolean = chosen?.second?.validated ?: true
}
