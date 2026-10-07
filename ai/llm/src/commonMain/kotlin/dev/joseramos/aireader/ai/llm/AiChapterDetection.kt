package dev.joseramos.aireader.ai.llm

import dev.joseramos.aireader.core.data.settings.SettingsRepository
import dev.joseramos.aireader.indexing.LlmChapterDetection
import dev.joseramos.aireader.text.DetectedChapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Detección de capítulos con IA para libros sin índice ni encabezados reconocibles: se le pasa
 * al modelo el principio de cada página y devuelve una lista JSON que se valida antes de usarla.
 */
class AiChapterDetection(
    private val llm: LlmClient,
    private val prompts: Prompts,
    private val settings: SettingsRepository
) : LlmChapterDetection {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Item(val title: String, val page: Int)

    override suspend fun detect(pageHeads: List<Pair<Int, String>>, pageCount: Int): List<DetectedChapter> {
        if (!llm.hasApiKey()) return emptyList()
        val listing = candidateHeads(pageHeads).joinToString("\n") { (page, head) -> "$page: $head" }
            .take(MAX_LISTING_CHARS)
        val prompt = prompts.render("chapters_detect_v1", "pages" to pageCount, "text" to listing)
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_DELAY_MS)
            val answer = llm.complete(
                LlmRequest(
                    model = settings.settings.first().analysisModel,
                    messages = listOf(LlmMessage(LlmRole.USER, prompt)),
                    maxTokens = MAX_TOKENS,
                    jsonSchema = SCHEMA
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

    internal companion object {
        /**
         * Solo las páginas que pueden empezar un capítulo, para no gastar tokens en el resto: fuera las
         * que empiezan a mitad de frase (en minúscula) y las cabeceras que se repiten en muchas páginas
         * (el título del libro o del capítulo en lo alto de cada página). Cada línea, como mucho de
         * [HEAD_CHARS] caracteres.
         */
        fun candidateHeads(heads: List<Pair<Int, String>>): List<Pair<Int, String>> {
            val lines = heads.map { (page, head) -> page to head.trim().take(HEAD_CHARS) }
                .filter { it.second.isNotEmpty() }
            val repeated = lines.groupingBy { it.second.lowercase() }.eachCount()
                .filterValues { it >= MIN_REPEATS && it > lines.size * RUNNING_HEADER_SHARE }.keys
            return lines.filter { (_, head) -> !head.first().isLowerCase() && head.lowercase() !in repeated }
        }

        /** `[{"title": "…", "page": 7}]`: con el esquema, Gemini no devuelve JSON inválido. */
        private val SCHEMA = ResponseSchema.array(
            ResponseSchema.obj("title" to ResponseSchema.string(), "page" to ResponseSchema.integer)
        )

        private const val HEAD_CHARS = 80
        private const val MIN_REPEATS = 3
        private const val RUNNING_HEADER_SHARE = 0.3
        const val ATTEMPTS = 2
        const val RETRY_DELAY_MS = 500L
        const val MAX_TOKENS = 4_000L
        const val MAX_LISTING_CHARS = 60_000
    }
}
