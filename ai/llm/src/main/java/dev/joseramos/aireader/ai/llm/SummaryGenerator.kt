package dev.joseramos.aireader.ai.llm

import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.SummaryRepository
import dev.joseramos.aireader.core.data.db.DocumentType
import dev.joseramos.aireader.core.data.db.SummaryKind
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Identifica un resumen: libro, capítulo (`null` para el libro) y tipo. */
data class SummaryKey(val bookId: String, val chapterId: Long?, val kind: SummaryKind)

sealed interface SummaryJob {
    data object Running : SummaryJob

    data class Failed(val message: String, val needsApiKey: Boolean) : SummaryJob
}

/**
 * Genera resúmenes con el modelo de resúmenes (Haiku por defecto):
 * - Capítulo que cabe (≈30 k tokens): una sola llamada con el texto completo, sin truncar.
 * - Capítulo largo: se resume por bloques de ≈8 k tokens y luego se combinan (map-reduce).
 * - Libro: a partir de los resúmenes breves de cada capítulo, generando los que falten.
 * - Repaso «Hasta ahora…»: resúmenes de los capítulos anteriores más el capítulo actual hasta la
 *   página dada, nunca más allá (sin spoilers).
 * El estilo depende del tipo de documento (narrativo, tipo abstract, conceptos clave o documento).
 * Cada capítulo es un trabajo independiente: si falla uno, los demás se conservan.
 * Corre en el ámbito de la app para que cerrar la hoja no lo cancele.
 */
@Singleton
class SummaryGenerator @Inject constructor(
    private val llm: LlmClient,
    private val prompts: Prompts,
    private val content: BookContentRepository,
    private val books: BookRepository,
    private val summaries: SummaryRepository,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _jobs = MutableStateFlow<Map<SummaryKey, SummaryJob>>(emptyMap())
    val jobs: StateFlow<Map<SummaryKey, SummaryJob>> = _jobs

    fun summarizeChapter(bookId: String, chapter: Chapter, detailed: Boolean = false) {
        val kind = if (detailed) SummaryKind.CHAPTER_LONG else SummaryKind.CHAPTER_SHORT
        launchJob(SummaryKey(bookId, chapter.id, kind)) { chapterSummary(bookId, chapter, detailed) }
    }

    fun summarizeBook(bookId: String) {
        launchJob(SummaryKey(bookId, null, SummaryKind.BOOK)) {
            val title = bookTitle(bookId)
            val chapters = content.chapters(bookId)
            val parts = chapters.map { chapter ->
                "Capítulo ${chapter.number}. ${chapter.title}\n${shortSummary(bookId, chapter)}"
            }
            val model = model()
            val summary = ask(
                bookId,
                model,
                prompts.render(
                    R.raw.summary_book_v1,
                    "book" to title,
                    "text" to parts.joinToString("\n\n")
                )
            )
            summaries.save(bookId, null, SummaryKind.BOOK, summary, model)
        }
    }

    /**
     * Repaso de lo leído hasta [untilPage] (incluida): los capítulos anteriores, por sus resúmenes
     * breves (generando los que falten), y el texto del capítulo actual hasta esa página.
     */
    fun recap(bookId: String, untilPage: Int) {
        launchJob(SummaryKey(bookId, null, SummaryKind.RECAP)) {
            val title = bookTitle(bookId)
            val chapters = content.chapters(bookId)
            val current = chapters.lastOrNull { untilPage >= it.startPage }
            val previous = chapters.filter { it.endPage < (current?.startPage ?: 1) }
            val previousText = previous.map { chapter ->
                "Capítulo ${chapter.number}. ${chapter.title}\n${shortSummary(bookId, chapter)}"
            }.joinToString("\n\n")
            val model = model()
            val currentTitle = current?.title ?: title
            val currentText = content.text(bookId, current?.startPage ?: 1, untilPage).let { text ->
                if (estimateTokens(text) <= SINGLE_CALL_TOKENS) {
                    text
                } else {
                    // Demasiado largo para una sola llamada: se condensa por bloques, en orden.
                    mapBlocks(bookId, model, title, currentTitle, text).joinToString("\n\n")
                }
            }
            val summary = ask(
                bookId,
                model,
                prompts.render(
                    R.raw.summary_recap_v1,
                    "book" to title,
                    "page" to untilPage,
                    "chapter" to currentTitle,
                    "previous" to previousText.ifEmpty { "(ninguno: estoy en el primer capítulo)" },
                    "current" to currentText
                )
            )
            summaries.save(bookId, null, SummaryKind.RECAP, summary, model, untilPage)
        }
    }

    /** Resumen breve de un capítulo: el guardado o uno nuevo. */
    private suspend fun shortSummary(bookId: String, chapter: Chapter): String =
        summaries.get(bookId, chapter.id, SummaryKind.CHAPTER_SHORT)?.text
            ?: runForKey(SummaryKey(bookId, chapter.id, SummaryKind.CHAPTER_SHORT)) {
                chapterSummary(bookId, chapter, detailed = false)
            }

    private suspend fun chapterSummary(bookId: String, chapter: Chapter, detailed: Boolean): String {
        val title = bookTitle(bookId)
        val text = content.text(bookId, chapter.startPage, chapter.endPage)
        val model = model()
        val kind = if (detailed) SummaryKind.CHAPTER_LONG else SummaryKind.CHAPTER_SHORT
        val summary = when {
            text.isBlank() -> EMPTY_CHAPTER
            estimateTokens(text) <= SINGLE_CALL_TOKENS -> ask(
                bookId,
                model,
                prompts.render(
                    if (detailed) R.raw.summary_chapter_long_v1 else R.raw.summary_chapter_short_v1,
                    "book" to title,
                    "chapter" to chapter.title,
                    "text" to text
                )
            )
            else -> mapReduce(bookId, model, title, chapter.title, text, detailed)
        }
        summaries.save(bookId, chapter.id, kind, summary, model)
        return summary
    }

    private suspend fun mapReduce(
        bookId: String,
        model: String,
        book: String,
        chapter: String,
        text: String,
        detailed: Boolean
    ): String {
        val partials = mapBlocks(bookId, model, book, chapter, text)
        return ask(
            bookId,
            model,
            prompts.render(
                R.raw.summary_reduce_v1,
                "book" to book,
                "chapter" to chapter,
                "length" to if (detailed) "entre 3 y 5 párrafos breves" else "un párrafo de entre 3 y 6 frases",
                "text" to partials.joinToString("\n\n")
            )
        )
    }

    /** Resume [text] por bloques de ≈8 k tokens, en orden (fase «map»). */
    private suspend fun mapBlocks(
        bookId: String,
        model: String,
        book: String,
        chapter: String,
        text: String
    ): List<String> {
        val blocks = splitIntoBlocks(text, BLOCK_TOKENS * CHARS_PER_TOKEN)
        return blocks.mapIndexed { i, block ->
            ask(
                bookId,
                model,
                prompts.render(
                    R.raw.summary_map_v1,
                    "part" to i + 1,
                    "parts" to blocks.size,
                    "book" to book,
                    "chapter" to chapter,
                    "text" to block
                )
            )
        }
    }

    private suspend fun ask(bookId: String, model: String, prompt: String): String = llm.complete(
        LlmRequest(
            model = model,
            system = listOf(SystemBlock(prompts.summarySystem), SystemBlock(style(bookId))),
            messages = listOf(LlmMessage(LlmRole.USER, prompt)),
            maxTokens = SUMMARY_MAX_TOKENS,
            effort = "low"
        )
    ).text.trim()

    private fun launchJob(key: SummaryKey, block: suspend () -> Unit) {
        if (_jobs.value[key] == SummaryJob.Running) return
        scope.launch { runCatching { runForKey(key) { block() } } }
    }

    /** Ejecuta [block] marcando [key] como en curso y, si falla, guarda el error para la UI. */
    private suspend fun <T> runForKey(key: SummaryKey, block: suspend () -> T): T {
        _jobs.update { it + (key to SummaryJob.Running) }
        try {
            return block().also { _jobs.update { jobs -> jobs - key } }
        } catch (e: LlmException) {
            _jobs.update { it + (key to SummaryJob.Failed(e.message.orEmpty(), e is LlmException.NoApiKey)) }
            throw e
        }
    }

    /** Instrucciones de estilo según el tipo de documento. */
    private suspend fun style(bookId: String): String = prompts.render(
        when (books.getBook(bookId)?.documentType) {
            DocumentType.LITERATURE -> R.raw.summary_style_literature_v1
            DocumentType.SCIENTIFIC -> R.raw.summary_style_scientific_v1
            DocumentType.EDUCATIONAL -> R.raw.summary_style_educational_v1
            DocumentType.GENERIC, null -> R.raw.summary_style_generic_v1
        }
    )

    private suspend fun model() = settings.settings.first().summaryModel

    private suspend fun bookTitle(bookId: String) = books.getBook(bookId)?.title.orEmpty()

    companion object {
        private const val CHARS_PER_TOKEN = 4
        private const val SINGLE_CALL_TOKENS = 30_000
        private const val BLOCK_TOKENS = 8_000
        private const val SUMMARY_MAX_TOKENS = 2_000L
        private const val EMPTY_CHAPTER = "Este capítulo no tiene texto extraíble (puede ser una imagen escaneada)."

        /** Estimación grosera (≈4 caracteres por token en español), suficiente para decidir el reparto. */
        fun estimateTokens(text: String) = text.length / CHARS_PER_TOKEN

        /** Bloques de como mucho [maxChars], cortando entre párrafos. */
        fun splitIntoBlocks(text: String, maxChars: Int): List<String> {
            val blocks = mutableListOf<String>()
            val current = StringBuilder()
            for (paragraph in text.split("\n\n")) {
                if (current.isNotEmpty() && current.length + paragraph.length > maxChars) {
                    blocks += current.toString()
                    current.clear()
                }
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(paragraph)
            }
            if (current.isNotEmpty()) blocks += current.toString()
            return blocks
        }
    }
}
