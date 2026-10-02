package dev.joseramos.aireader.ai.llm

import kotlinx.coroutines.flow.Flow

enum class LlmRole { USER, ASSISTANT }

data class LlmMessage(val role: LlmRole, val text: String)

/** Bloque del prompt de sistema. Con [cache], se marca para la caché de prompts de la API. */
data class SystemBlock(val text: String, val cache: Boolean = false)

data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val system: List<SystemBlock> = emptyList(),
    val maxTokens: Long = DEFAULT_MAX_TOKENS,
    /** `low`, `medium` o `high`; se ignora en modelos sin control de esfuerzo (Haiku). */
    val effort: String? = null
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 8_000L
    }
}

data class LlmUsage(val inputTokens: Long = 0, val outputTokens: Long = 0, val cacheReadTokens: Long = 0)

sealed interface LlmEvent {
    data class Text(val delta: String) : LlmEvent

    data class Done(val usage: LlmUsage) : LlmEvent
}

data class LlmResponse(val text: String, val usage: LlmUsage)

/** Errores del LLM ya traducidos a casos que la UI sabe explicar. */
sealed class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoApiKey : LlmException("Añade tu clave de API de Anthropic en Ajustes.")

    class Unauthorized(cause: Throwable) : LlmException("La clave de API no es válida o no tiene permisos.", cause)

    class RateLimited(cause: Throwable) :
        LlmException("Has alcanzado el límite de peticiones. Prueba en unos segundos.", cause)

    class Overloaded(cause: Throwable) :
        LlmException("El servicio está saturado. Prueba de nuevo en un momento.", cause)

    class Network(cause: Throwable) : LlmException("No hay conexión con el servicio de IA.", cause)

    class Refused : LlmException("El modelo no ha podido responder a esta petición.")

    class Failed(message: String, cause: Throwable? = null) : LlmException(message, cause)
}

/** Cliente de un modelo de lenguaje. La app usa Claude, pero la UI solo conoce esta interfaz. */
interface LlmClient {
    /** Respuesta en streaming: varios [LlmEvent.Text] y un [LlmEvent.Done] al final. */
    fun streamChat(request: LlmRequest): Flow<LlmEvent>

    suspend fun complete(request: LlmRequest): LlmResponse

    suspend fun hasApiKey(): Boolean
}
