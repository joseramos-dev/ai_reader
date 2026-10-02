package dev.joseramos.aireader.indexing

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evita bucles infinitos cuando una etapa de la indexación tumba la app (un fallo nativo o falta de
 * memoria no se puede capturar): se apunta el intento antes de empezar y se borra al terminar. Si
 * al volver a empezar ya había [MAX_CRASHES] intentos sin terminar, la etapa se salta para ese libro.
 */
@Singleton
class StageCrashGuard @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("indexing_crash_guard", Context.MODE_PRIVATE)

    fun shouldSkip(stage: String, bookId: String): Boolean = prefs.getInt(key(stage, bookId), 0) >= MAX_CRASHES

    /** Se llama justo antes de empezar la etapa (se guarda en disco al momento). */
    fun begin(stage: String, bookId: String) {
        val key = key(stage, bookId)
        prefs.edit(commit = true) { putInt(key, prefs.getInt(key, 0) + 1) }
    }

    /** La etapa ha terminado (bien, con un error controlado o cancelada): no fue un cierre. */
    fun end(stage: String, bookId: String) {
        prefs.edit { remove(key(stage, bookId)) }
    }

    private fun key(stage: String, bookId: String) = "$stage:$bookId"

    private companion object {
        const val MAX_CRASHES = 2
    }
}
