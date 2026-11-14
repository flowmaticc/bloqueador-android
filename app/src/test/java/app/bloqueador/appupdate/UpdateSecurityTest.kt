package app.bloqueador.appupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class UpdateSecurityTest {

    @Test
    fun `solo se descarga por HTTPS y de GitHub`() {
        assertTrue(AppUpdater.isTrustedUrl("https://github.com/flowmaticc/bloqueador-android/releases/download/v1/app.apk"))
        assertTrue(AppUpdater.isTrustedUrl("https://objects.githubusercontent.com/algo"))
        assertTrue(AppUpdater.isTrustedUrl("https://api.github.com/repos/x/y/releases"))

        assertFalse("sin cifrar", AppUpdater.isTrustedUrl("http://github.com/x.apk"))
        assertFalse("dominio parecido", AppUpdater.isTrustedUrl("https://github.com.evil.example/x.apk"))
        assertFalse("sufijo pegado", AppUpdater.isTrustedUrl("https://evilgithub.com/x.apk"))
        assertFalse("usuario en la URL", AppUpdater.isTrustedUrl("https://github.com@evil.example/x.apk"))
        assertFalse("credenciales", AppUpdater.isTrustedUrl("https://user:pass@github.com/x.apk"))
        assertFalse("otro esquema", AppUpdater.isTrustedUrl("file:///sdcard/x.apk"))
        assertFalse(AppUpdater.isTrustedUrl("javascript:alert(1)"))
        assertFalse(AppUpdater.isTrustedUrl(""))
        assertFalse(AppUpdater.isTrustedUrl("no es una url"))
    }

    @Test
    fun `las respuestas se leen con limite de tamano`() {
        assertEquals(1000, AppUpdater.readLimited(ByteArrayInputStream(ByteArray(1000)), 1000).size)
        assertThrows(IOException::class.java) { AppUpdater.readLimited(ByteArrayInputStream(ByteArray(1001)), 1000) }
    }

    @Test
    fun `un manifiesto que apunta a un fichero que no esta en la publicacion se rechaza`() {
        val release = Release(
            tag = "v9.9.9", prerelease = false, draft = false, publishedAt = java.time.Instant.EPOCH, notes = "",
            assets = mapOf("update.json" to "https://github.com/x/update.json", "app.apk" to "https://github.com/x/app.apk"),
        )
        val bad = """{"versionCode": 9, "versionName": "9", "channel": "stable", "apk": "../../otro.apk", "sha256": "AB"}"""
        assertThrows(IllegalStateException::class.java) { UpdateParser.parseManifest(bad, release) }

        val good = """{"versionCode": 9, "versionName": "9", "channel": "stable", "apk": "app.apk", "sha256": "AB"}"""
        val info = UpdateParser.parseManifest(good, release)
        assertEquals("https://github.com/x/app.apk", info.apkUrl)
        assertEquals("la huella se compara en minúsculas", "ab", info.sha256)
    }
}
