package dev.joseramos.aireader.tts

import java.io.IOException

/** Error HTTP de Google Cloud TTS, como causa de los fallos de síntesis (nunca incluye la clave). */
class CloudTtsApiException(val code: Int, message: String) : IOException("$code: $message")

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500

/**
 * Traduce un error de red o un [CloudTtsApiException] (código HTTP de Google Cloud TTS) a un
 * mensaje claro, sin filtrar nunca la clave de API. Aparte como función pura para poder probar
 * el mapeo sin tener que montar todo [GoogleCloudTtsEngine].
 */
internal fun translateCloudTtsError(error: IOException): IOException = when {
    error !is CloudTtsApiException -> IOException("No se pudo contactar con Google Cloud TTS", error)
    error.code == HTTP_UNAUTHORIZED || error.code == HTTP_FORBIDDEN ->
        IOException("La clave de API de Google Cloud TTS no es válida", error)
    error.code == HTTP_TOO_MANY_REQUESTS -> IOException("Límite de Google Cloud TTS superado", error)
    error.code >= HTTP_SERVER_ERROR -> IOException("Google Cloud TTS no está disponible ahora", error)
    else -> IOException("Error de Google Cloud TTS (${error.code})", error)
}
