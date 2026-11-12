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
    fun `cada publicación se clasifica en su canal`() {
        assertEquals(
            listOf(UpdateChannel.NIGHTLY, UpdateChannel.BETA, UpdateChannel.STABLE, UpdateChannel.STABLE, UpdateChannel.STABLE),
            releases.map { it.channel },
        )
    }

    @Test
    fun `Estable solo ve versiones finales publicadas`() {
        assertEquals("v0.2.0", UpdateParser.pick(releases, UpdateChannel.STABLE)?.tag) // no el borrador ni sin manifiesto
    }

    @Test
    fun `Beta incluye estables y betas, Nightly lo incluye todo`() {
        assertEquals("v0.3.0-beta.1", UpdateParser.pick(releases, UpdateChannel.BETA)?.tag)
        assertEquals("nightly", UpdateParser.pick(releases, UpdateChannel.NIGHTLY)?.tag)
        assertTrue(UpdateChannel.NIGHTLY.accepts(UpdateChannel.STABLE))
        assertTrue(!UpdateChannel.STABLE.accepts(UpdateChannel.BETA))
    }

    @Test
    fun `sin publicaciones no hay nada que ofrecer`() {
        assertNull(UpdateParser.pick(emptyList(), UpdateChannel.NIGHTLY))
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
        assertEquals(UpdateChannel.NIGHTLY, info.channel)
    }
}

class PlainTextTest {
    @Test
    fun `quita el formato Markdown de las notas de GitHub`() {
        val md = "## Novedades\n* **Rápido**: menos CPU\n**Full Changelog**: [v0.1.0](https://x)"
        assertEquals("Novedades\n• Rápido: menos CPU\nFull Changelog: v0.1.0", UpdateParser.plainText(md))
    }
}
