package dev.joseramos.aireader.ai.llm

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Reintentos cuando Gemini está saturado (503): cuántos, cuánto se espera y qué se avisa. */
class OverloadRetryTest {
    private val overloaded = LlmException.Overloaded(GeminiApiException(503, "UNAVAILABLE", "high demand"))

    @Test
    fun retriesWhileOverloadedWaitingLongerEachTime() = runTest {
        val retries = mutableListOf<Int>()
        var calls = 0

        val result = withOverloadRetry("Chat con", "gemini-x", listOf(2_000L, 5_000L), { retries += it }) {
            if (++calls < 3) throw overloaded
            "respuesta"
        }

        assertEquals("respuesta", result)
        assertEquals(listOf(2, 3), retries)
        assertEquals(7_000L, currentTime)
    }

    @Test
    fun givesUpNamingTheModelAfterTheLastAttempt() = runTest {
        var calls = 0
        try {
            withOverloadRetry<Unit>("Chat con", "gemini-x", listOf(2_000L, 5_000L)) {
                calls++
                throw overloaded
            }
            fail("Debería haber lanzado Overloaded")
        } catch (e: LlmException.Overloaded) {
            assertEquals(3, calls)
            assertTrue(e.message.orEmpty().contains("gemini-x"))
            assertTrue(e.cause is GeminiApiException)
        }
    }

    @Test
    fun otherErrorsAreNotRetried() = runTest {
        var calls = 0
        try {
            withOverloadRetry<Unit>("Petición a", "gemini-x") {
                calls++
                throw LlmException.RateLimited(GeminiApiException(429, "RESOURCE_EXHAUSTED", "Cuota"))
            }
            fail("Debería haber lanzado RateLimited")
        } catch (_: LlmException.RateLimited) {
            assertEquals(1, calls)
        }
    }
}
