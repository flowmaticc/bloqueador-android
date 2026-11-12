package app.bloqueador.history

import app.bloqueador.filter.BlocklistParser
import app.bloqueador.filter.FilterEngine
import app.bloqueador.settings.HistoryRetention
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    private val matcher = BlocklistParser.parse(
        sequenceOf("||ads.com^", "0.0.0.0 exacto.org", "||tracker.net^", "@@||ok.tracker.net^")
    ).build()

    @Test
    fun `devuelve la regla que bloquea, escrita como en la lista`() {
        assertEquals("||ads.com^", matcher.matchRule("x.y.ads.com"))
        assertEquals("exacto.org", matcher.matchRule("exacto.org"))
        assertEquals("||tracker.net^", matcher.matchRule("tracker.net"))
        assertNull(matcher.matchRule("ok.tracker.net"))
        assertNull(matcher.matchRule("wikipedia.org"))
        assertEquals("@@||ok.tracker.net^", matcher.allowRule("api.ok.tracker.net"))
        assertEquals("||use-application-dns.net^", matcher.matchRule("use-application-dns.net"))
    }

    @Test
    fun `el motor cachea también la regla`() {
        val engine = FilterEngine()
        engine.update(FilterEngine.Filters(matcher))
        assertEquals("||ads.com^", engine.decide("a.ads.com"))
        assertEquals("||ads.com^", engine.decide("a.ads.com"))
        assertNull(engine.decide("libre.org"))
        assertNull(engine.decide("libre.org"))
        assertEquals(2, engine.cacheHits.get())
        assertEquals(2, engine.blocked.get())
        assertEquals(4, engine.queries.get())
    }

    @Test
    fun `agrupa consultas iguales y conserva la primera hora`() {
        val records = listOf(
            DnsRecord(300, "ads.com", "com.app", true, "||ads.com^"),
            DnsRecord(100, "ads.com", "com.app", true, "||ads.com^"),
            DnsRecord(200, "ads.com", "com.otra", true, "||ads.com^"),
            DnsRecord(150, "wiki.org", "com.app", false, null),
            DnsRecord(400, "wiki.org", "com.app", false, null),
        )
        val events = HistoryRecorder.aggregate(records)
        assertEquals(3, events.size)
        val first = events[0]
        assertEquals(100L, first.timestamp)
        assertEquals("com.app", first.appPackage)
        assertEquals(2, first.count)
        assertEquals("||ads.com^", first.matchedRule)
        assertEquals(listOf(100L, 150L, 200L), events.map { it.timestamp })
        assertTrue(events.all { it.source == "dns" })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `escribe por lotes - una sola escritura por ventana`() = runTest {
        val writes = mutableListOf<List<HistoryEvent>>()
        val recorder = HistoryRecorder(this, flushDelayMs = 5_000) { writes += it }
        repeat(10) { recorder.record(DnsRecord(it.toLong(), "ads.com", "com.app", true, "||ads.com^")) }
        recorder.record(DnsRecord(20, "otro.com", null, false, null))
        runCurrent()
        assertEquals(0, writes.size) // nada hasta que pasa la ventana
        advanceTimeBy(5_001)
        assertEquals(1, writes.size)
        assertEquals(2, writes[0].size)
        assertEquals(10, writes[0].first { it.domain == "ads.com" }.count)

        // Sin consultas nuevas no hay más escrituras.
        advanceTimeBy(60_000)
        assertEquals(1, writes.size)
    }

    @Test
    fun `duraciones del historial`() {
        assertEquals(3 * 3_600_000L, HistoryRetention.H3.millis)
        assertEquals(listOf(1L, 3L, 6L, 24L), HistoryRetention.entries.map { it.hours })
    }
}
