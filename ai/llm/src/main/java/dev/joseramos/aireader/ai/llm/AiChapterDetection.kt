package dev.joseramos.aireader.ai.llm

import dev.joseramos.aireader.core.data.settings.SettingsRepository
import dev.joseramos.aireader.indexing.LlmChapterDetection
import dev.joseramos.aireader.text.DetectedChapter
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Detección de capítulos con IA para libros sin índice ni encabezados reconocibles: se le pasa
 * al modelo el principio de cada página y devuelve una lista JSON que se valida antes de usarla.
 */
class AiChapterDetection @Inject constructor(
    private val llm: LlmClient,
    private val prompts: Prompts,
    private val settings: SettingsRepository
) : LlmChapterDetection {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Item(val title: String, val page: Int)

    override suspend fun detect(pageHeads: List<Pair<Int, String>>, pageCount: Int): List<DetectedChapter> {
        if (!llm.hasApiKey()) return emptyList()
        val listing = pageHeads.joinToString("\n") { (page, head) -> "$page: $head" }.take(MAX_LISTING_CHARS)
        val prompt = prompts.render(R.raw.chapters_detect_v1, "pages" to pageCount, "text" to listing)
        repeat(ATTEMPTS) {
            val answer = llm.complete(
                LlmRequest(
                    model = settings.settings.first().summaryModel,
                    messages = listOf(LlmMessage(LlmRole.USER, prompt)),
                    maxTokens = MAX_TOKENS,
                    jsonOutput = true
                )
            ).text
            parse(answer, pageCount)?.let { return it }
        }
        return emptyList()
    }

    private fun parse(answer: String, pageCount: Int): List<DetectedChapter>? {
        val start = answer.indexOf('[')
        val end = answer.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val items =
            runCatching { json.decodeFromString<List<Item>>(answer.substring(start, end + 1)) }.getOrNull()
                ?: return null
        val valid = items
            .filter { it.title.isNotBlank() && it.page in 1..pageCount }
            .sortedBy { it.page }
            .distinctBy { it.page }
            .map { DetectedChapter(it.title.trim(), it.page) }
        return valid.takeIf { it.size >= 2 }
    }

    private companion object {
        const val ATTEMPTS = 2
        const val MAX_TOKENS = 4_000L
        const val MAX_LISTING_CHARS = 60_000
    }
}
