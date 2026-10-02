package dev.joseramos.aireader.ai.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.http.StreamResponse
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import dev.joseramos.aireader.core.common.IoDispatcher
import dev.joseramos.aireader.core.data.settings.SecretStore
import dev.joseramos.aireader.core.data.settings.UsageRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [LlmClient] sobre la API de Claude con el SDK oficial de Java. Usa el endpoint beta para poder
 * pedir `fallbacks: "default"` en Opus y Sonnet (si las salvaguardas rechazan una petición, la API
 * la reintenta en el modelo que recomienda). Registra los tokens usados.
 */
@Singleton
class ClaudeLlmClient @Inject constructor(
    private val secrets: SecretStore,
    private val usage: UsageRepository,
    @IoDispatcher private val io: CoroutineDispatcher
) : LlmClient {
    @Volatile private var cached: Pair<String, AnthropicClient>? = null

    override suspend fun hasApiKey(): Boolean = secrets.apiKey() != null

    // El SDK puede lanzar excepciones de varios tipos; todas se traducen a LlmException.
    @Suppress("TooGenericExceptionCaught")
    override fun streamChat(request: LlmRequest): Flow<LlmEvent> = callbackFlow {
        val client = client()
        var stream: StreamResponse<BetaRawMessageStreamEvent>? = null
        val job = launch(io) {
            try {
                var input = 0L
                var cacheRead = 0L
                var output = 0L
                var refused = false
                client.beta().messages().createStreaming(params(request)).use { response ->
                    stream = response
                    response.stream().forEach { event ->
                        event.messageStart().ifPresent { start ->
                            input = start.message().usage().inputTokens()
                            cacheRead = start.message().usage().cacheReadInputTokens().orElse(0)
                        }
                        event.contentBlockDelta().ifPresent { delta ->
                            delta.delta().text().ifPresent { trySend(LlmEvent.Text(it.text())) }
                        }
                        event.messageDelta().ifPresent { delta ->
                            output = delta.usage().outputTokens()
                            refused = delta.delta().stopReason().orElse(null) == BetaStopReason.REFUSAL
                        }
                    }
                }
                val totals = LlmUsage(input, output, cacheRead)
                usage.add(totals.inputTokens, totals.outputTokens, totals.cacheReadTokens)
                if (refused) throw LlmException.Refused()
                send(LlmEvent.Done(totals))
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                close(e)
            } catch (e: Exception) {
                close(translate(e))
            }
        }
        // Cerrar el stream corta la conexión HTTP si quien escucha cancela (por ejemplo, al salir del chat).
        awaitClose {
            stream?.close()
            job.cancel()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(io) {
        val client = client()
        val message = try {
            client.beta().messages().create(params(request))
        } catch (e: Exception) {
            throw translate(e)
        }
        if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw LlmException.Refused()
        val text = message.content().mapNotNull { block ->
            block.text().map { it.text() }.orElse(null)
        }.joinToString("")
        val tokens = message.usage().let {
            LlmUsage(it.inputTokens(), it.outputTokens(), it.cacheReadInputTokens().orElse(0))
        }
        usage.add(tokens.inputTokens, tokens.outputTokens, tokens.cacheReadTokens)
        LlmResponse(text, tokens)
    }

    private suspend fun client(): AnthropicClient {
        val key = secrets.apiKey() ?: throw LlmException.NoApiKey()
        cached?.takeIf { it.first == key }?.let { return it.second }
        return AnthropicOkHttpClient.builder().apiKey(key).build().also { cached = key to it }
    }

    private fun params(request: LlmRequest): MessageCreateParams = MessageCreateParams.builder()
        .model(request.model)
        .maxTokens(request.maxTokens)
        .apply {
            if (request.system.isNotEmpty()) {
                systemOfBetaTextBlockParams(
                    request.system.map { block ->
                        BetaTextBlockParam.builder().text(block.text).apply {
                            if (block.cache) cacheControl(BetaCacheControlEphemeral.builder().build())
                        }.build()
                    }
                )
            }
            request.messages.forEach { message ->
                when (message.role) {
                    LlmRole.USER -> addUserMessage(message.text)
                    LlmRole.ASSISTANT -> addAssistantMessage(message.text)
                }
            }
            if (request.effort != null && supportsEffort(request.model)) {
                outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(request.effort)).build())
            }
            if (request.model in MODELS_WITH_FALLBACK) {
                addBeta(FALLBACK_BETA)
                putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
        }
        .build()

    private fun supportsEffort(model: String) = !model.startsWith("claude-haiku")

    private fun translate(error: Exception): LlmException = when (error) {
        is UnauthorizedException, is PermissionDeniedException -> LlmException.Unauthorized(error)
        is RateLimitException -> LlmException.RateLimited(error)
        is AnthropicServiceException ->
            if (error.statusCode() == HTTP_OVERLOADED || error.statusCode() >= HTTP_SERVER_ERROR) {
                LlmException.Overloaded(error)
            } else {
                LlmException.Failed(error.message ?: "Error de la API (${error.statusCode()})", error)
            }
        is AnthropicIoException -> LlmException.Network(error)
        else -> LlmException.Failed(error.message ?: error.javaClass.simpleName, error)
    }

    private companion object {
        /** Modelos que admiten la recuperación automática ante rechazos (`fallbacks: "default"`). */
        val MODELS_WITH_FALLBACK = setOf("claude-opus-5-5", "claude-sonnet-5-5")
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val HTTP_OVERLOADED = 529
        const val HTTP_SERVER_ERROR = 500
    }
}
