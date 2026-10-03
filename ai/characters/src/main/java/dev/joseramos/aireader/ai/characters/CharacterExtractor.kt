package dev.joseramos.aireader.ai.characters

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.joseramos.aireader.ai.llm.LlmClient
import dev.joseramos.aireader.ai.llm.LlmException
import dev.joseramos.aireader.ai.llm.LlmMessage
import dev.joseramos.aireader.ai.llm.LlmRequest
import dev.joseramos.aireader.ai.llm.LlmRole
import dev.joseramos.aireader.ai.llm.SummaryGenerator
import dev.joseramos.aireader.ai.llm.TokenEstimate
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.PageText
import dev.joseramos.aireader.core.data.db.CharacterDao
import dev.joseramos.aireader.core.data.db.CharacterScanEntity
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * Analiza un capítulo con el modelo de resúmenes (Gemini Flash-Lite por defecto): le pasa el texto,
 * con la página marcada, y la lista compacta de personajes ya conocidos, para que reutilice sus ids
 * en vez de duplicarlos.
 * Los capítulos largos van por bloques, y cada bloque ya conoce lo extraído en el anterior.
 */
class CharacterExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llm: LlmClient,
    private val content: BookContentRepository,
    private val dao: CharacterDao,
    private val merger: CharacterMerger
) {
    private val template by lazy {
        context.resources.openRawResource(R.raw.characters_extract_v1).bufferedReader().use { it.readText() }
    }

    suspend fun scan(bookId: String, bookTitle: String, chapter: Chapter, model: String) {
        val pages = content.pagesFrom(bookId, chapter.startPage, chapter.endPage - chapter.startPage + 1)
        val blocks = blocks(pages)
        blocks.forEachIndexed { i, block ->
            val part = if (blocks.size > 1) " (parte ${i + 1} de ${blocks.size})" else ""
            val prompt = render(
                "book" to bookTitle,
                "number" to chapter.number,
                "chapter" to chapter.title,
                "start" to chapter.startPage,
                "end" to chapter.endPage,
                "part" to part,
                "known" to known(bookId),
                "text" to block
            )
            extract(prompt, model)?.let { merger.merge(bookId, chapter, it) }
        }
        dao.upsertScan(CharacterScanEntity(bookId, chapter.id, model, System.currentTimeMillis()))
    }

    /**
     * Estimación local (sin llamar a la API) de lo que costaría analizar los capítulos que faltan:
     * los mismos bloques que [scan], cada uno con la plantilla y los personajes ya conocidos.
     */
    suspend fun estimate(bookId: String): TokenEstimate {
        val done = dao.scannedChapterIds(bookId).toSet()
        val overhead = (
            SummaryGenerator.estimateTokens(
                template
            ) + SummaryGenerator.estimateTokens(known(bookId))
            ).toLong()
        var estimate = TokenEstimate()
        for (chapter in content.chapters(bookId).filter { it.id !in done }) {
            val pages = content.pagesFrom(bookId, chapter.startPage, chapter.endPage - chapter.startPage + 1)
            for (block in blocks(pages)) {
                estimate += TokenEstimate(SummaryGenerator.estimateTokens(block) + overhead, ESTIMATED_OUTPUT_TOKENS)
            }
        }
        return estimate
    }

    /** Pide la extracción; si la respuesta no es JSON válido, lo intenta una vez más. */
    private suspend fun extract(prompt: String, model: String): Extraction? {
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_DELAY_MS)
            val answer = llm.complete(
                LlmRequest(
                    model = model,
                    messages = listOf(LlmMessage(LlmRole.USER, prompt)),
                    maxTokens = MAX_TOKENS,
                    jsonOutput = true
                )
            ).text
            Extraction.parse(answer)?.let { return it }
        }
        return null
    }

    /** «c12: Rodión Románovich Raskólnikov | Rodia | Rodka», uno por línea. */
    private suspend fun known(bookId: String): String {
        val names = dao.getNames(bookId).groupBy { it.characterId }
        return dao.getCharacters(bookId)
            .mapNotNull { character ->
                names[character.id]?.map { it.name }?.distinct()?.take(MAX_NAMES_PER_CHARACTER)?.let {
                    "c${character.id}: ${it.joinToString(" | ")}"
                }
            }
            .takeLast(MAX_KNOWN)
            .joinToString("\n")
            .ifEmpty { "(ninguno todavía)" }
    }

    private fun blocks(pages: List<PageText>): List<String> {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        for (page in pages) {
            val text = "[p. ${page.page}]\n" + page.paragraphs.joinToString("\n")
            if (current.isNotEmpty() && current.length + text.length > BLOCK_CHARS) {
                blocks += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(text)
        }
        if (current.isNotEmpty()) blocks += current.toString()
        return blocks
    }

    private fun render(vararg values: Pair<String, Any>): String {
        val byKey = values.toMap()
        return PLACEHOLDER.replace(template) { match -> byKey[match.groupValues[1]]?.toString() ?: match.value }.trim()
    }

    private companion object {
        // Una sola pasada sobre la plantilla original: si un valor insertado contiene literalmente
        // "{{otraClave}}" (por ejemplo, texto del libro), no se vuelve a sustituir por error.
        val PLACEHOLDER = Regex("\\{\\{(\\w+)\\}\\}")
        const val ATTEMPTS = 2
        const val RETRY_DELAY_MS = 500L
        const val MAX_TOKENS = 8_000L

        /** Respuesta típica de una extracción, para las estimaciones (el máximo es [MAX_TOKENS]). */
        const val ESTIMATED_OUTPUT_TOKENS = 1_500L

        /** ≈20 k tokens de texto por petición (≈4 caracteres por token). */
        const val BLOCK_CHARS = 80_000
        const val MAX_KNOWN = 150
        const val MAX_NAMES_PER_CHARACTER = 8
    }
}

/** Errores tras los que no tiene sentido reintentar el análisis sin que el usuario haga algo. */
internal fun LlmException.isPermanent() = this is LlmException.NoApiKey || this is LlmException.Unauthorized
