package app.bloqueador.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.bloqueador.BloqueadorApp
import app.bloqueador.R
import app.bloqueador.VpnState
import app.bloqueador.apps.InstalledApps
import app.bloqueador.apps.TunnelApps
import app.bloqueador.apps.computeTunnelApps
import app.bloqueador.dns.DnsMessage
import app.bloqueador.dns.IpPacket
import app.bloqueador.dns.UdpPacket
import app.bloqueador.BuildConfig
import app.bloqueador.debug.QueryLog
import app.bloqueador.filter.DomainMatcher
import app.bloqueador.filter.FilterEngine
import kotlinx.coroutines.withContext
import app.bloqueador.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import app.bloqueador.history.AppResolver
import app.bloqueador.history.DnsRecord
import app.bloqueador.history.HistoryRecorder
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Túnel VPN local que solo recibe DNS: se anuncia un servidor DNS virtual y únicamente esa IP
 * se enruta por la interfaz tun. Las consultas bloqueadas se responden al momento; las demás se
 * reenvían al DNS real de la red con sockets protegidos (fuera del túnel).
 */
class DnsVpnService : VpnService() {

    /** Una interfaz tun abierta, con su hilo lector y la tubería que sirve para despertarlo. */
    private class Tunnel(
        val pfd: ParcelFileDescriptor,
        val wakeRead: FileDescriptor,
        val wakeWrite: FileDescriptor,
    ) {
        val output = FileOutputStream(pfd.fileDescriptor)
        var thread: Thread? = null
    }

    private val app get() = application as BloqueadorApp

    @Volatile
    private var tunnel: Tunnel? = null
    private var upstream: UpstreamDns? = null
    private var forwarder: DnsForwarder? = null
    private var recorder: HistoryRecorder? = null
    private var appResolver: AppResolver? = null
    private var threatAlerts: ThreatAlerts? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                // Solo tiene sentido con el túnel abierto; si no, no hay nada que recargar.
                if (tunnel == null) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                reloadTunnel()
                return START_STICKY
            }
        }
        startInForeground()
        if (tunnel == null) startVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        // Otra app VPN ha tomado el control o el usuario lo ha revocado desde Ajustes:
        // no se vuelve a encender solo.
        app.settings.filterWanted = false
        stopVpn()
    }

    override fun onDestroy() {
        closeAll()
        scope.cancel()
        super.onDestroy()
    }

    private fun startVpn() {
        app.setVpnState(VpnState.Starting)
        app.engine.resetCounters()
        val up = UpstreamDns(this).also { it.start() }
        upstream = up
        forwarder = DnsForwarder(
            protect = { protect(it) },
            protectTcp = { protect(it) },
            upstream = { up.current() },
            reply = ::write,
        ).also { it.start() }
        if (BuildConfig.DEBUG) {
            QueryLog.init(this)
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(5 * 60_000L)
                    val e = app.engine
                    QueryLog.stats(e.queries.get(), e.blocked.get(), e.threatsBlocked.get(), e.cacheHits.get(), forwarder?.inFlight ?: 0)
                }
            }
        }
        appResolver = AppResolver(this)
        threatAlerts = ThreatAlerts(this)
        val history = app.history
        recorder = HistoryRecorder(scope) { history.write(it) }

        // Si las listas ya están en el móvil, se cargan ANTES de abrir el túnel. Si no, las primeras
        // consultas pasarían sin filtrar mientras cargan, y Android guarda esas respuestas en su
        // caché varios minutos: esos anuncios seguirían cargando un rato.
        scope.launch {
            if (app.engine.matcher === DomainMatcher.EMPTY && app.repository.hasListsOnDisk()) {
                runCatching { app.reloadEngine() }.onFailure { Log.w(TAG, "No se pudo cargar la lista", it) }
            }
            withContext(Dispatchers.Main) { openAndStart() }
        }
    }

    private fun openAndStart() {
        if (forwarder == null) return // se detuvo mientras cargaban las listas
        val opened = openTunnel()
        if (Build.VERSION.SDK_INT >= 29) app.settings.alwaysOnVpn = isAlwaysOn
        if (opened == null) {
            // null = la app no tiene permiso VPN (p. ej. otra VPN lo tiene ahora).
            app.setVpnState(VpnState.Error("No se pudo activar la VPN. ¿Hay otra VPN activa?"))
            stopVpn(keepState = true)
            return
        }
        tunnel = opened
        loadBlocklist()
    }

    /**
     * Aplica una nueva selección de apps. Las reglas por app solo se pueden fijar al crear el
     * túnel, así que se abre uno nuevo ANTES de cerrar el anterior para que no haya corte.
     */
    private fun reloadTunnel() {
        val old = tunnel ?: return
        val fresh = openTunnel()
        if (fresh == null) {
            Log.w(TAG, "No se pudo recrear el túnel; se mantiene el anterior")
            return
        }
        tunnel = fresh
        closeTunnel(old)
    }

    private fun openTunnel(): Tunnel? {
        val pfd = try {
            buildTunnel()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo crear el túnel", e)
            null
        } ?: return null
        val pipe = Os.pipe()
        val t = Tunnel(pfd, pipe[0], pipe[1])
        t.thread = Thread({ readLoop(t) }, "dns-tun-reader").also { it.start() }
        return t
    }

    private fun buildTunnel(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setBlocking(true)
            .addAddress(TUN_V4, 24)
            .addDnsServer(DNS_V4)
            .addRoute(DNS_V4, 32)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
        try {
            builder.addAddress(TUN_V6, 64).addDnsServer(DNS_V6).addRoute(DNS_V6, 128)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "IPv6 no disponible en el túnel", e)
        }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false) // hereda la medición de la red real

        val settings = app.settings
        val apps = computeTunnelApps(
            strategy = settings.strategy,
            excluded = settings.excludedApps,
            included = settings.includedApps,
            self = packageName,
            isInstalled = { InstalledApps.isInstalled(this, it) },
        )
        when (apps) {
            is TunnelApps.Disallowed -> apps.packages.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "App excluida no instalada: $pkg")
                }
            }
            is TunnelApps.Allowed -> apps.packages.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "App incluida no instalada: $pkg")
                }
            }
        }
        return builder.establish()
    }

    private fun loadBlocklist() {
        scope.launch {
            app.setVpnState(VpnState.Running(loadingList = true))
            try {
                app.repository.downloadMissing()
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo descargar alguna lista", e)
            }
            try {
                app.reloadEngine()
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo cargar la lista", e)
            }
            if (tunnel != null) app.setVpnState(VpnState.Running(loadingList = false))
        }
    }

    /**
     * Hilo dedicado, dormido en poll() mientras no hay consultas (no consume CPU).
     * Se usa poll() con una tubería de aviso en lugar de un read() bloqueante porque cerrar el
     * descriptor no desbloquea un read() en curso, y el túnel quedaría abierto al detener la VPN.
     */
    private fun readLoop(t: Tunnel) {
        val input = FileInputStream(t.pfd.fileDescriptor)
        val buffer = ByteArray(MAX_PACKET)
        val tunPoll = StructPollfd().apply { fd = t.pfd.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        val wakePoll = StructPollfd().apply { fd = t.wakeRead; events = OsConstants.POLLIN.toShort() }
        val fds = arrayOf(tunPoll, wakePoll)
        try {
            while (true) {
                try {
                    Os.poll(fds, -1)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw e
                }
                if (wakePoll.revents.toInt() != 0) break // nos piden parar
                if (tunPoll.revents.toInt() and OsConstants.POLLIN == 0) {
                    if (tunPoll.revents.toInt() != 0) break // error o cierre del túnel
                    continue
                }
                val length = input.read(buffer)
                if (length > 0) handlePacket(buffer, length)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Fin del lector del túnel: ${e.message}")
        }
    }

    private fun handlePacket(buffer: ByteArray, length: Int) {
        val udp = IpPacket.parseUdp(buffer, length) ?: return
        if (udp.destinationPort != 53) return
        val question = DnsMessage.parseQuery(udp.data, udp.payloadOffset, udp.payloadLength) ?: return

        val rule = app.engine.decide(question.name)
        if (BuildConfig.DEBUG) QueryLog.query(ownerOf(udp), question.name, rule)
        if (rule != null) {
            // Se averigua la app antes de responder: tras la respuesta el socket puede cerrarse.
            record(udp, question.name, rule)
            if (FilterEngine.isThreat(rule)) threatAlerts?.onThreat(question.name, ownerOf(udp))
            val response = DnsMessage.buildBlockedResponse(udp.data, question, udp.payloadOffset)
            write(IpPacket.buildUdpReply(udp, response))
        } else {
            // Copia independiente del búfer de lectura, que se reutiliza en el siguiente read().
            val request = IpPacket.parseUdp(buffer.copyOf(length), length) ?: return
            record(request, question.name, null)
            forwarder?.forward(request, question.name)
        }
    }

    private fun ownerOf(udp: UdpPacket): String? =
        appResolver?.ownerPackage(udp.source, udp.sourcePort, udp.destination, udp.destinationPort)

    /** Anota la consulta en el historial (si está activado), con la app que la hizo. */
    private fun record(udp: UdpPacket, domain: String, rule: String?) {
        val rec = recorder ?: return
        if (!app.settings.historyEnabled) return
        val pkg = appResolver?.ownerPackage(udp.source, udp.sourcePort, udp.destination, udp.destinationPort)
        rec.record(DnsRecord(System.currentTimeMillis(), domain, pkg, rule != null, rule))
    }

    /** Escribe en el túnel vigente (tras una recarga, las respuestas pendientes salen por el nuevo). */
    private fun write(packet: ByteArray) {
        val out = tunnel?.output ?: return
        try {
            synchronized(out) { out.write(packet) }
        } catch (e: IOException) {
            Log.d(TAG, "Escritura en tun fallida: ${e.message}")
        }
    }

    private fun stopVpn(keepState: Boolean = false) {
        closeAll()
        if (!keepState) app.setVpnState(VpnState.Stopped)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeAll() {
        tunnel?.let { closeTunnel(it) }
        tunnel = null
        forwarder?.stop()
        forwarder = null
        upstream?.stop()
        upstream = null
        // Guarda lo pendiente del historial antes de que se cancele el scope del servicio.
        recorder?.let { rec -> runBlocking { withTimeoutOrNull(2000) { rec.flush() } } }
        if (BuildConfig.DEBUG) QueryLog.flushNow()
        recorder = null
        appResolver = null
        threatAlerts = null
    }

    private fun closeTunnel(t: Tunnel) {
        // Cerrar el extremo de escritura despierta el poll() del lector con POLLHUP.
        runCatching { Os.close(t.wakeWrite) }
        t.thread?.join(1000)
        runCatching { Os.close(t.wakeRead) }
        runCatching { t.output.close() }
        runCatching { t.pfd.close() }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_garbillo)
            .setContentTitle(getString(R.string.notif_running))
            .setContentText(getString(R.string.notif_running_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "DnsVpnService"
        private const val ACTION_STOP = "app.bloqueador.STOP"
        private const val ACTION_RELOAD = "app.bloqueador.RELOAD"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1

        private const val TUN_V4 = "10.111.222.1"
        private const val DNS_V4 = "10.111.222.2"
        private const val TUN_V6 = "fd00:6c6f:6361:6c::1"
        private const val DNS_V6 = "fd00:6c6f:6361:6c::2"

        private const val MAX_PACKET = 32767

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DnsVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DnsVpnService::class.java).setAction(ACTION_STOP))
        }

        /** Recrea el túnel con la selección de apps actual. No hace nada si la VPN está apagada. */
        fun reloadApps(context: Context) {
            context.startService(Intent(context, DnsVpnService::class.java).setAction(ACTION_RELOAD))
        }
    }
}
