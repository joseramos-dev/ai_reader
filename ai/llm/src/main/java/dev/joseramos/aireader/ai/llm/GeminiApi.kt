package dev.joseramos.aireader.ai.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// Cuerpos JSON de la API REST de Gemini (`models.generateContent` y `streamGenerateContent`), solo
// con los campos que usa la app (https://ai.google.dev/api/generate-content), y lectura del stream.

@Serializable
internal data class GeminiPart(val text: String? = null, val thought: Boolean? = null)

/** [role]: `user` o `model`; en las instrucciones de sistema se omite. */
@Serializable
internal data class GeminiContent(val role: String? = null, val parts: List<GeminiPart> = emptyList())

/** [thinkingLevel]: `minimal`, `low`, `medium` o `high` (modelos Gemini 3). */
@Serializable
internal data class GeminiThinkingConfig(val thinkingLevel: String)

@Serializable
internal data class GeminiGenerationConfig(
    val maxOutputTokens: Long,
    val thinkingConfig: GeminiThinkingConfig? = null,
    val responseMimeType: String? = null,
    val responseSchema: JsonObject? = null
)

@Serializable
internal data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GeminiGenerationConfig
)

@Serializable
internal data class GeminiUsage(
    val promptTokenCount: Long = 0,
    val candidatesTokenCount: Long = 0,
    val thoughtsTokenCount: Long = 0,
    val cachedContentTokenCount: Long = 0
)

@Serializable
internal data class GeminiCandidate(val content: GeminiContent? = null, val finishReason: String? = null)

@Serializable
internal data class GeminiPromptFeedback(val blockReason: String? = null)

/**
 * Respuesta de `generateContent` o evento de `streamGenerateContent`. [error] solo llega como evento
 * del stream: si Gemini falla con la respuesta ya empezada (por ejemplo, un 503 por saturación), no
 * puede cambiar el código HTTP, que ya era 200, y manda el error como un evento más.
 */
@Serializable
internal data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val promptFeedback: GeminiPromptFeedback? = null,
    val usageMetadata: GeminiUsage? = null,
    val error: GeminiError? = null
) {
    /** Por qué terminó la respuesta: [FINISH_STOP] si acabó bien. En un stream, solo lo trae el último evento. */
    val finishReason: String?
        get() = candidates.firstOrNull()?.finishReason

    /** Texto de la respuesta, sin el razonamiento interno del modelo. */
    val text: String
        get() = candidates.firstOrNull()?.content?.parts.orEmpty()
            .filter { it.thought != true }
            .mapNotNull { it.text }
            .joinToString("")

    /** La petición o la respuesta se ha bloqueado por las políticas de contenido. */
    val blocked: Boolean
        get() = promptFeedback?.blockReason != null || candidates.firstOrNull()?.finishReason in BLOCKED_REASONS

    /** Motivo del bloqueo (p. ej. «SAFETY»), solo para el registro: nunca se muestra al usuario. */
    val blockReason: String?
        get() = promptFeedback?.blockReason
            ?: candidates.firstOrNull()?.finishReason?.takeIf { it in BLOCKED_REASONS }
}

// Fuera de la clase a propósito: en una clase @Serializable, un `private companion object` hace que
// `decodeFromString<GeminiResponse>()` lance IllegalAccessError en Android (y cierre la app).
private val BLOCKED_REASONS = setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII")

/** Motivo de fin de una respuesta completa. Los demás (`MAX_TOKENS`, `OTHER`…) la dejan cortada. */
internal const val FINISH_STOP = "STOP"

@Serializable
internal data class GeminiErrorBody(val error: GeminiError? = null)

@Serializable
internal data class GeminiError(val code: Int = 0, val message: String = "", val status: String = "")

internal val geminiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Lee una respuesta de `generateContent` o un evento de `streamGenerateContent`. Los tests usan
 * esta misma función: el compilador genera otro acceso al serializador desde los tests que desde la app.
 */
internal fun parseGeminiResponse(json: String): GeminiResponse = geminiJson.decodeFromString(json)

/**
 * Cómo terminó un stream de Gemini: el uso de tokens del último evento que lo trae, el motivo de fin
 * ([FINISH_STOP] si la respuesta está completa; `null` si el stream se cerró sin evento final) y, si la
 * respuesta se bloqueó, el motivo del bloqueo.
 */
internal data class GeminiStreamEnd(val usage: GeminiUsage, val finishReason: String?, val blockReason: String?)

private const val SSE_DATA = "data:"

/**
 * Lee los eventos SSE de `streamGenerateContent` (`data: {...}`, uno por fragmento) pidiendo líneas a
 * [nextLine] hasta que devuelve `null`, y pasa el texto a [onText]. Si Gemini manda un error como evento
 * (ver [GeminiResponse.error]) antes del primer texto, se lanza traducido, y un 503 se puede reintentar;
 * si llega con la respuesta a medias, se lanza [LlmException.Incomplete]: reintentar repetiría lo ya
 * mostrado, y lo recibido no se debe tomar por la respuesta entera.
 */
internal suspend fun readGeminiStream(nextLine: () -> String?, onText: suspend (String) -> Unit): GeminiStreamEnd {
    var usage = GeminiUsage()
    var finishReason: String? = null
    var blockReason: String? = null
    var emitted = false
    var line = nextLine()
    while (line != null) {
        if (line.startsWith(SSE_DATA)) {
            val chunk = parseGeminiResponse(line.removePrefix(SSE_DATA).trim())
            chunk.error?.let { error ->
                val cause = GeminiApiException(error.code, error.status, error.message)
                throw if (emitted) LlmException.Incomplete(cause) else translateGeminiError(cause)
            }
            if (blockReason == null) blockReason = chunk.blockReason
            if (chunk.text.isNotEmpty()) {
                onText(chunk.text)
                emitted = true
            }
            chunk.usageMetadata?.let { usage = it }
            chunk.finishReason?.let { finishReason = it }
        }
        line = nextLine()
    }
    return GeminiStreamEnd(usage, finishReason, blockReason)
}
