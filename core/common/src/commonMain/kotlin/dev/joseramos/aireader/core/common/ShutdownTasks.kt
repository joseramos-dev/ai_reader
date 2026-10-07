package dev.joseramos.aireader.core.common

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException

/**
 * Lo que hay que guardar antes de que la app se cierre. En Windows, cerrar la ventana termina el proceso sin dar tiempo
 * a los guardados con retraso (la página que se está leyendo, la posición de la voz), así que el escritorio ejecuta
 * estas tareas antes de salir. En Android no hace falta: el proceso sigue vivo al cerrar la pantalla.
 */
class ShutdownTasks {
    private val tasks = CopyOnWriteArrayList<suspend () -> Unit>()

    /** Apunta [task] y devuelve cómo quitarla (por ejemplo, al cerrar la pantalla que la apuntó). */
    fun register(task: suspend () -> Unit): () -> Unit {
        tasks += task
        return { tasks -= task }
    }

    /** Ejecuta todas: si una falla, las demás se ejecutan igual. */
    suspend fun runAll() {
        for (task in tasks) {
            try {
                task()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "Falló una tarea de cierre", e)
            }
        }
    }

    private companion object {
        const val TAG = "ShutdownTasks"
    }
}
