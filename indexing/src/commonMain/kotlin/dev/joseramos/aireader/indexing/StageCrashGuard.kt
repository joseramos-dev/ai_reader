package dev.joseramos.aireader.indexing

import dev.joseramos.aireader.core.common.InstallStamp
import dev.joseramos.aireader.core.common.KeyValueStoreFactory

/**
 * Evita bucles infinitos cuando una etapa de la indexación tumba la app (un fallo nativo o falta de
 * memoria no se puede capturar): se apunta el intento antes de empezar y se borra al terminar. Si
 * al volver a empezar ya había [MAX_CRASHES] intentos sin terminar, la etapa se salta para ese libro.
 *
 * Una etapa también puede quedar marcada como imposible en este equipo ([markUnsupported]), por
 * ejemplo si falta una biblioteca nativa: entonces se salta en todos los libros sin intentarlo.
 *
 * Todo se olvida al instalar una versión nueva de la app, para que tenga ocasión de probar sus
 * arreglos (sin bucles: si sigue fallando, se vuelve a apuntar).
 */
class StageCrashGuard(stores: KeyValueStoreFactory, private val installStamp: InstallStamp) {
    private val prefs = stores.open("indexing_crash_guard")
    private val updateCheck by lazy { forgetIfAppUpdated() }

    fun shouldSkip(stage: String, bookId: String): Boolean {
        updateCheck
        return prefs.getBoolean(unsupportedKey(stage), false) || prefs.getInt(key(stage, bookId), 0) >= MAX_CRASHES
    }

    /** Se llama justo antes de empezar la etapa (se guarda en disco al momento). */
    fun begin(stage: String, bookId: String) {
        updateCheck
        val key = key(stage, bookId)
        prefs.edit(commit = true) { putInt(key, prefs.getInt(key, 0) + 1) }
    }

    /** La etapa ha terminado (bien, con un error controlado o cancelada): no fue un cierre. */
    fun end(stage: String, bookId: String) {
        prefs.edit { remove(key(stage, bookId)) }
    }

    /** La etapa no puede funcionar en este equipo con esta versión de la app (no merece la pena reintentarla). */
    fun markUnsupported(stage: String) {
        prefs.edit { putBoolean(unsupportedKey(stage), true) }
    }

    /** Lo pide el usuario («Reintentar»): se olvidan los fallos de este libro y las etapas imposibles. */
    fun reset(bookId: String) {
        updateCheck
        val keys = prefs.keys().filter { it.endsWith(":$bookId") || it.startsWith(UNSUPPORTED_PREFIX) }
        prefs.edit { keys.forEach(::remove) }
    }

    private fun forgetIfAppUpdated() {
        val install = runCatching { installStamp.get() }.getOrNull() ?: return
        if (prefs.getString(INSTALL_KEY) == install) return
        prefs.edit(commit = true) {
            clear()
            putString(INSTALL_KEY, install)
        }
    }

    private fun key(stage: String, bookId: String) = "$stage:$bookId"

    private fun unsupportedKey(stage: String) = "$UNSUPPORTED_PREFIX$stage"

    companion object {
        /** Fragmentos del libro para la búsqueda por palabras del chat. */
        const val CHUNKS = "chunks"

        /** Vectores (modelo ONNX) para la búsqueda por significado del chat. */
        const val EMBEDDINGS = "embeddings"

        private const val MAX_CRASHES = 2
        private const val INSTALL_KEY = "install"
        private const val UNSUPPORTED_PREFIX = "unsupported:"
    }
}
