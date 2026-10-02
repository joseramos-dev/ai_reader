package dev.joseramos.aireader.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.joseramos.aireader.ai.models.ModelCatalog
import dev.joseramos.aireader.ai.models.ModelInfo
import dev.joseramos.aireader.ai.models.ModelManager
import dev.joseramos.aireader.text.Language
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Motor de voz. La interfaz existe para poder probar el pipeline con un motor simulado. */
interface TtsEngine {
    val sampleRate: Int

    /** Carga la voz [voiceId] (y libera otra que hubiera cargada); `false` si no está descargada. */
    suspend fun load(voiceId: String): Boolean

    /** PCM float mono a [sampleRate]. [speed] 1.0 es la velocidad normal. */
    fun synthesize(text: String, speed: Float): FloatArray

    fun release()
}

/** Voz de cada idioma que la app sabe leer. */
fun Language.voice(): ModelInfo = when (this) {
    Language.SPANISH -> ModelCatalog.spanishVoice
    Language.ENGLISH -> ModelCatalog.englishVoice
}

/** Piper (VITS) con sherpa-onnx: `es_ES-davefx-medium` o `en_US-lessac-medium`, ambas int8. */
@Singleton
class PiperTtsEngine @Inject constructor(private val models: ModelManager) : TtsEngine {
    private val mutex = Mutex()

    @Volatile private var tts: OfflineTts? = null
    private var loadedId: String? = null

    override val sampleRate: Int get() = tts?.sampleRate() ?: DEFAULT_SAMPLE_RATE

    override suspend fun load(voiceId: String): Boolean = mutex.withLock {
        if (tts != null && loadedId == voiceId) return@withLock true
        val dir = models.installedDir(voiceId) ?: return@withLock false
        release()
        tts = create(dir)
        loadedId = voiceId
        true
    }

    override fun synthesize(text: String, speed: Float): FloatArray =
        checkNotNull(tts) { "La voz no está cargada" }.generate(text, 0, speed).samples

    override fun release() {
        tts?.release()
        tts = null
        loadedId = null
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
