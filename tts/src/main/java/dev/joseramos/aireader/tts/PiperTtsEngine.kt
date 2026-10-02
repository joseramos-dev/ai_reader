package dev.joseramos.aireader.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.joseramos.aireader.ai.models.ModelCatalog
import dev.joseramos.aireader.ai.models.ModelManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Motor de voz. La interfaz existe para poder probar el pipeline con un motor simulado. */
interface TtsEngine {
    val sampleRate: Int

    /** Carga el modelo; `false` si la voz no está descargada. */
    suspend fun load(): Boolean

    /** PCM float mono a [sampleRate]. [speed] 1.0 es la velocidad normal. */
    fun synthesize(text: String, speed: Float): FloatArray

    fun release()
}

/** Piper (VITS) con la voz `es_ES-davefx-medium` int8, ejecutado con sherpa-onnx. */
@Singleton
class PiperTtsEngine @Inject constructor(private val models: ModelManager) : TtsEngine {
    private val mutex = Mutex()

    @Volatile private var tts: OfflineTts? = null

    override val sampleRate: Int get() = tts?.sampleRate() ?: DEFAULT_SAMPLE_RATE

    override suspend fun load(): Boolean = mutex.withLock {
        if (tts != null) return@withLock true
        val dir = models.installedDir(ModelCatalog.piperVoice.id) ?: return@withLock false
        tts = create(dir)
        true
    }

    override fun synthesize(text: String, speed: Float): FloatArray =
        checkNotNull(tts) { "La voz no está cargada" }.generate(text, 0, speed).samples

    override fun release() {
        tts?.release()
        tts = null
    }

    private fun create(dir: File): OfflineTts {
        val onnx = dir.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull() ?: error("Falta el modelo de la voz")
        val model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(
                model = onnx.absolutePath,
                tokens = File(dir, "tokens.txt").absolutePath,
                dataDir = File(dir, "espeak-ng-data").absolutePath
            ),
            numThreads = THREADS
        )
        return OfflineTts(config = OfflineTtsConfig(model = model))
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 22_050
        const val THREADS = 2
    }
}
