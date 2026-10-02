package dev.joseramos.aireader.spikes.tts

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import dev.joseramos.aireader.spikes.common.AudioOut
import dev.joseramos.aireader.spikes.common.Catalog
import dev.joseramos.aireader.spikes.common.LogView
import dev.joseramos.aireader.spikes.common.Memory
import dev.joseramos.aireader.spikes.common.ModelSelector
import dev.joseramos.aireader.spikes.common.ModelStore
import dev.joseramos.aireader.spikes.common.SampleTexts
import dev.joseramos.aireader.spikes.common.SpikeHeader
import dev.joseramos.aireader.spikes.common.SpikeLog
import dev.joseramos.aireader.spikes.common.withPeakPss
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SPIKE = "1-tts"

@Composable
fun TtsSpikeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var threads by remember { mutableIntStateOf(2) }

    SpikeHeader(
        "1 · TTS en el dispositivo (Piper)",
        "primera frase en < 1,5 s y síntesis más rápida que el tiempo real (RTF < 1)."
    )
    val spec = ModelSelector(listOf(Catalog.piperDavefxInt8), SPIKE)

    Text("Hilos de inferencia:")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 2, 4).forEach { n -> FilterChip(threads == n, { threads = n }, { Text("$n hilos") }) }
    }

    val ready = ModelStore.isReady(context, spec)
    fun run(listen: Boolean) {
        running = true
        scope.launch {
            runCatching { benchmark(context, threads, listen) }.onFailure { SpikeLog.log(SPIKE, "ERROR: $it") }
            running = false
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = ready && !running, onClick = { run(listen = false) }) { Text("Medir") }
        OutlinedButton(enabled = ready && !running, onClick = { run(listen = true) }) { Text("Medir y escuchar") }
    }
    if (running) Text("Ejecutando…")
    LogView(SPIKE)
}

private suspend fun benchmark(context: Context, threads: Int, listen: Boolean) = withContext(Dispatchers.Default) {
    val spec = Catalog.piperDavefxInt8
    val pssBefore = Memory.pssMb()
    SpikeLog.log(SPIKE, "== ${spec.label} · $threads hilos · PSS inicial $pssBefore MB")
    val (_, peak) = withPeakPss(this) {
        lateinit var tts: OfflineTts
        val loadMs = measureTimeMillis { tts = SherpaTts.create(ModelStore.dir(context, spec), threads) }
        SpikeLog.log(SPIKE, "Carga del modelo: $loadMs ms · PSS ${Memory.pssMb()} MB · ${tts.sampleRate()} Hz")
        try {
            val rtfs = mutableListOf<Double>()
            SampleTexts.ttsPhrases.forEachIndexed { i, phrase ->
                lateinit var audio: GeneratedAudio
                val ms = measureTimeMillis { audio = tts.generate(phrase, 0, 1.0f) }
                val seconds = audio.samples.size.toDouble() / audio.sampleRate
                val rtf = ms / 1000.0 / seconds
                rtfs += rtf
                val tag = if (i == 0) " ← PRIMERA FRASE" else ""
                val line = "Frase ${i + 1}: síntesis $ms ms · audio %.2f s · RTF %.2f".format(seconds, rtf)
                SpikeLog.log(SPIKE, line + tag)
                if (listen) AudioOut.playBlocking(audio.samples, audio.sampleRate)
            }
            SpikeLog.log(SPIKE, "RTF medio %.2f (máx %.2f)".format(rtfs.average(), rtfs.max()))
        } finally {
            tts.release()
        }
    }
    SpikeLog.log(SPIKE, "PSS pico $peak MB (+${peak - pssBefore} MB sobre el inicial)")
}
