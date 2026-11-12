package app.bloqueador.turtlecute

import app.bloqueador.filter.BlocklistParser
import app.bloqueador.lists.BlockMode
import app.bloqueador.lists.ExtraLayers
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cobertura de nuestras listas frente a dominios recogidos de webs reales o de tests (canyoublockit,
 * obfusgated, adblock-tester, un periódico…). Cada fichero de la carpeta es un "sitio": dominios
 * separados por espacios o saltos de línea, opcionalmente `categoría<TAB>dominio` por línea.
 *
 *   gradlew :app:testDebugUnitTest --tests '*SiteCoverageTest' -PsiteHosts=<carpeta>
 *
 * Informe en app/build/reports/sites.md. Necesita red (descarga las listas).
 */
class SiteCoverageTest {

    @Test
    fun `informe por sitio`() {
        val dir = System.getProperty("siteHosts").orEmpty()
        assumeTrue("Solo con -PsiteHosts=<carpeta>", dir.isNotEmpty())

        val sources = BlockMode.entries.map { it.source } + ExtraLayers.all
        val lists = sources.associateWith { BlocklistParser.parse(download(it.url).lineSequence()).build() }
        val combos = BlockMode.entries.flatMap { mode ->
            listOf(
                mode.label to listOf(mode.source),
                "${mode.label} + GoodbyeAds" to listOf(mode.source, ExtraLayers.all.first { it.id == "goodbyeads" }),
                "${mode.label} + todas" to listOf(mode.source) + ExtraLayers.all,
            )
        }

        val out = StringBuilder("# Cobertura por sitio\n")
        for (file in File(dir).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }) {
            val entries = file.readLines().flatMap { line ->
                if ('\t' in line) listOf(line.substringBefore('\t') to line.substringAfter('\t').trim())
                else line.split(' ').filter { it.isNotBlank() }.map { "" to it.trim() }
            }.distinctBy { it.second }
            val cats = entries.map { it.first }.distinct().filter { it.isNotEmpty() }
            out.appendLine("\n## ${file.nameWithoutExtension} (${entries.size} dominios)\n")
            out.appendLine("| Configuración | Total |" + cats.joinToString("") { " $it |" })
            out.appendLine("|---|---|" + cats.joinToString("") { "---|" })
            for ((name, srcs) in combos) {
                val ms = srcs.map { lists.getValue(it) }
                val hit = entries.filter { (_, d) -> ms.any { it.isBlocked(d) } }
                val cells = cats.map { c -> pct(hit.count { it.first == c }, entries.count { it.first == c }) }
                out.appendLine("| $name | **${pct(hit.size, entries.size)}** |" + cells.joinToString("") { " $it |" })
            }
            val balanced = lists.getValue(BlockMode.BALANCED.source)
            val strict = lists.getValue(BlockMode.STRICT.source)
            out.appendLine("\nNo bloqueados por Equilibrado:")
            entries.filter { !balanced.isBlocked(it.second) }.forEach { (c, d) ->
                val who = lists.filter { (_, m) -> m.isBlocked(d) }.keys.joinToString { it.label }
                out.appendLine("- ${if (c.isNotEmpty()) "$c: " else ""}`$d`" +
                    (if (strict.isBlocked(d)) " (Estricto sí)" else "") +
                    if (who.isNotEmpty()) " — $who" else " — ninguna lista")
            }
        }
        println(out)
        File(System.getProperty("turtlecute.report")!!).resolveSibling("sites.md").writeText(out.toString())
    }

    private fun pct(n: Int, t: Int) = if (t == 0) "–" else "${n * 100 / t} % ($n/$t)"

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
