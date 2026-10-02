package dev.joseramos.aireader.spikes.gemini

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
import dev.joseramos.aireader.spikes.common.LogView
import dev.joseramos.aireader.spikes.common.SpikeHeader
import dev.joseramos.aireader.spikes.common.SpikeLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val SPIKE = "6-gemini"

private val models = listOf("gemini-3.8-flash", "gemini-3.1-flash-lite")

private val http = OkHttpClient.Builder().readTimeout(180, TimeUnit.SECONDS).build()

@Composable
fun GeminiSpikeScreen() {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(models[0]) }
    var thinking by remember { mutableStateOf("low") }
    var prompt by remember { mutableStateOf("Resume en cinco frases cómo funciona la fotosíntesis.") }
    var output by remember { mutableStateOf("") }
    var call by remember { mutableStateOf<Call?>(null) }
    val cancelAt = remember { longArrayOf(0) }

    SpikeHeader("6 · Streaming de la API de Gemini (REST + SSE)", "primer texto en < 2 s y cancelación limpia.")
    Text(
        "La clave solo se guarda en memoria mientras la pantalla está abierta.",
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedTextField(
        apiKey,
        { apiKey = it.trim() },
        label = { Text("Clave de API de Gemini") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        models.forEach { m -> FilterChip(model == m, { model = m }, { Text(m.removePrefix("gemini-")) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("minimal", "low", "high").forEach { t -> FilterChip(thinking == t, { thinking = t }, { Text(t) }) }
    }
    OutlinedTextField(prompt, { prompt = it }, label = { Text("Pregunta") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = apiKey.isNotBlank() && call == null, onClick = {
            output = ""
            cancelAt[0] = 0
            val newCall = http.newCall(request(apiKey, model, thinking, prompt))
            call = newCall
            scope.launch {
                runStreaming(newCall, model, thinking, cancelAt) { output += it }
                call = null
            }
        }) { Text("Enviar") }
        OutlinedButton(enabled = call != null, onClick = {
            cancelAt[0] = SystemClock.elapsedRealtime()
            call?.cancel()
        }) { Text("Cancelar") }
    }
    if (output.isNotEmpty()) Text(output, style = MaterialTheme.typography.bodyMedium)
    LogView(SPIKE)
}

private fun request(apiKey: String, model: String, thinking: String, prompt: String): Request {
    val message = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))
    val config = JSONObject()
        .put("maxOutputTokens", 8_000)
        .put("thinkingConfig", JSONObject().put("thinkingLevel", thinking))
    val body = JSONObject().put("contents", JSONArray().put(message)).put("generationConfig", config)
    return Request.Builder()
        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
        .header("x-goog-api-key", apiKey)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
}

private suspend fun runStreaming(
    call: Call,
    model: String,
    thinking: String,
    cancelAt: LongArray,
    onText: (String) -> Unit
) = withContext(Dispatchers.IO) {
    val start = SystemClock.elapsedRealtime()
    var firstText = 0L
    var output = 0L
    var thoughts = 0L
    var finish = "?"
    SpikeLog.log(SPIKE, "== $model · thinking $thinking")
    try {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                SpikeLog.log(SPIKE, "ERROR de la API ${response.code}: ${response.body.string().take(300)}")
                return@withContext
            }
            val source = response.body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val chunk = JSONObject(line.removePrefix("data:").trim())
                val candidate = chunk.optJSONArray("candidates")?.optJSONObject(0)
                val parts = candidate?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.optBoolean("thought") || !part.has("text")) continue
                    if (firstText == 0L) firstText = SystemClock.elapsedRealtime() - start
                    // El estado de Compose admite escrituras desde otros hilos.
                    onText(part.getString("text"))
                }
                candidate?.optString("finishReason")?.takeIf { it.isNotEmpty() }?.let { finish = it }
                chunk.optJSONObject("usageMetadata")?.let {
                    output = it.optLong("candidatesTokenCount")
                    thoughts = it.optLong("thoughtsTokenCount")
                }
            }
        }
        val total = SystemClock.elapsedRealtime() - start
        SpikeLog.log(SPIKE, "Primer texto $firstText ms · total $total ms · finishReason $finish")
        SpikeLog.log(
            SPIKE,
            "Tokens de salida $output (+$thoughts de razonamiento) · %.0f tokens/s tras el primer texto"
                .format(output * 1000.0 / maxOf(1, total - firstText))
        )
    } catch (e: IOException) {
        if (cancelAt[0] > 0) {
            val elapsed = SystemClock.elapsedRealtime() - cancelAt[0]
            SpikeLog.log(SPIKE, "Cancelado: la conexión se cerró $elapsed ms después de pulsar Cancelar")
        } else {
            SpikeLog.log(SPIKE, "ERROR: $e")
        }
    }
}
