package app.bloqueador

import android.app.Application
import app.bloqueador.filter.FilterEngine
import app.bloqueador.history.HistoryRepository
import app.bloqueador.lists.BlocklistRepository
import app.bloqueador.settings.AppSettings
import app.bloqueador.update.ListUpdateScheduler
import app.bloqueador.appupdate.AppUpdateWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Estado global mínimo compartido entre el servicio VPN y la UI. */
class BloqueadorApp : Application() {

    // Android 8 a 12: el idioma elegido en Ajustes (en 13+ lo aplica el sistema).
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    lateinit var settings: AppSettings
        private set

    lateinit var repository: BlocklistRepository
        private set

    lateinit var history: HistoryRepository
        private set

    val engine = FilterEngine()

    private val _vpnState = MutableStateFlow<VpnState>(VpnState.Stopped)
    val vpnState: StateFlow<VpnState> = _vpnState

    /** Con el filtro activo: ¿lleva el DNS real un rato sin contestar? (para avisar en la pantalla principal). */
    @Volatile
    var dnsUnresponsive: (() -> Boolean)? = null

    /** Con el filtro activo: vuelve a comprobar "Bloquear conexiones sin VPN" (solo lo sabe el servicio). */
    @Volatile
    var checkLockdown: (() -> Unit)? = null

    /** Con el filtro activo: estado interno para la pantalla Diagnóstico. */
    @Volatile
    var diagnostics: (() -> app.bloqueador.vpn.DiagnosticsInfo)? = null

    /** Con el filtro activo: consulta de prueba que recorre el filtro entero (pantalla Diagnóstico). */
    @Volatile
    var selfTest: (suspend (String) -> app.bloqueador.vpn.SelfTestResult)? = null

    fun setVpnState(state: VpnState) {
        _vpnState.value = state
    }

    /** Reconstruye el motor desde disco y lo sustituye en caliente (sin tocar el túnel). */
    /**
     * Construye el motor con las listas del disco y lo sustituye. Si el móvil se queda sin memoria a
     * mitad (muchas capas en un móvil con poca RAM), se sigue con el motor anterior y se avisa como
     * error normal en vez de cerrar la app.
     */
    suspend fun reloadEngine() {
        val filters = try {
            repository.load()
        } catch (e: OutOfMemoryError) {
            app.bloqueador.diag.ErrorReports.record(this, "listas_memoria", e)
            throw java.io.IOException(getString(R.string.lists_out_of_memory), e)
        }
        engine.update(filters)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        repository = BlocklistRepository(this, settings)
        history = HistoryRepository(this, settings)
        removeVisualBlockLeftovers()
        app.bloqueador.diag.ErrorReports.install(this)
        app.bloqueador.update.DownloadPolicy.reschedule(this)
    }

    /** Borra datos que dejaban versiones anteriores con bloqueo visual (reglas y miniaturas). */
    private fun removeVisualBlockLeftovers() {
        java.io.File(filesDir, "visual_rules.json").delete()
        java.io.File(cacheDir, "thumbs").deleteRecursively()
    }

    companion object {
        lateinit var instance: BloqueadorApp
            private set
    }
}

sealed interface VpnState {
    data object Stopped : VpnState
    data object Starting : VpnState
    data class Running(val loadingList: Boolean) : VpnState
    data class Error(val message: UiText) : VpnState
}
