package dev.joseramos.aireader.spikes.tokenizer

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.joseramos.aireader.spikes.common.Catalog
import dev.joseramos.aireader.spikes.common.LogView
import dev.joseramos.aireader.spikes.common.ModelSelector
import dev.joseramos.aireader.spikes.common.ModelSpec
import dev.joseramos.aireader.spikes.common.ModelStore
import dev.joseramos.aireader.spikes.common.SpikeHeader
import dev.joseramos.aireader.spikes.common.SpikeLog
import java.io.File
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val SPIKE = "3-tokenizer"

@Composable
fun TokenizerSpikeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }

    SpikeHeader(
        "3 · Tokenizador en Kotlin (DJL HuggingFace tokenizers)",
        "ids idénticos a la referencia de Python (tools/spikes/gen_token_reference.py)."
    )
    val spec = ModelSelector(listOf(Catalog.tokenizerE5, Catalog.tokenizerGemma), SPIKE)
    Button(enabled = ModelStore.isReady(context, spec) && !running, onClick = {
        running = true
        scope.launch {
            runCatching { compare(context, spec) }.onFailure { SpikeLog.log(SPIKE, "ERROR: $it") }
            running = false
        }
    }) { Text("Comparar con la referencia") }
    LogView(SPIKE)
}

private suspend fun compare(context: Context, spec: ModelSpec) = withContext(Dispatchers.Default) {
    val key = if (spec == Catalog.tokenizerE5) "e5-small" else "embeddinggemma"
    val reference = JSONObject(context.assets.open("token_reference.json").bufferedReader().readText())
    val cases = reference.getJSONObject("models").getJSONArray(key)
    SpikeLog.log(SPIKE, "== $key · referencia tokenizers ${reference.getString("tokenizers_version")}")

    lateinit var tokenizer: HuggingFaceTokenizer
    val loadMs = measureTimeMillis {
        tokenizer = HuggingFaceTokenizer.builder()
            .optTokenizerPath(File(ModelStore.dir(context, spec), "tokenizer.json").toPath())
            .optAddSpecialTokens(true)
            .optTruncation(false)
            .optPadding(false)
            .build()
    }
    SpikeLog.log(SPIKE, "Carga del tokenizador: $loadMs ms")
    tokenizer.use { tok ->
        var ok = 0
        val texts = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val text = case.getString("text")
            texts += text
            val expectedJson = case.getJSONArray("ids")
            val expected = LongArray(expectedJson.length()) { expectedJson.getLong(it) }
            val actual = tok.encode(text).ids
            if (actual.contentEquals(expected)) {
                ok++
            } else {
                val diff =
                    expected.indices.firstOrNull { it >= actual.size || actual[it] != expected[it] } ?: actual.size
                SpikeLog.log(
                    SPIKE,
                    "  DIFERENTE «${text.trim().take(40)}» en posición $diff: " +
                        "esperado ${expected.drop(diff).take(5)} · obtenido ${actual.drop(diff).take(5)}"
                )
            }
        }
        SpikeLog.log(SPIKE, "Coincidencias: $ok/${cases.length()}")
        val repetitions = 200
        val ms = measureTimeMillis { repeat(repetitions) { texts.forEach { tok.encode(it) } } }
        SpikeLog.log(SPIKE, "Velocidad: %.3f ms por frase".format(ms.toDouble() / (repetitions * texts.size)))
    }
}
