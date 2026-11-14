package app.bloqueador.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Consultas desde varios hilos mientras se recargan las listas (como al añadir una excepción). */
class FilterConcurrencyTest {

    private val blocking = BlocklistParser.parse(sequenceOf("||ads.example^")).build()
    private val allowing = BlocklistParser.parse(sequenceOf("||otro.example^")).build()

    @Test
    fun `consultas y recargas a la vez no fallan ni dejan resultados viejos en la cache`() {
        val engine = FilterEngine(cacheSize = 64)
        engine.update(FilterEngine.Filters(blocking))
        val pool = Executors.newFixedThreadPool(8)
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(8)
        repeat(8) { worker ->
            pool.execute {
                try {
                    repeat(20_000) { i ->
                        if (worker == 0 && i % 50 == 0) {
                            engine.update(FilterEngine.Filters(if (i % 100 == 0) blocking else allowing))
                        } else {
                            engine.decide(if (i % 2 == 0) "ads.example" else "s$i.ads.example")
                        }
                    }
                } catch (t: Throwable) {
                    error.set(t)
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(60, TimeUnit.SECONDS))
        pool.shutdown()
        error.get()?.let { throw it }

        // Tras la última recarga manda la lista nueva, aunque hubiera consultas a mitad.
        engine.update(FilterEngine.Filters(allowing))
        assertFalse(engine.isBlocked("ads.example"))
        engine.update(FilterEngine.Filters(blocking))
        assertTrue(engine.isBlocked("ads.example"))
    }

    @Test
    fun `los contadores no pierden consultas entre hilos`() {
        val engine = FilterEngine()
        engine.update(FilterEngine.Filters(blocking))
        val pool = Executors.newFixedThreadPool(4)
        repeat(4) { pool.execute { repeat(10_000) { engine.decide("ads.example") } } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(40_000, engine.queries.get())
        assertEquals(40_000, engine.blocked.get())
    }
}
