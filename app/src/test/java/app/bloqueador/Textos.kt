package app.bloqueador

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Para los tests (sin móvil): resuelve los [UiText] leyendo los ficheros de textos reales
 * (res/values/strings.xml para inglés, res/values-es/strings.xml para español…). Así los tests
 * comprueban la app en cada idioma con lo que de verdad verá el usuario.
 */
class Textos(lang: String) {
    private val strings: Map<String, String>

    init {
        fun load(dir: String): Map<String, String> {
            val f = listOf(File("src/main/res/$dir/strings.xml"), File("app/src/main/res/$dir/strings.xml")).first { it.isFile }
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).associate { i ->
                val n = nodes.item(i)
                n.attributes.getNamedItem("name").nodeValue to unescape(n.textContent)
            }
        }
        // Lo que falte en un idioma se ve en inglés, igual que en la app.
        strings = load("values") + if (lang == "en") emptyMap() else load("values-$lang")
    }

    private val names: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    fun get(id: Int, vararg args: Any): String {
        val name = names[id] ?: error("Id de texto desconocido: $id")
        val raw = strings[name] ?: error("Falta el texto $name")
        return if (args.isEmpty()) raw else String.format(raw, *args)
    }

    fun get(text: UiText): String = when (text) {
        is UiText.Res -> get(text.id, *text.args.map { if (it is UiText) get(it) else it }.toTypedArray())
        is UiText.Raw -> text.text
        is UiText.Plural -> error("Plurales: no se usan en este test")
    }

    /** Todas las claves de un idioma (para comprobar que las traducciones están completas). */
    val keys: Set<String> get() = strings.keys

    private fun unescape(s: String): String =
        s.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")
}
