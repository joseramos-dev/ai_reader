package dev.joseramos.aireader.ai.llm

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapeo de los errores HTTP de Gemini a [LlmException] (el código más frágil del cliente: depende
 * de subcadenas del mensaje de error de Google, además del código HTTP).
 */
class GeminiErrorsTest {
    @Test
    fun aNonGeminiIOExceptionIsNetwork() {
        val error = translateGeminiError(IOException("Falló la conexión"))
        assertTrue(error is LlmException.Network)
    }

    @Test
    fun badRequestMentioningApiKeyIsUnauthorized() {
        val error = translateGeminiError(GeminiApiException(400, "INVALID_ARGUMENT", "API key not valid"))
        assertTrue(error is LlmException.Unauthorized)
    }

    @Test
    fun badRequestWithoutApiKeyMentionIsFailed() {
        val error = translateGeminiError(GeminiApiException(400, "INVALID_ARGUMENT", "Request malformed"))
        assertTrue(error is LlmException.Failed)
    }

    @Test
    fun unauthorizedIsUnauthorized() {
        val error = translateGeminiError(GeminiApiException(401, "UNAUTHENTICATED", "No autenticado"))
        assertTrue(error is LlmException.Unauthorized)
    }

    @Test
    fun forbiddenIsUnauthorized() {
        val error = translateGeminiError(GeminiApiException(403, "PERMISSION_DENIED", "Sin permiso"))
        assertTrue(error is LlmException.Unauthorized)
    }

    @Test
    fun tooManyRequestsIsRateLimited() {
        val error = translateGeminiError(GeminiApiException(429, "RESOURCE_EXHAUSTED", "Cuota superada"))
        assertTrue(error is LlmException.RateLimited)
    }

    @Test
    fun serverErrorIsOverloaded() {
        val error = translateGeminiError(GeminiApiException(500, "INTERNAL", "Fallo interno"))
        assertTrue(error is LlmException.Overloaded)
    }

    @Test
    fun serviceUnavailableIsOverloaded() {
        val error = translateGeminiError(GeminiApiException(503, "UNAVAILABLE", "El servicio está saturado"))
        assertTrue(error is LlmException.Overloaded)
    }

    @Test
    fun unsupportedLocationHasADedicatedMessage() {
        val error = translateGeminiError(
            GeminiApiException(400, "FAILED_PRECONDITION", "User location is not supported for the API use")
        )
        assertTrue(error is LlmException.Failed)
        assertEquals("La API de Gemini no está disponible en tu región con esta clave.", error.message)
    }

    @Test
    fun anyOtherCodeFallsBackToFailedWithTheOriginalMessage() {
        val error = translateGeminiError(GeminiApiException(404, "NOT_FOUND", "Modelo no encontrado"))
        assertTrue(error is LlmException.Failed)
        assertEquals("404 NOT_FOUND: Modelo no encontrado", error.message)
    }
}
