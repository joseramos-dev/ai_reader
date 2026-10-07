package dev.joseramos.aireader.ai.llm

import dev.joseramos.aireader.core.common.Log
import java.io.IOException
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

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

/**
 * Esperas entre intentos cuando Gemini está saturado (503). Suele ser intermitente: merece la pena reintentar, pero con
 * algo de margen entre intentos.
 */
internal val RETRY_DELAYS_MS = listOf(2_000L, 5_000L)
internal val MAX_ATTEMPTS = RETRY_DELAYS_MS.size + 1

private const val TAG = "GeminiLlmClient"

/**
 * Reintenta [block] si Gemini responde que está saturado ([LlmException.Overloaded]), esperando [delaysMs] entre un
 * intento y el siguiente y avisando con [onRetry] del intento que empieza. Solo se reintenta antes de que [block] haya
 * emitido nada (en el chat, antes del primer texto), para no duplicar una respuesta a medias. Si se agotan los
 * intentos, el error dice qué [model] estaba saturado. Deja en el registro cuánto tardó cada fallo, con el código y el
 * mensaje de Gemini (la causa).
 */
internal suspend fun <T> withOverloadRetry(
    what: String,
    model: String,
    delaysMs: List<Long> = RETRY_DELAYS_MS,
    onRetry: suspend (attempt: Int) -> Unit = {},
    block: suspend () -> T
): T {
    val attempts = delaysMs.size + 1
    var attempt = 1
    while (true) {
        val started = TimeSource.Monotonic.markNow()
        try {
            return block()
        } catch (e: LlmException) {
            val ms = started.elapsedNow().inWholeMilliseconds
            val wait = delaysMs.getOrNull(attempt - 1)
            if (e !is LlmException.Overloaded || wait == null) {
                Log.w(TAG, "$what $model: falló a los $ms ms en el intento $attempt de $attempts", e)
                throw if (e is LlmException.Overloaded) LlmException.Overloaded(e.cause ?: e, model) else e
            }
            Log.w(TAG, "$what $model: saturado a los $ms ms (intento $attempt de $attempts), se reintenta", e)
            delay(wait)
            attempt++
            onRetry(attempt)
        }
    }
}
