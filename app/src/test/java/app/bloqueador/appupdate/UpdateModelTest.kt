package app.bloqueador.appupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateModelTest {

    /** Respuesta de la API de GitHub Releases, recortada a los campos que se usan. */
    private val releasesJson = """
    [
      {"tag_name":"nightly","prerelease":true,"draft":false,"published_at":"2026-11-20T10:00:00Z","body":"Último cambio",
       "assets":[{"name":"update.json","browser_download_url":"https://x/n/update.json"},
                 {"name":"bloqueador-0.3.0-nightly.apk","browser_download_url":"https://x/n/app.apk"}]},
      {"tag_name":"v0.3.0-beta.1","prerelease":true,"draft":false,"published_at":"2026-11-18T10:00:00Z","body":"Beta",
       "assets":[{"name":"update.json","browser_download_url":"https://x/b/update.json"}]},
      {"tag_name":"v0.2.0","prerelease":false,"draft":false,"published_at":"2026-11-15T10:00:00Z","body":"Estable",
       "assets":[{"name":"update.json","browser_download_url":"https://x/s/update.json"}]},
      {"tag_name":"v0.4.0","prerelease":false,"draft":true,"published_at":"2026-11-25T10:00:00Z","body":"Borrador",
       "assets":[{"name":"update.json","browser_download_url":"https://x/d/update.json"}]},
      {"tag_name":"v0.1.0","prerelease":false,"draft":false,"published_at":"2026-11-10T10:00:00Z","body":"Sin manifiesto",
       "assets":[]}
    ]
    """

    private val releases = UpdateParser.parseReleases(releasesJson)

    @Test
    fun `cada publicación se clasifica en su canal (la antigua nightly cuenta como beta)`() {
        assertEquals(
            listOf(UpdateChannel.BETA, UpdateChannel.BETA, UpdateChannel.STABLE, UpdateChannel.STABLE, UpdateChannel.STABLE),
            releases.map { it.channel },
        )
    }

    @Test
    fun `Estable solo ve versiones finales publicadas`() {
        assertEquals("v0.2.0", UpdateParser.pick(releases, UpdateChannel.STABLE)?.tag) // no el borrador ni sin manifiesto
    }

    @Test
    fun `Beta incluye estables y betas`() {
        assertEquals("nightly", UpdateParser.pick(releases, UpdateChannel.BETA)?.tag)
        assertTrue(UpdateChannel.BETA.accepts(UpdateChannel.STABLE))
        assertTrue(!UpdateChannel.STABLE.accepts(UpdateChannel.BETA))
    }

    @Test
    fun `sin publicaciones no hay nada que ofrecer`() {
        assertNull(UpdateParser.pick(emptyList(), UpdateChannel.BETA))
    }

    @Test
    fun `el canal Nightly guardado por versiones anteriores pasa a Beta`() {
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromSaved("NIGHTLY"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromSaved("BETA"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromSaved(null))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromSaved("raro"))
    }

    @Test
    fun `el manifiesto apunta al APK adjunto y guarda la huella en minúsculas`() {
        val nightly = releases.first()
        val info = UpdateParser.parseManifest(
            """{"versionCode":1042,"versionName":"0.3.0-nightly.20261120","apk":"bloqueador-0.3.0-nightly.apk","sha256":"ABCDEF"}""",
            nightly,
        )
        assertEquals(1042L, info.versionCode)
        assertEquals("https://x/n/app.apk", info.apkUrl)
        assertEquals("abcdef", info.sha256)
        assertEquals(UpdateChannel.BETA, info.channel)
        assertEquals("sin notas en el manifiesto, las de la publicación", "Último cambio", info.notes)
    }

    private val base = "https://github.com"
    private val repo = "flowmaticc/bloqueador-android"

    @Test
    fun `update json estatico con notas, aviso y version segura`() {
        val json = """{"versionCode":2100,"versionName":"0.4.0","channel":"stable","tag":"v0.4.0",
            "apk":"bloqueador-0.4.0.apk","sha256":"AB12","notes":"## Novedades\n- **Rápido**","notes_en":"- **Fast**",
            "notice":"Corrige el aviso de VPN","minSafeVersionCode":2050}"""
        val info = UpdateParser.parseStaticManifest(json, base, repo)
        assertEquals("https://github.com/flowmaticc/bloqueador-android/releases/download/v0.4.0/bloqueador-0.4.0.apk", info.apkUrl)
        assertEquals(UpdateChannel.STABLE, info.channel)
        assertEquals("Novedades\n• Rápido", info.notes)
        assertEquals("Novedades\n• Rápido", info.notesFor("es"))
        assertEquals("• Fast", info.notesFor("en"))
        assertEquals("otros idiomas: las inglesas", "• Fast", info.notesFor("fr"))
        assertEquals("sin notas en inglés: las españolas", info.notes, info.copy(notesEn = "").notesFor("en"))
        assertEquals("Corrige el aviso de VPN", info.notice)
        assertTrue(info.isUrgentFor(2048))
        assertTrue(!info.isUrgentFor(2050))
    }

    @Test
    fun `un update json estatico no puede apuntar fuera de su publicacion`() {
        fun manifest(tag: String, apk: String) =
            """{"versionCode":1,"versionName":"1","channel":"beta","tag":"$tag","apk":"$apk","sha256":"a"}"""
        for ((tag, apk) in listOf("beta" to "../x.apk", "beta" to "a/b.apk", "../v1" to "x.apk", "beta" to "x.exe", "beta" to "x.apk?y=1")) {
            org.junit.Assert.assertThrows("$tag $apk", IllegalStateException::class.java) {
                UpdateParser.parseStaticManifest(manifest(tag, apk), base, repo)
            }
        }
        // El formato anterior (sin "tag") no vale: se usará la API.
        org.junit.Assert.assertThrows(Exception::class.java) {
            UpdateParser.parseStaticManifest("""{"versionCode":1,"versionName":"1","apk":"x.apk","sha256":"a"}""", base, repo)
        }
    }

    @Test
    fun `Beta ofrece la mas alta entre beta y estable`() {
        fun info(code: Long, ch: UpdateChannel) = UpdateInfo(code, "$code", "u", "s", ch, "")
        assertEquals(2101L, UpdateParser.newest(listOf(info(2100, UpdateChannel.STABLE), info(2101, UpdateChannel.BETA)))?.versionCode)
        assertEquals(2200L, UpdateParser.newest(listOf(info(2200, UpdateChannel.STABLE), info(2101, UpdateChannel.BETA)))?.versionCode)
        assertNull(UpdateParser.newest(emptyList()))
    }
}

class PlainTextTest {
    @Test
    fun `quita el formato Markdown de las notas de GitHub`() {
        val md = "## Novedades\n* **Rápido**: menos CPU\n**Full Changelog**: [v0.1.0](https://x)"
        assertEquals("Novedades\n• Rápido: menos CPU\nFull Changelog: v0.1.0", UpdateParser.plainText(md))
    }

    @Test
    fun `las lineas sangradas continuan la frase anterior`() {
        val md = "Intro:\n\n- **Corregido**: si una lista no se puede descargar, el ajuste vuelve a como\n" +
            "  estaba y se avisa.\n- Otra \"mejora\" con comillas.\n\n\n\nFin"
        assertEquals(
            "Intro:\n\n• Corregido: si una lista no se puede descargar, el ajuste vuelve a como estaba y se avisa.\n" +
                "• Otra \"mejora\" con comillas.\n\nFin",
            UpdateParser.plainText(md),
        )
    }
}
