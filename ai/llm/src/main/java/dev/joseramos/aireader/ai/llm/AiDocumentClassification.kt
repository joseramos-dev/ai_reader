package dev.joseramos.aireader.ai.llm

import dev.joseramos.aireader.core.data.db.DocumentType
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import dev.joseramos.aireader.indexing.LlmDocumentClassification
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Confirma el tipo de documento con el modelo de resúmenes (Gemini Flash-Lite por defecto) cuando la
 * heurística local no es concluyente. Recibe título, capítulos y unas 3.000 palabras del principio.
 */
class AiDocumentClassification @Inject constructor(
    private val llm: LlmClient,
    private val prompts: Prompts,
    private val settings: SettingsRepository
) : LlmDocumentClassification {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Answer(@SerialName("tipo") val type: String)

    override suspend fun classify(title: String, chapterTitles: List<String>, sample: String): DocumentType? {
        if (!llm.hasApiKey()) return null
        val answer = llm.complete(
            LlmRequest(
                model = settings.settings.first().summaryModel,
                messages = listOf(
                    LlmMessage(
                        LlmRole.USER,
                        prompts.render(
                            R.raw.document_classify_v1,
                            "title" to title,
                            "chapters" to chapterTitles.take(MAX_CHAPTERS).joinToString(" · ").ifEmpty { "(ninguno)" },
                            "text" to sample
                        )
                    )
                ),
                maxTokens = MAX_TOKENS,
                thinking = Thinking.MINIMAL,
                jsonOutput = true
            )
        ).text
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val parsed = runCatching { json.decodeFromString<Answer>(answer.substring(start, end + 1)) }.getOrNull()
        return parsed?.let { runCatching { DocumentType.valueOf(it.type.trim().uppercase()) }.getOrNull() }
    }

    private companion object {
        const val MAX_CHAPTERS = 40
        const val MAX_TOKENS = 300L
    }
}
