package app.bloqueador

import kotlinx.coroutines.CancellationException

/**
 * Como `runCatching`, pero sin tragarse la cancelación de la corrutina: si el trabajo se cancela
 * (el usuario sale de la pantalla, WorkManager para la tarea, el servicio se detiene), la cancelación
 * sigue su camino en vez de convertirse en un "fallo" que se registra o se reintenta.
 */
inline fun <T> intentar(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
