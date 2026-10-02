package dev.joseramos.aireader.indexing

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evita bucles infinitos cuando una etapa de la indexación tumba la app (un fallo nativo o falta de
 * memoria no se puede capturar): se apunta el intento antes de empezar y se borra al terminar. Si
 * al volver a empezar ya había [MAX_CRASHES] intentos sin terminar, la etapa se salta para ese libro.
 *
 * Una etapa también puede quedar marcada como imposible en este móvil ([markUnsupported]), por
 * ejemplo si falta una biblioteca nativa: entonces se salta en todos los libros sin intentarlo.
 *
 * Todo se olvida al instalar una versión nueva de la app, para que tenga ocasión de probar sus
 * arreglos (sin bucles: si sigue fallando, se vuelve a apuntar).
 */
@Singleton
class StageCrashGuard @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("indexing_crash_guard", Context.MODE_PRIVATE)
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

    /** La etapa no puede funcionar en este móvil con esta versión de la app (no merece la pena reintentarla). */
    fun markUnsupported(stage: String) {
        prefs.edit { putBoolean(unsupportedKey(stage), true) }
    }

    /** Lo pide el usuario («Reintentar»): se olvidan los fallos de este libro y las etapas imposibles. */
    fun reset(bookId: String) {
        updateCheck
        prefs.edit {
            prefs.all.keys.filter { it.endsWith(":$bookId") || it.startsWith(UNSUPPORTED_PREFIX) }.forEach(::remove)
        }
    }

    private fun forgetIfAppUpdated() {
        val install = runCatching { installStamp() }.getOrNull() ?: return
        if (prefs.getString(INSTALL_KEY, null) == install) return
        prefs.edit(commit = true) {
            clear()
            putString(INSTALL_KEY, install)
        }
    }

    /** Cambia con cada instalación o actualización, aunque no cambie el número de versión (depuración). */
    private fun installStamp(): String {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        return "${info.longVersionCode}:${info.lastUpdateTime}"
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
