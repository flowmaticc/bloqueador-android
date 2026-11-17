package app.bloqueador.appupdate

import app.bloqueador.R
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.bloqueador.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Resultado de buscar actualizaciones. */
sealed interface UpdateCheck {
    data class Available(val info: UpdateInfo) : UpdateCheck
    data object UpToDate : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

/**
 * Actualizaciones de la propia app desde GitHub Releases (la app no está en Google Play).
 *
 * 1. Lee el `update.json` del canal (fichero estático de la publicación: versión, APK, huella SHA-256,
 *    notas y avisos). Beta lee también el de la estable y se queda con la versión más alta.
 *    Si no se puede leer, lo busca a través de la API de GitHub (respaldo).
 * 2. Si es más nueva que la instalada, descarga el APK, comprueba la huella e instala con
 *    `PackageInstaller`. Android solo acepta la actualización si va firmada con la misma clave.
 */
class AppUpdater(private val context: Context, private val repo: String = BuildConfig.UPDATE_REPO) {

    private val dir = File(context.cacheDir, "updates")

    val installedVersionCode: Long
        get() {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }

    suspend fun check(channel: UpdateChannel): UpdateCheck = withContext(Dispatchers.IO) {
        try {
            val info = fromStaticFiles(channel) ?: fromApi(channel) ?: return@withContext UpdateCheck.UpToDate
            if (info.versionCode > installedVersionCode) UpdateCheck.Available(info) else UpdateCheck.UpToDate
        } catch (e: HttpStatus) {
            UpdateCheck.Failed(
                if (e.code == 404) context.getString(R.string.upd_err_not_found)
                else context.getString(R.string.upd_err_github, e.code)
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al buscar actualizaciones", e)
            UpdateCheck.Failed(context.getString(R.string.upd_err_check, e.message ?: context.getString(R.string.upd_err_offline)))
        }
    }

    /**
     * Lo normal: los update.json estáticos del canal. null si no se pudo leer ninguno (se probará la
     * API). Un canal sin publicación todavía (404) cuenta como "no hay nada", no como fallo.
     */
    private fun fromStaticFiles(channel: UpdateChannel): UpdateInfo? {
        val base = BuildConfig.UPDATE_DOWNLOAD_BASE
        val urls = buildList {
            add(UpdateUrls.stableManifest(base, repo))
            if (channel == UpdateChannel.BETA) add(UpdateUrls.betaManifest(base, repo))
        }
        var failed = false
        val found = urls.mapNotNull { url ->
            try {
                UpdateParser.parseStaticManifest(get(requireTrusted(url)), base, repo)
            } catch (e: HttpStatus) {
                if (e.code != 404) failed = true // 404: ese canal aún no tiene publicación
                null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Sin red, o un update.json del formato anterior (sin "tag"): se prueba con la API.
                Log.d(TAG, "update.json no disponible ($url): ${e.message}")
                failed = true
                null
            }
        }
        if (failed) return null
        return UpdateParser.newest(found.filter { channel.accepts(it.channel) })
            ?: UpdateInfo(0, "", "", "", channel, "") // nada publicado en el canal: "al día"
    }

    /** Respaldo: la API de GitHub (tiene límite de peticiones por IP, por eso no es lo primero). */
    private fun fromApi(channel: UpdateChannel): UpdateInfo? {
        val releases = UpdateParser.parseReleases(get("${BuildConfig.UPDATE_API_BASE}/repos/$repo/releases?per_page=30"))
        val release = UpdateParser.pick(releases, channel) ?: return null
        return UpdateParser.parseManifest(get(requireTrusted(release.manifestUrl!!)), release)
    }

    /** Descarga el APK y verifica su huella. [progress] recibe 0..1. */
    suspend fun download(info: UpdateInfo, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() } // solo se guarda la última descarga
        val file = File(dir, "bloqueador-${info.versionCode}.apk")
        val conn = open(requireTrusted(info.apkUrl))
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 }
            if (total != null && total > MAX_APK_BYTES) throw IOException(context.getString(R.string.upd_err_too_big))
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (read > MAX_APK_BYTES) throw IOException(context.getString(R.string.upd_err_too_big))
                        if (total != null) progress(read.toFloat() / total)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != info.sha256) {
                file.delete()
                throw IOException(context.getString(R.string.upd_err_hash))
            }
            if (!signedLikeThisApp(file)) {
                file.delete()
                throw IOException(context.getString(R.string.upd_err_signature))
            }
            file
        } finally {
            conn.disconnect()
        }
    }

    /** ¿Ha permitido el usuario que esta app instale actualizaciones? (Android 8+: "Instalar apps desconocidas"). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Instala [apk]. Android pide confirmación al usuario (salvo actualizaciones propias en Android 12+). */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /**
     * Antes de pasarle nada al instalador: el APK tiene que ser de esta misma app y estar firmado con
     * el mismo certificado. Android lo comprobaría igualmente al instalar; así el fallo es claro y no
     * se llega a abrir el instalador con un fichero ajeno.
     */
    private fun signedLikeThisApp(apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, 0) ?: return false
        if (archive.packageName != context.packageName) return false
        val mine = certificates { pm.getPackageInfo(context.packageName, it) }
        val theirs = certificates { pm.getPackageArchiveInfo(apk.path, it) }
        // En algunas versiones de Android (9 y 10) no se puede leer la firma de un APK sin instalar.
        // En ese caso decide el instalador de Android, que rechaza cualquier APK firmado con otra clave.
        if (mine.isEmpty() || theirs.isEmpty()) return true
        theirs.any { it in mine }
    }.getOrDefault(false)

    /**
     * Huellas SHA-256 de los certificados de firma. Prueba primero la API moderna y, si no devuelve
     * nada (pasa con APK sin instalar en Android 9 y 10), la antigua.
     */
    private fun certificates(load: (Int) -> android.content.pm.PackageInfo?): Set<String> {
        fun digest(sigs: Array<android.content.pm.Signature>?): Set<String> = sigs.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
        if (Build.VERSION.SDK_INT >= 28) {
            val info = runCatching { load(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
            val modern = digest(info?.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            })
            if (modern.isNotEmpty()) return modern
        }
        @Suppress("DEPRECATION")
        val info = runCatching { load(android.content.pm.PackageManager.GET_SIGNATURES) }.getOrNull()
        @Suppress("DEPRECATION")
        return digest(info?.signatures)
    }

    private class HttpStatus(val code: Int) : IOException("HTTP $code")

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Garbillo-Android")
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        }

    private fun get(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw HttpStatus(conn.responseCode)
            return conn.inputStream.use { readLimited(it, MAX_JSON_BYTES) }.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    /** En la versión publicada solo se descarga por HTTPS y de GitHub. */
    private fun requireTrusted(url: String): String {
        if (BuildConfig.DEBUG && url.startsWith("http://127.0.0.1")) return url
        if (!isTrustedUrl(url)) throw IOException(context.getString(R.string.upd_err_url))
        return url
    }

    companion object {
        const val TAG = "AppUpdater"
        private const val MAX_APK_BYTES = 60L * 1024 * 1024
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024

        private val TRUSTED_HOSTS = listOf("github.com", "githubusercontent.com")

        /** HTTPS y un dominio de GitHub (o un subdominio suyo). */
        fun isTrustedUrl(url: String): Boolean = runCatching {
            val u = java.net.URI(url)
            val host = u.host?.lowercase() ?: return false
            u.scheme == "https" && u.userInfo == null && TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
        }.getOrDefault(false)

        /** Lee como mucho [max] bytes; si hay más, falla (protege de respuestas enormes). */
        fun readLimited(input: java.io.InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > max) throw IOException("Respuesta demasiado grande.")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}

/** Último resultado de instalación, para que la pantalla de Ajustes lo muestre. */
object InstallStatus {
    val message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

/** Recibe el resultado de la instalación; si Android necesita confirmación, abre su diálogo. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(AppUpdater.TAG, "Actualización instalada")
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallStatus.message.value = context.getString(R.string.upd_install_cancelled)
            else -> {
                Log.w(
                    AppUpdater.TAG,
                    "Instalación fallida ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}",
                )
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        context.getString(R.string.upd_install_conflict)
                    PackageInstaller.STATUS_FAILURE_STORAGE -> context.getString(R.string.upd_install_storage)
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> context.getString(R.string.upd_install_blocked)
                    else -> context.getString(R.string.upd_install_failed)
                }
                InstallStatus.message.value = message
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
