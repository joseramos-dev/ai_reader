package dev.joseramos.aireader.spikes.common

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Registro de resultados de los spikes: se muestra en pantalla y se añade a
 * `Android/data/<paquete>/files/spikes/resultados.md` para copiarlo después.
 */
object SpikeLog {
    private val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
    private val _lines = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val lines: StateFlow<Map<String, List<String>>> = _lines
    lateinit var file: File
        private set
    lateinit var deviceInfo: String
        private set

    fun init(context: Context) {
        val dir = context.getExternalFilesDir("spikes") ?: File(context.filesDir, "spikes")
        dir.mkdirs()
        file = File(dir, "resultados.md")
        deviceInfo = buildDeviceInfo(context)
        file.appendText("\n## Sesión ${date.format(Date())}\n\n$deviceInfo\n\n")
    }

    @Synchronized
    fun log(spike: String, message: String) {
        Log.i("Spike-$spike", message)
        val line = "[${time.format(Date())}] $message"
        _lines.update { it + (spike to (it[spike].orEmpty() + line).takeLast(300)) }
        file.appendText("- [$spike] $line\n")
    }

    fun clear(spike: String) {
        _lines.update { it - spike }
    }

    private fun buildDeviceInfo(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "?"
        return "Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}" +
            " · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            " · SoC: $soc · Núcleos: ${Runtime.getRuntime().availableProcessors()}" +
            " · RAM total: ${mem.totalMem / MB} MB · Clase de memoria: ${am.memoryClass} MB"
    }

    private const val MB = 1024 * 1024
}
