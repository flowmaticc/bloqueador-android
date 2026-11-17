package app.bloqueador.apps

import app.bloqueador.R
import app.bloqueador.UiText
/** Qué apps pasan por el filtro. */
enum class AppStrategy(val label: UiText, val description: UiText) {
    ALL(UiText.res(R.string.strategy_all), UiText.res(R.string.strategy_all_desc)),
    ALL_EXCEPT(UiText.res(R.string.strategy_except), UiText.res(R.string.strategy_except_desc)),
    ONLY(UiText.res(R.string.strategy_only), UiText.res(R.string.strategy_only_desc)),
}

/**
 * Configuración que se aplica al crear el túnel. Android no permite mezclar listas de
 * permitidas y excluidas en el mismo túnel, por eso es una cosa o la otra.
 */
sealed interface TunnelApps {
    /** Todo pasa por el túnel salvo estos paquetes. */
    data class Disallowed(val packages: List<String>) : TunnelApps

    /** Solo estos paquetes pasan por el túnel. */
    data class Allowed(val packages: List<String>) : TunnelApps
}

/**
 * Calcula la configuración del túnel.
 *
 * - La propia app ([self]) nunca pasa por el filtro: sus descargas de listas no deben depender de él.
 * - Los paquetes que ya no están instalados se ignoran.
 * - Si "solo las que elija" se queda sin ninguna app instalada, se filtra todo: es preferible
 *   proteger de más que dejar el dispositivo sin protección sin avisar.
 */
fun computeTunnelApps(
    strategy: AppStrategy,
    excluded: Set<String>,
    included: Set<String>,
    self: String,
    isInstalled: (String) -> Boolean,
): TunnelApps = when (strategy) {
    AppStrategy.ALL -> TunnelApps.Disallowed(listOf(self))
    AppStrategy.ALL_EXCEPT ->
        TunnelApps.Disallowed(listOf(self) + excluded.filter { it != self && isInstalled(it) }.sorted())
    AppStrategy.ONLY -> {
        val apps = included.filter { it != self && isInstalled(it) }.sorted()
        if (apps.isEmpty()) TunnelApps.Disallowed(listOf(self)) else TunnelApps.Allowed(apps)
    }
}
