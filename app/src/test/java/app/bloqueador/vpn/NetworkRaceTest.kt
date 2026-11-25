package app.bloqueador.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Avisos de red de varios hilos a la vez y en cualquier orden: nunca hay excepciones y lo que se lee
 * es siempre coherente (los DNS de una red real, nunca los de una red ya perdida al terminar).
 */
class NetworkRaceTest {

    private val dnsOf = mapOf(
        "wifi" to listOf(InetAddress.getByName("192.168.1.1")),
        "movil" to listOf(InetAddress.getByName("10.0.0.53")),
        "cable" to listOf(InetAddress.getByName("172.16.0.1")),
    )
    private val rank = mapOf("wifi" to 2, "movil" to 1, "cable" to 3)

    private fun race(exclusive: Boolean) {
        val t = NetworkTracker<String>()
        val error = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val writers = (1..4).map { seed ->
            Thread {
                val rnd = Random(seed)
                start.await()
                repeat(20_000) {
                    val n = dnsOf.keys.random(rnd)
                    runCatching {
                        when (rnd.nextInt(4)) {
                            0 -> t.onAvailable(n, exclusive)
                            1 -> t.onDns(n, dnsOf.getValue(n))
                            2 -> t.onCapabilities(n, rnd.nextBoolean(), rank.getValue(n))
                            else -> t.onLost(n)
                        }
                    }.onFailure { error.compareAndSet(null, it) }
                }
            }
        }
        val reader = Thread {
            start.await()
            repeat(50_000) {
                runCatching {
                    val dns = t.dns()
                    // Los DNS son siempre los de una sola red (o ninguno), nunca una mezcla.
                    check(dns.isEmpty() || dns in dnsOf.values) { "DNS mezclados: $dns" }
                    t.isValidated()
                }.onFailure { error.compareAndSet(null, it) }
            }
        }
        (writers + reader).forEach { it.start() }
        start.countDown()
        (writers + reader).forEach { it.join() }
        error.get()?.let { throw it }

        // Al final, con todas perdidas, no queda ninguna red ni sus DNS.
        dnsOf.keys.forEach(t::onLost)
        assertEquals(null, t.network)
        assertTrue(t.dns().isEmpty())
        assertTrue(t.isValidated())
    }

    @Test
    fun `Android 12+ (la red la elige Android)`() = race(exclusive = true)

    @Test
    fun `Android 8 a 11 (se escuchan todas)`() = race(exclusive = false)
}
