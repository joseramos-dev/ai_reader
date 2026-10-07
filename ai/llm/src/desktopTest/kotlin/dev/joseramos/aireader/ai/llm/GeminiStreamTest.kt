package dev.joseramos.aireader.ai.llm

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiStreamTest {
    private fun text(text: String, finish: String? = null, usage: String = "") =
        """data: {"candidates": [{"content": {"role": "model", "parts": [{"text": "$text"}]}""" +
            (finish?.let { """, "finishReason": "$it"""" } ?: "") + "}]" + usage + "}"

    private val overloaded =
        """data: {"error": {"code": 503, "message": "This model is currently experiencing high demand.", """ +
            """"status": "UNAVAILABLE"}}"""

    /** Lee [events] como lo haría el cliente (líneas SSE separadas por una en blanco) y junta el texto. */
    private suspend fun read(vararg events: String): Pair<String, GeminiStreamEnd> {
        val lines = events.flatMap { listOf(it, "") }.iterator()
        val text = StringBuilder()
        val end = readGeminiStream({ if (lines.hasNext()) lines.next() else null }) { text.append(it) }
        return text.toString() to end
    }

    @Test
    fun completeStreamEndsWithStopAndTheLastUsage() = runTest {
        val (text, end) = read(
            text("Raskólnikov "),
            text("esconde los objetos.", finish = "STOP", usage = """, "usageMetadata": {"promptTokenCount": 9}""")
        )

        assertEquals("Raskólnikov esconde los objetos.", text)
        assertEquals(FINISH_STOP, end.finishReason)
        assertEquals(9L, end.usage.promptTokenCount)
        assertNull(end.blockReason)
    }

    @Test
    fun streamClosedWithoutFinalEventHasNoFinishReason() = runTest {
        val (text, end) = read(text("La obra sigue a Raskolnikof, un joven estudiante sumido en una"))

        assertEquals("La obra sigue a Raskolnikof, un joven estudiante sumido en una", text)
        assertNull(end.finishReason)
    }

    @Test
    fun answerCutByTheOutputLimitKeepsItsReason() = runTest {
        val (_, end) = read(text("Primera parte", finish = "MAX_TOKENS"))

        assertEquals("MAX_TOKENS", end.finishReason)
    }

    @Test
    fun errorEventAfterTextMakesTheAnswerIncomplete() = runTest {
        try {
            read(text("A partir de los fragmentos"), overloaded)
            fail("Debería haber lanzado Incomplete")
        } catch (e: LlmException.Incomplete) {
            // No es Overloaded: reintentar repetiría el texto que ya se ha mostrado.
            assertEquals(503, (e.cause as GeminiApiException).code)
        }
    }

    @Test
    fun errorEventBeforeAnyTextIsTranslatedSoItCanBeRetried() = runTest {
        try {
            read(overloaded)
            fail("Debería haber lanzado Overloaded")
        } catch (e: LlmException.Overloaded) {
            assertTrue(e.cause is GeminiApiException)
        }
    }

    @Test
    fun blockedAnswerKeepsTheFirstReason() = runTest {
        val (_, end) = read(text("Empieza"), """data: {"candidates": [{"finishReason": "PROHIBITED_CONTENT"}]}""")

        assertEquals("PROHIBITED_CONTENT", end.blockReason)
    }
}
