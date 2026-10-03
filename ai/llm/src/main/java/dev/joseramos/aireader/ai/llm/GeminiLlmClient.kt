package dev.joseramos.aireader.ai.llm

import android.util.Log
import dev.joseramos.aireader.core.common.IoDispatcher
import dev.joseramos.aireader.core.data.settings.SecretStore
import dev.joseramos.aireader.core.data.settings.UsageRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * [LlmClient] sobre la API REST de Gemini (Google AI Studio) con OkHttp: `generateContent` y
 * `streamGenerateContent` con eventos SSE. La clave va en la cabecera `x-goog-api-key`, nunca en
 * la URL, para que no acabe en registros. La caché de prompts de Gemini es implícita: basta con que
 * las peticiones repitan el mismo principio (instrucciones y contexto del libro). Registra los tokens.
 */
@Singleton
class GeminiLlmClient @Inject constructor(
    private val secrets: SecretStore,
    private val usage: UsageRepository,
    @IoDispatcher private val io: CoroutineDispatcher
) : LlmClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        // El modelo puede pensar un rato antes de enviar el primer fragmento.
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    override suspend fun hasApiKey(): Boolean = secrets.apiKey() != null

    override fun streamChat(request: LlmRequest): Flow<LlmEvent> = callbackFlow {
        var activeCall: Call? = null
        val job = launch(io) {
            try {
                withOverloadRetry {
                    val call = http.newCall(httpRequest(request, apiKey(), stream = true))
                    activeCall = call
                    call.execute().use { response ->
                        ensureSuccess(response)
                        val (totals, blocked, blockReason) = readEvents(response) { send(LlmEvent.Text(it)) }
                        val tokens = record(totals)
                        if (blocked) {
                            Log.w(TAG, "Gemini bloqueó la respuesta del chat: $blockReason")
                            throw LlmException.Refused()
                        }
                        send(LlmEvent.Done(tokens))
                    }
                }
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                close(e)
            } catch (e: IOException) {
                val cancelled = activeCall?.isCanceled() == true
                close(if (cancelled) CancellationException("Cancelado") else translateGeminiError(e))
            } catch (e: SerializationException) {
                close(LlmException.Failed("Respuesta inesperada de Gemini.", e))
            }
        }
        // Cancelar la llamada corta la conexión si quien escucha se va (por ejemplo, al salir del chat).
        awaitClose {
            activeCall?.cancel()
            job.cancel()
        }
    }

    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(io) {
        withOverloadRetry {
            val call = http.newCall(httpRequest(request, apiKey(), stream = false))
            val body = try {
                call.execute().use { response ->
                    ensureSuccess(response)
                    response.body.string()
                }
            } catch (e: IOException) {
                throw translateGeminiError(e)
            }
            val parsed = try {
                parseGeminiResponse(body)
            } catch (e: SerializationException) {
                throw LlmException.Failed("Respuesta inesperada de Gemini.", e)
            }
            val tokens = record(parsed.usageMetadata ?: GeminiUsage())
            if (parsed.blocked && parsed.text.isBlank()) {
                Log.w(TAG, "Gemini bloqueó la respuesta: ${parsed.blockReason}")
                throw LlmException.Refused()
            }
            LlmResponse(parsed.text, tokens)
        }
    }

    /**
     * Reintenta [block] con una pequeña espera si Gemini responde que está saturado (503): es
     * frecuente e intermitente en el nivel gratuito. Solo se reintenta antes de que [block] haya
     * emitido nada (aquí, antes del primer fragmento de texto), para no duplicar una respuesta a medias.
     */
    private suspend fun <T> withOverloadRetry(block: suspend () -> T): T {
        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return block()
            } catch (e: LlmException.Overloaded) {
                Log.w(TAG, "Gemini saturado, reintentando (${attempt + 1}/${MAX_ATTEMPTS - 1})", e)
                delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return block()
    }

    /**
     * Lee los eventos SSE (`data: {...}`, uno por fragmento) y pasa el texto a [onText]. Devuelve el
     * uso de tokens del último evento, si la respuesta se ha bloqueado y, si es así, el motivo.
     */
    private suspend fun readEvents(
        response: Response,
        onText: suspend (String) -> Unit
    ): Triple<GeminiUsage, Boolean, String?> {
        var totals = GeminiUsage()
        var blocked = false
        var blockReason: String? = null
        val source = response.body.source()
        var line = source.readUtf8Line()
        while (line != null) {
            if (line.startsWith(SSE_DATA)) {
                val chunk = parseGeminiResponse(line.removePrefix(SSE_DATA).trim())
                if (chunk.blocked && !blocked) blockReason = chunk.blockReason
                blocked = blocked || chunk.blocked
                if (chunk.text.isNotEmpty()) onText(chunk.text)
                chunk.usageMetadata?.let { totals = it }
            }
            line = source.readUtf8Line()
        }
        return Triple(totals, blocked, blockReason)
    }

    private suspend fun apiKey(): String = secrets.apiKey() ?: throw LlmException.NoApiKey()

    private fun httpRequest(request: LlmRequest, key: String, stream: Boolean): Request {
        val payload = GeminiRequest(
            contents = request.messages.map { message ->
                GeminiContent(
                    role = if (message.role == LlmRole.USER) "user" else "model",
                    parts = listOf(GeminiPart(text = message.text))
                )
            },
            systemInstruction = request.system.takeIf { it.isNotEmpty() }?.let { blocks ->
                GeminiContent(parts = blocks.map { GeminiPart(text = it.text) })
            },
            generationConfig = GeminiGenerationConfig(
                // En Gemini 3 el razonamiento cuenta dentro de maxOutputTokens: se le deja margen.
                maxOutputTokens = request.maxTokens + request.thinking.margin,
                thinkingConfig = GeminiThinkingConfig(request.thinking.name.lowercase()),
                responseMimeType = if (request.jsonOutput) "application/json" else null
            )
        )
        val method = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        return Request.Builder()
            .url("$BASE_URL/${request.model}:$method")
            .header("x-goog-api-key", key)
            .post(geminiJson.encodeToString(GeminiRequest.serializer(), payload).toRequestBody(JSON))
            .build()
    }

    private suspend fun ensureSuccess(response: Response) {
        if (response.isSuccessful) return
        val body = response.body.string()
        val error = runCatching { geminiJson.decodeFromString<GeminiErrorBody>(body).error }.getOrNull()
        // El 429 de cuota diaria trae un `quotaId` con «PerDay» (los de por minuto, «PerMinute»).
        if (response.code == HTTP_TOO_MANY_REQUESTS && "PerDay" in body) usage.markDailyQuotaExhausted()
        throw translateGeminiError(GeminiApiException(response.code, error?.status.orEmpty(), error?.message.orEmpty()))
    }

    private suspend fun record(meta: GeminiUsage): LlmUsage {
        // Los tokens de razonamiento se cobran como salida.
        val tokens = LlmUsage(
            inputTokens = meta.promptTokenCount,
            outputTokens = meta.candidatesTokenCount + meta.thoughtsTokenCount,
            cacheReadTokens = meta.cachedContentTokenCount
        )
        usage.add(tokens.inputTokens, tokens.outputTokens, tokens.cacheReadTokens)
        return tokens
    }

    private companion object {
        const val TAG = "GeminiLlmClient"
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val SSE_DATA = "data:"
        const val CONNECT_TIMEOUT_S = 30L
        const val READ_TIMEOUT_S = 180L
        // Un 503 de Gemini («El servicio está saturado») suele ser intermitente: merece la pena reintentar.
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1_000L
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
