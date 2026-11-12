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

    lateinit var settings: AppSettings
        private set

    lateinit var repository: BlocklistRepository
        private set

    lateinit var history: HistoryRepository
        private set

    val engine = FilterEngine()

    private val _vpnState = MutableStateFlow<VpnState>(VpnState.Stopped)
    val vpnState: StateFlow<VpnState> = _vpnState

    fun setVpnState(state: VpnState) {
        _vpnState.value = state
    }

    /** Reconstruye el motor desde disco y lo sustituye en caliente (sin tocar el túnel). */
    suspend fun reloadEngine() {
        engine.update(repository.load())
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        repository = BlocklistRepository(this, settings)
        history = HistoryRepository(this, settings)
        removeVisualBlockLeftovers()
        ListUpdateScheduler.schedule(this, settings.updateFrequency, replace = false)
        AppUpdateWorker.schedule(this)
    }

    /** El bloqueo visual (accesibilidad) se quitó en 0.2.0: borra sus reglas y miniaturas. */
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
    data class Error(val message: String) : VpnState
}
