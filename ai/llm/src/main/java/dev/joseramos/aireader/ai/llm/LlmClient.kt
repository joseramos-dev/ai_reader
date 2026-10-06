package dev.joseramos.aireader.ai.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

enum class LlmRole { USER, ASSISTANT }

data class LlmMessage(val role: LlmRole, val text: String)

/**
 * Bloque del prompt de sistema. Los bloques fijos van primero: la caché implícita de la API
 * aprovecha que varias peticiones empiecen igual.
 */
data class SystemBlock(val text: String)

/**
 * Cuánto razona el modelo antes de responder. Más razonamiento es más lento y gasta más tokens
 * de salida; [margin] es lo que se suma al límite de salida para que no se coma la respuesta.
 */
enum class Thinking(val margin: Long) {
    MINIMAL(NO_MARGIN),
    LOW(LOW_MARGIN),
    MEDIUM(MEDIUM_MARGIN),
    HIGH(HIGH_MARGIN)
}

private const val NO_MARGIN = 0L
private const val LOW_MARGIN = 2_000L
private const val MEDIUM_MARGIN = 6_000L
private const val HIGH_MARGIN = 16_000L

data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val system: List<SystemBlock> = emptyList(),
    /** Límite de la respuesta visible (sin contar el razonamiento). */
    val maxTokens: Long = DEFAULT_MAX_TOKENS,
    val thinking: Thinking = Thinking.LOW,
    /** Pide que la respuesta sea JSON válido (detección de capítulos, clasificación, personajes). */
    val jsonOutput: Boolean = false,
    /**
     * Esquema que debe cumplir la respuesta JSON (formato `Schema` de Gemini). Con él la respuesta sale
     * siempre bien formada y no hace falta repetir la petición por un JSON inválido.
     */
    val jsonSchema: JsonObject? = null
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
    class NoApiKey : LlmException("Añade tu clave de API de Gemini en Ajustes.")

    class Unauthorized(cause: Throwable) : LlmException("La clave de API no es válida o no tiene permisos.", cause)

    class RateLimited(cause: Throwable) :
        LlmException(
            "Has alcanzado el límite de peticiones de Gemini (el nivel gratuito tiene un máximo por minuto y por " +
                "día). Prueba más tarde.",
            cause
        )

    class Overloaded(cause: Throwable) :
        LlmException("El servicio está saturado. Prueba de nuevo en un momento.", cause)

    class Network(cause: Throwable) : LlmException("No hay conexión con el servicio de IA.", cause)

    class Refused : LlmException("El modelo no ha podido responder a esta petición.")

    /**
     * La respuesta se cortó antes de terminar: llegó al límite de salida, Gemini falló con ella a medias
     * o la conexión se cerró sin el final. Lo recibido no se debe usar como si estuviera completo.
     */
    class Incomplete(cause: Throwable? = null) :
        LlmException("La respuesta se cortó antes de terminar. Prueba de nuevo.", cause)

    class Failed(message: String, cause: Throwable? = null) : LlmException(message, cause)
}

/** Cliente de un modelo de lenguaje. La app usa Gemini, pero la UI solo conoce esta interfaz. */
interface LlmClient {
    /** Respuesta en streaming: varios [LlmEvent.Text] y un [LlmEvent.Done] al final. */
    fun streamChat(request: LlmRequest): Flow<LlmEvent>

    suspend fun complete(request: LlmRequest): LlmResponse

    suspend fun hasApiKey(): Boolean
}
