package dev.joseramos.aireader.spikes.claude

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.http.StreamResponse
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.anthropic.models.beta.messages.MessageCreateParams
import dev.joseramos.aireader.spikes.common.LogView
import dev.joseramos.aireader.spikes.common.SpikeHeader
import dev.joseramos.aireader.spikes.common.SpikeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SPIKE = "6-claude"

private val models = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-4-5")

/** Modelos que admiten `fallbacks: "default"` (reintento en otro modelo si se rechaza la petición). */
private val withFallbacks = setOf("claude-opus-5-5", "claude-sonnet-5-5")

@Composable
fun ClaudeSpikeScreen() {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(models[0]) }
    var effort by remember { mutableStateOf("low") }
    var prompt by remember { mutableStateOf("Resume en cinco frases cómo funciona la fotosíntesis.") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val stream = remember { arrayOfNulls<StreamResponse<BetaRawMessageStreamEvent>>(1) }
    val cancelAt = remember { longArrayOf(0) }

    SpikeHeader(
        "6 · Streaming de la API de Claude (SDK oficial de Java)",
        "primer token en < 2 s y cancelación limpia."
    )
    Text(
        "La clave solo se guarda en memoria mientras la pantalla está abierta.",
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedTextField(
        apiKey,
        { apiKey = it.trim() },
        label = { Text("Clave de API de Anthropic") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        models.forEach { m -> FilterChip(model == m, { model = m }, { Text(m.removePrefix("claude-")) }) }
    }
    if (model != "claude-haiku-4-5") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("low", "medium", "high").forEach { e ->
                FilterChip(effort == e, { effort = e }, { Text("effort $e") })
            }
        }
    }
    OutlinedTextField(prompt, { prompt = it }, label = { Text("Pregunta") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = apiKey.isNotBlank() && !running, onClick = {
            running = true
            output = ""
            scope.launch {
                runStreaming(apiKey, model, effort, prompt, stream, cancelAt) { output += it }
                running = false
            }
        }) { Text("Enviar") }
        OutlinedButton(enabled = running, onClick = {
            cancelAt[0] = SystemClock.elapsedRealtime()
            scope.launch(Dispatchers.IO) { stream[0]?.close() }
        }) { Text("Cancelar") }
    }
    if (output.isNotEmpty()) Text(output, style = MaterialTheme.typography.bodyMedium)
    LogView(SPIKE)
}

private suspend fun runStreaming(
    apiKey: String,
    model: String,
    effort: String,
    prompt: String,
    holder: Array<StreamResponse<BetaRawMessageStreamEvent>?>,
    cancelAt: LongArray,
    onText: (String) -> Unit
) = withContext(Dispatchers.IO) {
    cancelAt[0] = 0
    val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
    val params = MessageCreateParams.builder()
        .model(model)
        .maxTokens(16_000L)
        .addUserMessage(prompt)
        .apply {
            if (model != "claude-haiku-4-5") {
                outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(effort)).build())
            }
            if (model in withFallbacks) {
                addBeta("server-side-fallback-2026-07-01")
                putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
        }
        .build()

    val start = SystemClock.elapsedRealtime()
    var firstEvent = 0L
    var firstText = 0L
    var outputTokens = 0L
    var stopReason = "?"
    SpikeLog.log(SPIKE, "== $model · effort ${if (model == "claude-haiku-4-5") "-" else effort}")
    try {
        client.beta().messages().createStreaming(params).use { response ->
            holder[0] = response
            response.stream().forEach { event ->
                val now = SystemClock.elapsedRealtime() - start
                if (firstEvent == 0L) firstEvent = now
                event.contentBlockDelta().ifPresent { delta ->
                    delta.delta().text().ifPresent { text ->
                        if (firstText == 0L) firstText = now
                        // El estado de Compose admite escrituras desde otros hilos.
                        onText(text.text())
                    }
                }
                event.messageDelta().ifPresent { md ->
                    outputTokens = md.usage().outputTokens()
                    md.delta().stopReason().ifPresent { stopReason = it.toString() }
                }
            }
        }
        val total = SystemClock.elapsedRealtime() - start
        if (cancelAt[0] > 0) {
            SpikeLog.log(
                SPIKE,
                "Cancelado: el stream terminó ${SystemClock.elapsedRealtime() - cancelAt[0]} ms después de pulsar Cancelar"
            )
        }
        SpikeLog.log(SPIKE, "Primer evento $firstEvent ms · primer texto $firstText ms · total $total ms")
        SpikeLog.log(
            SPIKE,
            "Tokens de salida $outputTokens · %.0f tokens/s tras el primer texto · stop_reason $stopReason"
                .format(outputTokens * 1000.0 / maxOf(1, total - firstText))
        )
    } catch (e: AnthropicServiceException) {
        SpikeLog.log(SPIKE, "ERROR de la API ${e.statusCode()}: ${e.message}")
    } catch (e: Exception) {
        if (cancelAt[0] > 0) {
            SpikeLog.log(
                SPIKE,
                "Cancelado: el stream se cerró ${SystemClock.elapsedRealtime() - cancelAt[0]} ms después de pulsar Cancelar"
            )
        } else {
            SpikeLog.log(SPIKE, "ERROR: $e")
        }
    } finally {
        holder[0] = null
        client.close()
    }
}
