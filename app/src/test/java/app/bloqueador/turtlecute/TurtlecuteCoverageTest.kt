package app.bloqueador.turtlecute

import app.bloqueador.filter.BlocklistParser
import app.bloqueador.filter.DomainMatcher
import app.bloqueador.lists.BlockMode
import app.bloqueador.lists.ExtraLayers
import app.bloqueador.lists.ListSource
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cobertura de nuestras listas frente al test público https://adblock.turtlecute.org
 * (repositorio github.com/Turtlecute33/adblocktest).
 *
 * El test de la web tiene tres partes: filtros cosméticos, scripts de anuncios y dominios. Un filtro
 * DNS solo puede actuar sobre los dominios, así que aquí se comprueba esa parte: se descarga su
 * lista de dominios (`d3host.txt`, 127 dominios en secciones Ads, Analytics, OEMs…) y se mira cuáles
 * bloquea cada modo y cada capa extra. El resultado es el porcentaje por sección y los dominios
 * que faltan.
 *
 * Necesita red, por eso no corre en la batería normal de tests:
 *   gradlew testDebugUnitTest --tests '*TurtlecuteCoverageTest' -Pturtlecute
 * El informe queda en app/build/reports/turtlecute.md.
 *
 * La lista de turtlecute tiene licencia CC BY-NC-SA, distinta de la de este proyecto: por eso se
 * descarga al ejecutar y no se copia en el repositorio.
 */
class TurtlecuteCoverageTest {

    data class TestDomain(val section: String, val vendor: String, val domain: String)

    @Test
    fun `informe de cobertura`() {
        assumeTrue("Solo con -Pturtlecute", System.getProperty("turtlecute") == "true")

        val domains = parseTestList(download(TEST_LIST_URL))
        check(domains.size >= 50) { "La lista de turtlecute parece incompleta: ${domains.size}" }

        val lists = (BlockMode.entries.map { it.source } + ExtraLayers.all)
            .associateWith { src -> BlocklistParser.parse(download(src.url).lineSequence()).build() }

        val report = StringBuilder()
        report.appendLine("# Cobertura frente a adblock.turtlecute.org (parte de dominios)\n")
        report.appendLine("${domains.size} dominios de prueba · lista: $TEST_LIST_URL\n")

        // 1) Cada modo solo, y cada modo con todas las capas extra.
        val sections = domains.map { it.section }.distinct()
        report.appendLine("| Configuración | Total | " + sections.joinToString(" | ") + " |")
        report.appendLine("|---|---|" + sections.joinToString("|") { "---" } + "|")
        val combos = BlockMode.entries.flatMap { mode ->
            listOf(
                mode.label to listOf(mode.source),
                "${mode.label} + Anti-evasión DNS" to listOf(mode.source, ExtraLayers.all.first { it.id == "doh" }),
                "${mode.label} + todas las capas" to listOf(mode.source) + ExtraLayers.all,
            )
        }
        for ((name, sources) in combos) {
            val matchers = sources.map { lists.getValue(it) }
            val blocked = domains.filter { d -> matchers.any { it.isBlocked(d.domain) } }.toSet()
            val cells = sections.map { s -> pct(blocked.count { it.section == s }, domains.count { it.section == s }) }
            report.appendLine("| $name | **${pct(blocked.size, domains.size)}** | " + cells.joinToString(" | ") + " |")
        }

        // 2) Capas sueltas, para ver qué aporta cada una.
        report.appendLine("\n## Cada lista por separado\n")
        for ((src, m) in lists) report.appendLine("- ${src.label}: ${pct(domains.count { m.isBlocked(it.domain) }, domains.size)}")

        // 3) Lo que se escapa al modo recomendado (Equilibrado) y quién lo cubriría.
        report.appendLine("\n## Dominios que no bloquea el modo Equilibrado\n")
        val balanced = lists.getValue(BlockMode.BALANCED.source)
        for (d in domains.filter { !balanced.isBlocked(it.domain) }) {
            val who = lists.filter { (_, m) -> m.isBlocked(d.domain) }.keys.joinToString { it.label }
            report.appendLine("- ${d.section} / ${d.vendor}: `${d.domain}`" + if (who.isEmpty()) " — ninguna lista" else " — sí en: $who")
        }

        println(report)
        System.getProperty("turtlecute.report")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(report.toString()) }
    }

    @Test
    fun `lee las secciones y dominios del formato hosts de turtlecute`() {
        val sample = """
            # Title: Turtlecute Host List
            #====== Ads ======
            # --- Google Ads
            0.0.0.0 pagead2.googlesyndication.com
            0.0.0.0 adservice.google.com
            #====== OEMs ======
            # --- Samsung
            0.0.0.0 samsungads.com
        """.trimIndent()
        val parsed = parseTestList(sample)
        assertEquals(
            listOf(
                TestDomain("Ads", "Google Ads", "pagead2.googlesyndication.com"),
                TestDomain("Ads", "Google Ads", "adservice.google.com"),
                TestDomain("OEMs", "Samsung", "samsungads.com"),
            ),
            parsed,
        )
    }

    companion object {
        const val TEST_LIST_URL = "https://adblock.turtlecute.org/d3host.txt"

        private val SECTION = Regex("""^#=+\s*(.+?)\s*=+\s*$""")
        private val VENDOR = Regex("""^#\s*-{2,}\s*(.+?)\s*$""")

        fun parseTestList(text: String): List<TestDomain> {
            var section = "?"
            var vendor = "?"
            val out = mutableListOf<TestDomain>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                val sec = SECTION.find(line)
                val ven = VENDOR.find(line)
                if (sec != null) { section = sec.groupValues[1]; vendor = "?" } else if (ven != null) vendor = ven.groupValues[1]
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("\\s+"))
                val host = (if (parts.size >= 2) parts[1] else parts[0]).lowercase()
                if ('.' in host) out += TestDomain(section, vendor, host)
            }
            return out.distinctBy { it.domain }
        }

        private fun pct(n: Int, total: Int) = if (total == 0) "–" else "${n * 100 / total} % ($n/$total)"

        private fun download(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "Bloqueador-tests")
            return conn.inputStream.bufferedReader().use { it.readText() }
        }
    }
}
