package dev.joseramos.aireader.ai.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Cuerpos JSON de la API REST de Gemini (`models.generateContent` y `streamGenerateContent`),
// solo con los campos que usa la app: https://ai.google.dev/api/generate-content

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
    val responseMimeType: String? = null
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

@Serializable
internal data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val promptFeedback: GeminiPromptFeedback? = null,
    val usageMetadata: GeminiUsage? = null
) {
    /** Texto de la respuesta, sin el razonamiento interno del modelo. */
    val text: String
        get() = candidates.firstOrNull()?.content?.parts.orEmpty()
            .filter { it.thought != true }
            .mapNotNull { it.text }
            .joinToString("")

    /** La petición o la respuesta se ha bloqueado por las políticas de contenido. */
    val blocked: Boolean
        get() = promptFeedback?.blockReason != null || candidates.firstOrNull()?.finishReason in BLOCKED_REASONS

    private companion object {
        val BLOCKED_REASONS = setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII")
    }
}

@Serializable
internal data class GeminiErrorBody(val error: GeminiError? = null)

@Serializable
internal data class GeminiError(val code: Int = 0, val message: String = "", val status: String = "")

internal val geminiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
