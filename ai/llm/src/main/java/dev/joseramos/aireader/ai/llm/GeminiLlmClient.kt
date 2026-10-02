package dev.joseramos.aireader.ai.llm

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Error HTTP de la API de Gemini, como causa de las [LlmException] (nunca incluye la clave). */
class GeminiApiException(val code: Int, val status: String, message: String) : IOException("$code $status: $message")

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
        val call = http.newCall(httpRequest(request, apiKey(), stream = true))
        val job = launch(io) {
            try {
                call.execute().use { response ->
                    ensureSuccess(response)
                    val (totals, blocked) = readEvents(response) { send(LlmEvent.Text(it)) }
                    val tokens = record(totals)
                    if (blocked) throw LlmException.Refused()
                    send(LlmEvent.Done(tokens))
                    close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                close(e)
            } catch (e: IOException) {
                close(if (call.isCanceled()) CancellationException("Cancelado") else translate(e))
            } catch (e: SerializationException) {
                close(LlmException.Failed("Respuesta inesperada de Gemini.", e))
            }
        }
        // Cancelar la llamada corta la conexión si quien escucha se va (por ejemplo, al salir del chat).
        awaitClose {
            call.cancel()
            job.cancel()
        }
    }

    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(io) {
        val call = http.newCall(httpRequest(request, apiKey(), stream = false))
        val body = try {
            call.execute().use { response ->
                ensureSuccess(response)
                response.body.string()
            }
        } catch (e: IOException) {
            throw translate(e)
        }
        val parsed = try {
            geminiJson.decodeFromString<GeminiResponse>(body)
        } catch (e: SerializationException) {
            throw LlmException.Failed("Respuesta inesperada de Gemini.", e)
        }
        val tokens = record(parsed.usageMetadata ?: GeminiUsage())
        if (parsed.blocked && parsed.text.isBlank()) throw LlmException.Refused()
        LlmResponse(parsed.text, tokens)
    }

    /**
     * Lee los eventos SSE (`data: {...}`, uno por fragmento) y pasa el texto a [onText]. Devuelve el
     * uso de tokens del último evento y si la respuesta se ha bloqueado.
     */
    private suspend fun readEvents(response: Response, onText: suspend (String) -> Unit): Pair<GeminiUsage, Boolean> {
        var totals = GeminiUsage()
        var blocked = false
        val source = response.body.source()
        var line = source.readUtf8Line()
        while (line != null) {
            if (line.startsWith(SSE_DATA)) {
                val chunk = geminiJson.decodeFromString<GeminiResponse>(line.removePrefix(SSE_DATA).trim())
                blocked = blocked || chunk.blocked
                if (chunk.text.isNotEmpty()) onText(chunk.text)
                chunk.usageMetadata?.let { totals = it }
            }
            line = source.readUtf8Line()
        }
        return totals to blocked
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
        throw translate(GeminiApiException(response.code, error?.status.orEmpty(), error?.message.orEmpty()))
    }

    private fun translate(error: IOException): LlmException = when {
        error !is GeminiApiException -> LlmException.Network(error)
        error.code == HTTP_BAD_REQUEST && "API key" in error.message.orEmpty() -> LlmException.Unauthorized(error)
        error.code == HTTP_UNAUTHORIZED || error.code == HTTP_FORBIDDEN -> LlmException.Unauthorized(error)
        error.code == HTTP_TOO_MANY_REQUESTS -> LlmException.RateLimited(error)
        error.code >= HTTP_SERVER_ERROR -> LlmException.Overloaded(error)
        "location is not supported" in error.message.orEmpty() -> LlmException.Failed(
            "La API de Gemini no está disponible en tu región con esta clave.",
            error
        )
        else -> LlmException.Failed(error.message ?: "Error de la API de Gemini (${error.code})", error)
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
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val SSE_DATA = "data:"
        const val CONNECT_TIMEOUT_S = 30L
        const val READ_TIMEOUT_S = 180L
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
