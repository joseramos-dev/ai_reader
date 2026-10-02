package dev.joseramos.aireader.ai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiApiTest {
    @Test
    fun requestUsesTheRestFieldNamesAndOmitsEmptyOptions() {
        val request = GeminiRequest(
            contents = listOf(GeminiContent("user", listOf(GeminiPart(text = "Hola")))),
            generationConfig = GeminiGenerationConfig(
                maxOutputTokens = 100,
                thinkingConfig = GeminiThinkingConfig("low")
            )
        )
        val json = geminiJson.encodeToString(GeminiRequest.serializer(), request)
        assertEquals(
            """{"contents":[{"role":"user","parts":[{"text":"Hola"}]}],""" +
                """"generationConfig":{"maxOutputTokens":100,"thinkingConfig":{"thinkingLevel":"low"}}}""",
            json
        )
    }

    @Test
    fun responseTextSkipsThoughtsAndReadsUsage() {
        val response = geminiJson.decodeFromString<GeminiResponse>(
            """
            {"candidates": [{"content": {"role": "model", "parts": [
                {"text": "pensando…", "thought": true}, {"text": "Hola, "}, {"text": "mundo"}]},
              "finishReason": "STOP"}],
             "usageMetadata": {"promptTokenCount": 12, "candidatesTokenCount": 3, "thoughtsTokenCount": 40,
               "totalTokenCount": 55}}
            """.trimIndent()
        )
        assertEquals("Hola, mundo", response.text)
        assertFalse(response.blocked)
        assertEquals(40L, response.usageMetadata?.thoughtsTokenCount)
    }

    @Test
    fun blockedPromptsAndAnswersAreDetected() {
        val prompt = geminiJson.decodeFromString<GeminiResponse>("""{"promptFeedback": {"blockReason": "SAFETY"}}""")
        val answer = geminiJson.decodeFromString<GeminiResponse>(
            """{"candidates": [{"finishReason": "PROHIBITED_CONTENT"}]}"""
        )
        assertTrue(prompt.blocked)
        assertTrue(answer.blocked)
        assertEquals("", answer.text)
    }
}
