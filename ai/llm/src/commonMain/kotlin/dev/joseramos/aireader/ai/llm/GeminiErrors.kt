package dev.joseramos.aireader.ai.llm

import java.io.IOException

/** Error HTTP de la API de Gemini, como causa de las [LlmException] (nunca incluye la clave). */
class GeminiApiException(val code: Int, val status: String, message: String) : IOException("$code $status: $message")

internal const val HTTP_BAD_REQUEST = 400
internal const val HTTP_UNAUTHORIZED = 401
internal const val HTTP_FORBIDDEN = 403
internal const val HTTP_TOO_MANY_REQUESTS = 429
internal const val HTTP_SERVER_ERROR = 500

/**
 * Traduce un error de red o un [GeminiApiException] (código HTTP de Gemini) a un [LlmException] que
 * la UI sabe explicar, sin perder la causa original (para el registro) ni filtrar nunca la clave de API.
 * Aparte como función pura para poder probar el mapeo sin tener que montar todo [GeminiLlmClient].
 */
internal fun translateGeminiError(error: IOException): LlmException = when {
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
