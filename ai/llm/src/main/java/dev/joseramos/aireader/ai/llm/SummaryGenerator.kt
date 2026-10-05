package dev.joseramos.aireader.ai.llm

import android.util.Log
import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.Summary
import dev.joseramos.aireader.core.data.book.SummaryRepository
import dev.joseramos.aireader.core.data.db.DocumentType
import dev.joseramos.aireader.core.data.db.SummaryKind
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
 * Genera resúmenes con el modelo de resúmenes (Gemini Flash-Lite por defecto):
 * - Capítulo que cabe (≈30 k tokens): una sola llamada con el texto completo, sin truncar.
 * - Capítulo largo: se resume por bloques de ≈8 k tokens y luego se combinan (map-reduce).
 * - Libro: a partir de los resúmenes breves de cada capítulo, generando los que falten.
 * - Repaso «Hasta ahora…»: resúmenes de los capítulos anteriores más el capítulo actual hasta la
 *   página dada, nunca más allá (sin spoilers).
 * - Hechos de un capítulo: los importantes, uno por línea (del texto o, si es largo, de los resúmenes
 *   de sus bloques). Sirven para situar en el libro preguntas como «¿qué hace después del crimen?».
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

    /** [kind]: resumen breve, detallado o hechos del capítulo. */
    fun summarizeChapter(bookId: String, chapter: Chapter, kind: SummaryKind = SummaryKind.CHAPTER_SHORT) {
        require(kind in CHAPTER_KINDS) { "No es un resumen de capítulo: $kind" }
        launchJob(SummaryKey(bookId, chapter.id, kind)) { chapterSummary(bookId, chapter, kind) }
    }

    /**
     * Hechos importantes de un capítulo: los guardados o, si no hay, unos nuevos. Para el análisis en
     * segundo plano, que los genera capítulo a capítulo: los errores se propagan para que reintente,
     * salvo que el modelo se niegue, que se apunta para no volver a pedirlos en cada análisis.
     */
    suspend fun chapterEvents(bookId: String, chapter: Chapter): String {
        summaries.get(bookId, chapter.id, SummaryKind.CHAPTER_EVENTS)?.let { return it.text }
        val key = SummaryKey(bookId, chapter.id, SummaryKind.CHAPTER_EVENTS)
        return try {
            runForKey(key) { chapterSummary(bookId, chapter, SummaryKind.CHAPTER_EVENTS) }
        } catch (e: LlmException.Refused) {
            Log.w(TAG, "Hechos del capítulo ${chapter.number} rechazados", e)
            summaries.save(bookId, chapter.id, SummaryKind.CHAPTER_EVENTS, EVENTS_REFUSED, model())
            _jobs.update { it - key }
            EVENTS_REFUSED
        }
    }

    /**
     * Estimación local (sin llamar a la API) de lo que costaría generar los hechos que faltan: cada
     * capítulo, como un resumen breve (una llamada o, si es largo, bloques y una más). Cero si ya
     * están todos (los capítulos sin texto no cuentan: no llaman a la API).
     */
    suspend fun estimateEvents(bookId: String): TokenEstimate {
        val done = summaries.all(bookId).filter { it.kind == SummaryKind.CHAPTER_EVENTS }.map { it.chapterId }.toSet()
        var estimate = TokenEstimate()
        for (chapter in content.chapters(bookId).filter { it.id !in done }) {
            val text = content.text(bookId, chapter.startPage, chapter.endPage)
            if (text.isNotBlank()) estimate += chapterCost(estimateTokens(text).toLong())
        }
        return estimate
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
     * Repaso de lo leído hasta [untilPage] (incluida). Si ya hay un repaso de una página anterior, se
     * actualiza con lo leído desde entonces (ver [recapPlan]); si no, se hace con los resúmenes breves
     * de los capítulos anteriores (generando los que falten) y el texto del capítulo actual.
     */
    fun recap(bookId: String, untilPage: Int) {
        launchJob(SummaryKey(bookId, null, SummaryKind.RECAP)) {
            val title = bookTitle(bookId)
            val plan = recapPlan(bookId, untilPage)
            val chaptersText = plan.chapters.map { chapter ->
                "Capítulo ${chapter.number}. ${chapter.title}\n${shortSummary(bookId, chapter)}"
            }.joinToString("\n\n")
            val model = model()
            val currentTitle = plan.current?.title ?: title
            val currentText = content.text(bookId, plan.currentFrom, untilPage).let { text ->
                if (estimateTokens(text) <= SINGLE_CALL_TOKENS) {
                    text
                } else {
                    // Demasiado largo para una sola llamada: se condensa por bloques, en orden.
                    mapBlocks(bookId, model, title, currentTitle, text).joinToString("\n\n")
                }
            }
            val prompt = plan.previousRecap?.let { previous ->
                prompts.render(
                    R.raw.summary_recap_update_v1,
                    "book" to title,
                    "page" to untilPage,
                    "since" to (previous.untilPage ?: 0),
                    "from" to plan.currentFrom,
                    "chapter" to currentTitle,
                    "recap" to previous.text,
                    "chapters" to chaptersText.ifEmpty { "(ninguno)" },
                    "current" to currentText
                )
            } ?: prompts.render(
                R.raw.summary_recap_v1,
                "book" to title,
                "page" to untilPage,
                "chapter" to currentTitle,
                "previous" to chaptersText.ifEmpty { "(ninguno: estoy en el primer capítulo)" },
                "current" to currentText
            )
            val summary = ask(bookId, model, prompt)
            summaries.save(bookId, null, SummaryKind.RECAP, summary, model, untilPage)
        }
    }

    /**
     * Qué entra en un repaso hasta [untilPage]. Con un repaso guardado de una página anterior se parte
     * de él y solo se añade lo leído desde entonces: los capítulos terminados después y el capítulo
     * actual desde esa página. Así no se reenvían en cada repaso los resúmenes de todo el libro. Al
     * pedirlo otra vez en la misma página (o más atrás) se rehace entero.
     */
    private suspend fun recapPlan(bookId: String, untilPage: Int): RecapPlan {
        val chapters = content.chapters(bookId)
        val current = chapters.lastOrNull { untilPage >= it.startPage }
        val currentStart = current?.startPage ?: 1
        val finished = chapters.filter { it.endPage < currentStart }
        val previous = summaries.get(bookId, null, SummaryKind.RECAP)
            ?.takeIf { (it.untilPage ?: 0) in 1 until untilPage }
            ?: return RecapPlan(null, finished, current, currentStart)
        val since = previous.untilPage ?: 0
        return RecapPlan(previous, finished.filter { it.endPage > since }, current, maxOf(currentStart, since + 1))
    }

    /**
     * [previousRecap]: el repaso del que se parte (o `null` si se hace entero); [chapters]: los
     * capítulos terminados que entran por su resumen breve; el capítulo actual entra desde [currentFrom].
     */
    private data class RecapPlan(
        val previousRecap: Summary?,
        val chapters: List<Chapter>,
        val current: Chapter?,
        val currentFrom: Int
    )

    /**
     * Estimación local (sin llamar a la API) de lo que gastaría [recap]: los resúmenes breves que
     * faltan de los capítulos que entran, el capítulo actual (por bloques si es largo) y la llamada final.
     */
    suspend fun estimateRecap(bookId: String, untilPage: Int): TokenEstimate {
        val plan = recapPlan(bookId, untilPage)
        var estimate = TokenEstimate()
        var previousTokens = plan.previousRecap?.let { estimateTokens(it.text).toLong() } ?: 0L
        for (chapter in plan.chapters) {
            val saved = summaries.get(bookId, chapter.id, SummaryKind.CHAPTER_SHORT)?.text
            if (saved != null) {
                previousTokens += estimateTokens(saved)
            } else {
                // Un capítulo sin texto no llama a la API (se resume con un aviso fijo).
                val text = content.text(bookId, chapter.startPage, chapter.endPage)
                if (text.isNotBlank()) {
                    estimate += chapterCost(estimateTokens(text).toLong())
                    previousTokens += ESTIMATED_SUMMARY_TOKENS
                }
            }
        }
        val currentTokens = estimateTokens(content.text(bookId, plan.currentFrom, untilPage)).toLong()
        val currentInPrompt = if (currentTokens <= SINGLE_CALL_TOKENS) {
            currentTokens
        } else {
            val blocks = blocksFor(currentTokens)
            estimate += mapCost(currentTokens, blocks)
            blocks * ESTIMATED_SUMMARY_TOKENS
        }
        return estimate + TokenEstimate(
            TokenEstimate.PROMPT_OVERHEAD_TOKENS + previousTokens + currentInPrompt,
            ESTIMATED_SUMMARY_TOKENS
        )
    }

    /** Resumen breve de un capítulo de [tokens]: una llamada o, si es largo, bloques y combinación. */
    private fun chapterCost(tokens: Long): TokenEstimate {
        if (tokens <= SINGLE_CALL_TOKENS) {
            return TokenEstimate(tokens + TokenEstimate.PROMPT_OVERHEAD_TOKENS, ESTIMATED_SUMMARY_TOKENS)
        }
        val blocks = blocksFor(tokens)
        return mapCost(tokens, blocks) +
            TokenEstimate(
                blocks * ESTIMATED_SUMMARY_TOKENS + TokenEstimate.PROMPT_OVERHEAD_TOKENS,
                ESTIMATED_SUMMARY_TOKENS
            )
    }

    private fun mapCost(tokens: Long, blocks: Long) =
        TokenEstimate(tokens + blocks * TokenEstimate.PROMPT_OVERHEAD_TOKENS, blocks * ESTIMATED_SUMMARY_TOKENS)

    private fun blocksFor(tokens: Long): Long = (tokens + BLOCK_TOKENS - 1) / BLOCK_TOKENS

    /** Resumen breve de un capítulo: el guardado o uno nuevo. */
    private suspend fun shortSummary(bookId: String, chapter: Chapter): String =
        summaries.get(bookId, chapter.id, SummaryKind.CHAPTER_SHORT)?.text
            ?: runForKey(SummaryKey(bookId, chapter.id, SummaryKind.CHAPTER_SHORT)) {
                chapterSummary(bookId, chapter, SummaryKind.CHAPTER_SHORT)
            }

    private suspend fun chapterSummary(bookId: String, chapter: Chapter, kind: SummaryKind): String {
        val title = bookTitle(bookId)
        val text = content.text(bookId, chapter.startPage, chapter.endPage)
        val model = model()
        val detailed = kind == SummaryKind.CHAPTER_LONG
        val fits = estimateTokens(text) <= SINGLE_CALL_TOKENS
        val summary = when {
            text.isBlank() -> EMPTY_CHAPTER
            kind == SummaryKind.CHAPTER_EVENTS -> {
                // Los hechos de un capítulo largo salen de los resúmenes de sus bloques.
                val source = if (fits) {
                    text
                } else {
                    mapBlocks(bookId, model, title, chapter.title, text).joinToString("\n\n")
                }
                ask(
                    bookId,
                    model,
                    prompts.render(
                        R.raw.summary_chapter_events_v1,
                        "book" to title,
                        "chapter" to chapter.title,
                        "text" to source
                    )
                )
            }
            fits -> ask(
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
            // Resumir no necesita razonar: el razonamiento se cobra como salida en cada llamada.
            thinking = Thinking.MINIMAL
        )
    ).text.trim()

    private fun launchJob(key: SummaryKey, block: suspend () -> Unit) {
        if (_jobs.value[key] == SummaryJob.Running) return
        scope.launch { runCatching { runForKey(key) { block() } } }
    }

    /**
     * Ejecuta [block] marcando [key] como en curso y, si falla, guarda el error para la UI. Antes
     * solo se capturaba [LlmException]: cualquier otro fallo (BD, IO…) dejaba la clave "Running"
     * para siempre y ese resumen no se podía volver a generar hasta reiniciar la app.
     */
    private suspend fun <T> runForKey(key: SummaryKey, block: suspend () -> T): T {
        _jobs.update { it + (key to SummaryJob.Running) }
        val result = runCatching { block() }
        when (val error = result.exceptionOrNull()) {
            null -> _jobs.update { it - key }
            // Cancelado, no es un fallo: no se queda "Running" para siempre, pero tampoco es un error.
            is CancellationException -> _jobs.update { it - key }
            is LlmException -> {
                val needsApiKey = error is LlmException.NoApiKey || error is LlmException.Unauthorized
                val failed = SummaryJob.Failed(error.message.orEmpty(), needsApiKey)
                _jobs.update { it + (key to failed) }
            }
            else -> {
                Log.e(TAG, "Fallo generando el resumen $key", error)
                _jobs.update { it + (key to SummaryJob.Failed(GENERIC_ERROR, false)) }
            }
        }
        return result.getOrThrow()
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
        private const val TAG = "SummaryGenerator"
        private const val CHARS_PER_TOKEN = 4
        private const val SINGLE_CALL_TOKENS = 30_000
        private const val BLOCK_TOKENS = 8_000
        private const val SUMMARY_MAX_TOKENS = 2_000L

        /** Longitud típica de un resumen, para las estimaciones (el máximo es [SUMMARY_MAX_TOKENS]). */
        private const val ESTIMATED_SUMMARY_TOKENS = 500L
        private const val EMPTY_CHAPTER = "Este capítulo no tiene texto extraíble (puede ser una imagen escaneada)."
        private const val GENERIC_ERROR = "No se pudo generar el resumen. Inténtalo de nuevo."
        private const val EVENTS_REFUSED = "El modelo no ha querido enumerar los hechos de este capítulo."
        private val CHAPTER_KINDS =
            setOf(SummaryKind.CHAPTER_SHORT, SummaryKind.CHAPTER_LONG, SummaryKind.CHAPTER_EVENTS)

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
