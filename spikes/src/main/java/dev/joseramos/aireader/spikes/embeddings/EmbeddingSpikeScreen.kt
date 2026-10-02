package dev.joseramos.aireader.spikes.embeddings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import dev.joseramos.aireader.spikes.common.Catalog
import dev.joseramos.aireader.spikes.common.LogView
import dev.joseramos.aireader.spikes.common.Memory
import dev.joseramos.aireader.spikes.common.ModelSelector
import dev.joseramos.aireader.spikes.common.ModelSpec
import dev.joseramos.aireader.spikes.common.ModelStore
import dev.joseramos.aireader.spikes.common.SampleTexts
import dev.joseramos.aireader.spikes.common.SpikeHeader
import dev.joseramos.aireader.spikes.common.SpikeLog
import dev.joseramos.aireader.spikes.common.withPeakPss
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SPIKE = "2-embeddings"
private const val BOOK_CHUNKS = 1500
private const val BATCH = 8

@Composable
fun EmbeddingSpikeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var threads by remember { mutableIntStateOf(4) }
    var chunks by remember { mutableIntStateOf(64) }

    SpikeHeader(
        "2 · Embeddings ONNX",
        "indexar un libro de 300 páginas (~$BOOK_CHUNKS fragmentos de ~350 tokens) en < 5 min con RAM < 400 MB."
    )
    val spec = ModelSelector(Catalog.embeddings, SPIKE)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(2, 4, 6).forEach { n -> FilterChip(threads == n, { threads = n }, { Text("$n hilos") }) }
    }
    Text("Fragmentos a procesar (se extrapola a $BOOK_CHUNKS):")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(64, 256, BOOK_CHUNKS).forEach { n -> FilterChip(chunks == n, { chunks = n }, { Text("$n") }) }
    }
    Button(enabled = ModelStore.isReady(context, spec) && !running, onClick = {
        running = true
        scope.launch {
            runCatching { benchmark(context, spec, threads, chunks) }.onFailure { SpikeLog.log(SPIKE, "ERROR: $it") }
            running = false
        }
    }) { Text("Medir") }
    if (running) Text("Ejecutando… (puede tardar varios minutos)")
    LogView(SPIKE)
}

private suspend fun benchmark(context: Context, spec: ModelSpec, threads: Int, chunks: Int) =
    withContext(Dispatchers.Default) {
        val pssBefore = Memory.pssMb()
        SpikeLog.log(SPIKE, "== ${spec.label} · $threads hilos · $chunks fragmentos · PSS inicial $pssBefore MB")
        val (_, peak) = withPeakPss(this) {
            lateinit var embedder: OnnxEmbedder
            val loadMs = measureTimeMillis { embedder = OnnxEmbedder(ModelStore.dir(context, spec), threads) }
            SpikeLog.log(
                SPIKE,
                "Carga: $loadMs ms · PSS ${Memory.pssMb()} MB · entradas ${embedder.inputNames} · salidas ${embedder.outputNames}"
            )
            embedder.use { e ->
                // Calidad: cada pregunta debería recuperar su párrafo.
                val docs = e.embed(SampleTexts.paragraphs.map(e::document)).first
                var hits = 0
                SampleTexts.queries.forEach { (q, expected) ->
                    val qv = e.embed(listOf(e.query(q))).first.single()
                    val scores = docs.map { dot(qv, it) }
                    val best = scores.indices.maxBy { scores[it] }
                    if (best == expected) hits++
                    SpikeLog.log(
                        SPIKE,
                        "  «${q.take(45)}…» → párrafo $best (esperado $expected) · score %.3f".format(scores[best])
                    )
                }
                SpikeLog.log(SPIKE, "Recuperación: $hits/${SampleTexts.queries.size} aciertos en top-1")

                // Rendimiento: lotes de fragmentos de ~350 tokens.
                var tokens = 0
                val totalMs = measureTimeMillis {
                    (0 until chunks).chunked(BATCH).forEachIndexed { i, idx ->
                        tokens += e.embed(idx.map { e.document(SampleTexts.chunk(it)) }).second
                        if (i % 8 == 0) SpikeLog.log(SPIKE, "  ${(i + 1) * BATCH}/$chunks · PSS ${Memory.pssMb()} MB")
                    }
                }
                val perChunk = totalMs.toDouble() / chunks
                SpikeLog.log(
                    SPIKE,
                    "Total $totalMs ms · %.0f ms/fragmento · %.0f tokens/fragmento".format(
                        perChunk,
                        tokens.toDouble() / chunks
                    )
                )
                SpikeLog.log(
                    SPIKE,
                    "Extrapolado a $BOOK_CHUNKS fragmentos: %.1f min".format(
                        perChunk * BOOK_CHUNKS / 60_000
                    )
                )
            }
        }
        SpikeLog.log(SPIKE, "PSS pico $peak MB (+${peak - pssBefore} MB sobre el inicial)")
    }
