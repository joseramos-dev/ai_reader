package dev.joseramos.aireader.ai.llm

import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.core.data.settings.SecretStore
import dev.joseramos.aireader.core.data.settings.UsageRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.EventListener
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
class GeminiLlmClient(
    private val secrets: SecretStore,
    private val usage: UsageRepository,
    private val budgetNotifier: BudgetNotifier,
    private val io: CoroutineDispatcher
) : LlmClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        // El modelo puede pensar un rato antes de enviar el primer fragmento.
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    override suspend fun hasApiKey(): Boolean = secrets.apiKey() != null

    override fun streamChat(request: LlmRequest): Flow<LlmEvent> = callbackFlow {
        var activeCall: Call? = null
        // Se avisa en cuanto la pregunta ha salido: Gemini no manda ni las cabeceras hasta que empieza a
        // responder, y mientras tanto (puede ser mucho rato) la fase debe ser «esperando», no «enviando».
        val sent = object : EventListener() {
            override fun requestBodyEnd(call: Call, byteCount: Long) {
                trySend(LlmEvent.Sent)
            }
        }
        val client = http.newBuilder().eventListener(sent).build()
        val job = launch(io) {
            try {
                val retrying: suspend (Int) -> Unit = { attempt -> send(LlmEvent.Retrying(attempt, MAX_ATTEMPTS)) }
                withOverloadRetry("Chat con", request.model, onRetry = retrying) {
                    val started = TimeSource.Monotonic.markNow()
                    val call = client.newCall(httpRequest(request, apiKey(), stream = true))
                    activeCall = call
                    val fragments = AtomicInteger()
                    val stalled = AtomicBoolean()
                    // Saturado, Gemini puede tener la petición minutos sin responder y acabar con un 503: si en
                    // [FIRST_TEXT_TIMEOUT_MS] no llega ni una palabra, se corta y se vuelve a intentar.
                    val watchdog = launch {
                        delay(FIRST_TEXT_TIMEOUT_MS)
                        if (fragments.get() == 0) {
                            stalled.set(true)
                            call.cancel()
                        }
                    }
                    try {
                        streamAttempt(call, request.model, started, fragments)
                    } catch (e: IOException) {
                        if (!stalled.get()) throw e
                        val seconds = FIRST_TEXT_TIMEOUT_MS / MS_PER_SECOND
                        throw LlmException.Overloaded(IOException("Sin respuesta en $seconds s", e))
                    } finally {
                        watchdog.cancel()
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

    /** Un intento del chat: lee el stream, avisando de cada trozo de texto, y comprueba que terminó bien. */
    private suspend fun ProducerScope<LlmEvent>.streamAttempt(
        call: Call,
        model: String,
        started: TimeSource.Monotonic.ValueTimeMark,
        fragments: AtomicInteger
    ) {
        call.execute().use { response ->
            Log.i(
                TAG,
                "Chat con $model: HTTP ${response.code} a los ${started.elapsedNow().inWholeMilliseconds} ms " +
                    "(${response.protocol})"
            )
            ensureSuccess(response)
            val source = response.body.source()
            val end = readGeminiStream(source::readUtf8Line) {
                if (fragments.getAndIncrement() == 0) {
                    Log.i(TAG, "Chat con $model: primer texto a los ${started.elapsedNow().inWholeMilliseconds} ms")
                }
                send(LlmEvent.Text(it))
            }
            Log.i(
                TAG,
                "Chat con $model: fin a los ${started.elapsedNow().inWholeMilliseconds} ms, ${fragments.get()} " +
                    "fragmentos de texto; ${describe(end.usage)}; final ${end.finishReason ?: "ninguno"}"
            )
            val tokens = record(end.usage)
            if (end.blockReason != null) {
                Log.w(TAG, "Gemini bloqueó la respuesta del chat: ${end.blockReason}")
                throw LlmException.Refused()
            }
            // Sin el evento final con «STOP», lo recibido es solo el principio de la respuesta.
            if (end.finishReason != FINISH_STOP) {
                Log.w(TAG, "La respuesta del chat se cortó: ${end.finishReason ?: "stream cerrado sin final"}")
                throw LlmException.Incomplete()
            }
            send(LlmEvent.Done(tokens))
        }
    }

    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(io) {
        withOverloadRetry("Petición a", request.model) {
            val started = TimeSource.Monotonic.markNow()
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
            val usageMetadata = parsed.usageMetadata ?: GeminiUsage()
            Log.i(
                TAG,
                "Petición a ${request.model}: ${started.elapsedNow().inWholeMilliseconds} ms; " +
                    "${describe(usageMetadata)}; final ${parsed.finishReason ?: "ninguno"}"
            )
            val tokens = record(usageMetadata)
            if (parsed.blocked && parsed.text.isBlank()) {
                Log.w(TAG, "Gemini bloqueó la respuesta: ${parsed.blockReason}")
                throw LlmException.Refused()
            }
            // Cortada por el límite de salida u otro motivo. Quien la pide decide: un JSON cortado no se
            // podrá leer y se reintenta; aquí solo se deja constancia para poder diagnosticarlo.
            val reason = parsed.finishReason
            if (!parsed.blocked && reason != null && reason != FINISH_STOP) Log.w(TAG, "La respuesta se cortó: $reason")
            LlmResponse(parsed.text, tokens)
        }
    }

    /** Los tokens que cuenta Gemini, para el registro. */
    private fun describe(usage: GeminiUsage) =
        "entrada ${usage.promptTokenCount} tokens (${usage.cachedContentTokenCount} en caché), " +
            "salida ${usage.candidatesTokenCount}, razonamiento ${usage.thoughtsTokenCount}"

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
                responseMimeType = if (request.jsonOutput || request.jsonSchema != null) "application/json" else null,
                responseSchema = request.jsonSchema
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
        val before = usage.today.first()
        usage.add(tokens.inputTokens, tokens.outputTokens, tokens.cacheReadTokens)
        runCatching { budgetNotifier.onUsageChanged(before, usage.today.first()) }
            .onFailure { Log.w(TAG, "No se pudo avisar del consumo", it) }
        return tokens
    }

    private companion object {
        const val TAG = "GeminiLlmClient"
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val CONNECT_TIMEOUT_S = 30L
        const val READ_TIMEOUT_S = 180L

        /**
         * Lo que se espera al primer texto del chat. Con el razonamiento bajo suele llegar en segundos; saturado,
         * Gemini llegó a tener la petición 2,5 minutos para acabar en 503 (medido el 2026-10-07).
         */
        const val FIRST_TEXT_TIMEOUT_MS = 60_000L
        const val MS_PER_SECOND = 1_000L
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
