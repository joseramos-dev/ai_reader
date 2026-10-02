package dev.joseramos.aireader.spikes.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/** Crea el motor Piper (sherpa-onnx) a partir del directorio de la voz ya descargada. */
object SherpaTts {
    fun create(dir: File, numThreads: Int): OfflineTts {
        val onnx = dir.listFiles { f -> f.name.endsWith(".onnx") }!!.first()
        val model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(
                model = onnx.absolutePath,
                tokens = File(dir, "tokens.txt").absolutePath,
                dataDir = File(dir, "espeak-ng-data").absolutePath
            ),
            numThreads = numThreads
        )
        return OfflineTts(config = OfflineTtsConfig(model = model))
    }
}
