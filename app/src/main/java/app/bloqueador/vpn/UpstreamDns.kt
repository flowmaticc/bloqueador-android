package app.bloqueador.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Sigue los servidores DNS de las redes reales (no VPN) para reenviarles las consultas permitidas.
 * Así respetamos el DNS del operador / router del usuario y no enviamos nada a terceros.
 * Solo si no se conoce ninguno se usa el respaldo (Quad9, sin registros).
 */
class UpstreamDns(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val servers = LinkedHashMap<Network, List<InetAddress>>()

    @Volatile
    var lastNetwork: Network? = null
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            synchronized(servers) {
                servers.remove(network)
                servers[network] = lp.dnsServers.filterNot { it.isLinkLocalAddress && it !is Inet4Address }
                lastNetwork = network
            }
        }

        override fun onLost(network: Network) {
            synchronized(servers) {
                servers.remove(network)
                if (lastNetwork == network) lastNetwork = servers.keys.lastOrNull()
            }
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    /** Servidores a probar en orden: IPv4 primero (más fiable a través de cualquier red), luego IPv6, luego respaldo. */
    fun current(): List<InetAddress> {
        val list = synchronized(servers) { servers[lastNetwork] ?: servers.values.lastOrNull() } ?: emptyList()
        val sorted = list.sortedBy { if (it is Inet4Address) 0 else 1 }
        return sorted + FALLBACK.filterNot { it in sorted }
    }


    companion object {
        val FALLBACK: List<InetAddress> = listOf(
            InetAddress.getByAddress(byteArrayOf(9, 9, 9, 9)),
            InetAddress.getByAddress(byteArrayOf(149.toByte(), 112, 112, 112)),
        )
    }
}
