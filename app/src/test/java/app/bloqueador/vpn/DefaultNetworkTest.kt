package app.bloqueador.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Los DNS y la validación usados son siempre los de la red de salida, y de la misma red. */
class DefaultNetworkTest {

    private val wifiDns = listOf(InetAddress.getByName("192.168.1.1"))
    private val mobileDns = listOf(InetAddress.getByName("10.0.0.53"))
    private val WIFI = 2
    private val MOVIL = 1

    private fun NetworkTracker<String>.up(n: String, dns: List<InetAddress>, validated: Boolean, rank: Int, exclusive: Boolean = false) {
        onAvailable(n, exclusive)
        onDns(n, dns)
        onCapabilities(n, validated, rank)
    }

    // --- Android 12+: la red la elige Android (exclusive) ---

    @Test
    fun `Android 12+ un aviso tardio de la red anterior no cambia los DNS`() {
        val t = NetworkTracker<String>()
        t.up("movil", mobileDns, false, MOVIL, exclusive = true)
        t.up("wifi", wifiDns, true, WIFI, exclusive = true)
        t.onDns("movil", mobileDns)
        t.onCapabilities("movil", true, MOVIL)
        assertEquals("wifi", t.network)
        assertEquals(wifiDns, t.dns())
        assertTrue(t.isValidated())
    }

    @Test
    fun `Android 12+ al pasar de WiFi a movil no se mezcla la validacion de la anterior`() {
        val t = NetworkTracker<String>()
        t.up("wifi", wifiDns, false, WIFI, exclusive = true)
        t.onAvailable("movil", exclusive = true)
        assertEquals(emptyList<InetAddress>(), t.dns())
        assertTrue("sin datos aún de la nueva red se da por buena", t.isValidated())
        t.onDns("movil", mobileDns); t.onCapabilities("movil", true, MOVIL)
        assertEquals(mobileDns, t.dns())
        t.onLost("wifi") // pérdida tardía de la anterior
        assertEquals("movil", t.network)
        assertEquals(mobileDns, t.dns())
    }

    // --- Android 8 a 11: se escuchan todas y se elige como Android ---

    @Test
    fun `con WiFi y datos se usa la WiFi aunque los datos avisen despues`() {
        val t = NetworkTracker<String>()
        t.up("wifi", wifiDns, true, WIFI)
        t.up("movil", mobileDns, true, MOVIL)
        t.onDns("movil", mobileDns)
        assertEquals("wifi", t.network)
        assertEquals(wifiDns, t.dns())
    }

    @Test
    fun `una WiFi sin internet (portal cautivo) no gana a los datos validados`() {
        val t = NetworkTracker<String>()
        t.up("movil", mobileDns, true, MOVIL)
        t.up("wifi", wifiDns, false, WIFI)
        assertEquals("movil", t.network)
        assertEquals(mobileDns, t.dns())
        assertTrue(t.isValidated())
        t.onCapabilities("wifi", true, WIFI) // inicia sesión en el portal
        assertEquals("wifi", t.network)
        assertEquals(wifiDns, t.dns())
    }

    @Test
    fun `al perder la WiFi se pasa a los datos con sus DNS`() {
        val t = NetworkTracker<String>()
        t.up("wifi", wifiDns, true, WIFI)
        t.up("movil", mobileDns, true, MOVIL)
        t.onLost("wifi")
        assertEquals("movil", t.network)
        assertEquals(mobileDns, t.dns())
    }

    @Test
    fun `al perder todas no queda ninguna`() {
        val t = NetworkTracker<String>()
        t.up("wifi", wifiDns, false, WIFI)
        assertFalse(t.isValidated())
        t.onLost("wifi")
        assertNull(t.network)
        assertEquals(emptyList<InetAddress>(), t.dns())
        assertTrue(t.isValidated())
    }

    @Test
    fun `avisos de una red desconocida se ignoran`() {
        val t = NetworkTracker<String>()
        t.onDns("wifi", wifiDns)
        t.onCapabilities("wifi", true, WIFI)
        assertNull(t.network)
        t.up("wifi", wifiDns, true, WIFI)
        t.onAvailable("wifi") // repetido: no borra lo que ya se sabe
        assertEquals(wifiDns, t.dns())
    }
}
