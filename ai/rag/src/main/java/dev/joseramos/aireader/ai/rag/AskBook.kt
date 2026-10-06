package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.ai.llm.LlmClient
import dev.joseramos.aireader.ai.llm.LlmEvent
import dev.joseramos.aireader.ai.llm.LlmMessage
import dev.joseramos.aireader.ai.llm.LlmRequest
import dev.joseramos.aireader.ai.llm.LlmRole
import dev.joseramos.aireader.ai.llm.Prompts
import dev.joseramos.aireader.ai.llm.SystemBlock
import dev.joseramos.aireader.ai.llm.Thinking
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.ChatMessage
import dev.joseramos.aireader.core.data.book.ChatRepository
import dev.joseramos.aireader.core.data.book.ChatSource
import dev.joseramos.aireader.core.data.book.SourceKind
import dev.joseramos.aireader.core.data.book.SummaryRepository
import dev.joseramos.aireader.core.data.db.ChatRole
import dev.joseramos.aireader.core.data.db.ChunkEntity
import dev.joseramos.aireader.core.data.db.SummaryKind
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

sealed interface AskEvent {
    /** Fragmento de la respuesta según llega. */
    data class Delta(val text: String) : AskEvent

    /** Respuesta final con las citas validadas (ya guardada en el chat). */
    data class Completed(val answer: CitedAnswer) : AskEvent
}

/**
 * Por dónde va el lector: [currentPage] para entender «este capítulo» y, con anti-spoilers,
 * [spoilerLimit], la página más avanzada leída (no se usa ni se revela nada posterior). `null` = sin límite.
 */
data class ReadingContext(val currentPage: Int = 1, val spoilerLimit: Int? = null)

/**
 * Responde una pregunta sobre un libro (RAG):
 * 1. Sitúa la pregunta en el libro si habla de una parte de él («el capítulo 2», «al final del
 *    libro», «después del crimen»: [BookLocator]) y, con historial, la reformula para que se entienda
 *    sola (modelo de resúmenes).
 * 2. Resumen de una parte del libro («resume este capítulo», «¿qué pasa al final?») → resúmenes
 *    guardados o fragmentos repartidos por ella; otras preguntas sobre una parte → búsqueda híbrida
 *    solo en sus páginas; globales → resúmenes de capítulo; concretas → búsqueda híbrida (top 8).
 * 3. Prompt: instrucciones + contexto del libro (prefijo estable, que aprovecha la caché implícita de
 *    Gemini) + reglas anti-spoilers si toca + fragmentos numerados y la posición del lector.
 * 4. Respuesta en streaming con el modelo del chat (Gemini Flash) y validación de las citas `[p. N]`.
 *
 * Con anti-spoilers, todo lo que se envía al modelo (fragmentos, resúmenes y lista de capítulos)
 * llega como mucho hasta [ReadingContext.spoilerLimit].
 */
class AskBook @Inject constructor(
    private val llm: LlmClient,
    private val retriever: HybridRetriever,
    private val locator: BookLocator,
    private val prompts: Prompts,
    private val books: BookRepository,
    private val content: BookContentRepository,
    private val summaries: SummaryRepository,
    private val chat: ChatRepository,
    private val settings: SettingsRepository
) {
    /**
     * Lo que se busca para una pregunta: [question] (la reformulada, si hizo falta), [query] (sin la
     * expresión que la sitúa en el libro) y la parte del libro a la que se refiere, si es el caso.
     */
    private data class Search(val question: String, val query: String, val located: LocatedQuestion?)

    fun ask(
        bookId: String,
        threadId: Long,
        question: String,
        reading: ReadingContext = ReadingContext()
    ): Flow<AskEvent> = flow {
        val history = chat.messages(threadId).takeLast(MAX_HISTORY_MESSAGES)
        chat.add(threadId, ChatRole.USER, question)
        try {
            askAndRespond(bookId, threadId, question, history, reading)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Sin esto, la pregunta queda sin respuesta en el historial y se reenvía así a Gemini
            // en la siguiente pregunta, ensuciando el contexto de la conversación.
            chat.add(threadId, ChatRole.ASSISTANT, FAILED_ANSWER)
            throw e
        }
    }

    private suspend fun FlowCollector<AskEvent>.askAndRespond(
        bookId: String,
        threadId: Long,
        question: String,
        history: List<ChatMessage>,
        reading: ReadingContext
    ) {
        val config = settings.settings.first()
        val chapters = content.chapters(bookId)
        val limit = reading.spoilerLimit
        // Los títulos de los capítulos que aún no se han leído también pueden destripar.
        val visibleChapters = if (limit == null) chapters else chapters.filter { it.startPage <= limit }
        val book = books.getBook(bookId)
        val pageCount = book?.pageCount ?: chapters.maxOfOrNull { it.endPage } ?: 0
        val context = LocationContext(chapters, pageCount, book?.isLiterature == true, reading)
        val search = search(bookId, question, history, context, config.summaryModel)
        val fragments = fragmentsFor(bookId, search, context)
        val system = buildList {
            add(SystemBlock(prompts.render(R.raw.rag_system_v1)))
            add(SystemBlock(bookContext(bookId, visibleChapters)))
            // Con el libro entero leído no hay nada que ocultar: las reglas anti-spoilers solo harían
            // que el modelo se negara a contar «qué pasa al final».
            if (limit != null && limit < pageCount) {
                add(SystemBlock(prompts.render(R.raw.rag_spoilers_v1, "page" to limit, "pages" to pageCount)))
            }
        }
        val request = LlmRequest(
            model = config.chatModel,
            system = system,
            // Las respuestas anteriores van recortadas: para seguir la conversación basta su principio,
            // y se reenvían en cada pregunta.
            messages =
            history.map { message ->
                if (message.role == ChatRole.USER) {
                    LlmMessage(LlmRole.USER, message.text)
                } else {
                    LlmMessage(LlmRole.ASSISTANT, message.text.shortened(HISTORY_ANSWER_CHARS))
                }
            } +
                LlmMessage(
                    LlmRole.USER,
                    prompts.render(
                        R.raw.rag_question_v1,
                        "fragments" to render(fragments),
                        "position" to position(reading, visibleChapters, search.located),
                        "question" to question
                    )
                ),
            maxTokens = ANSWER_MAX_TOKENS
        )

        val answer = StringBuilder()
        llm.streamChat(request).collect { event ->
            if (event is LlmEvent.Text) {
                answer.append(event.delta)
                emit(AskEvent.Delta(event.delta))
            }
        }
        val cited = CitationParser.validate(answer.toString(), fragments)
        chat.add(threadId, ChatRole.ASSISTANT, cited.text, cited.pages, fragments.map { it.toSource() })
        emit(AskEvent.Completed(cited))
    }

    /**
     * Prepara la búsqueda. La ubicación se busca en la pregunta original: «el capítulo anterior» se
     * resuelve con la página del lector, y al reformularla podría perderse la referencia. Reformular
     * cuesta una llamada: solo se hace si la pregunta depende de la conversación y no pide el resumen
     * de una parte del libro (que sale de esa parte, no del texto de la pregunta).
     */
    private suspend fun search(
        bookId: String,
        question: String,
        history: List<ChatMessage>,
        context: LocationContext,
        model: String
    ): Search {
        val original = locator.locate(bookId, question, context)
        val summaryOfPart = original != null && QueryRouter.isSummary(question)
        if (history.isEmpty() || !QueryRouter.needsContext(question) || summaryOfPart) {
            return Search(question, original?.query ?: question, original)
        }
        val standalone = rewrite(history, question, model)
        // Situada por la original, se busca con la reformulada entera: la expresión era de la otra.
        if (original != null) return Search(standalone, standalone, original)
        // La reformulada solo se sitúa si la original no hablaba de ninguna parte del libro («¿y después
        // de eso?» lo dice con la conversación); si hablaba y se descartó («al principio de su mano»),
        // reformularla no debe resucitarlo.
        val located = if (LocationRules.detect(question, context.literature) == null) {
            locator.locate(bookId, standalone, context)
        } else {
            null
        }
        return Search(standalone, located?.query ?: standalone, located)
    }

    private suspend fun fragmentsFor(bookId: String, search: Search, context: LocationContext): List<Fragment> {
        search.located?.let { return locatedFragments(bookId, search, it, context) }
        val question = search.question
        val limit = context.reading.spoilerLimit
        if (QueryRouter.route(question) == QueryScope.GLOBAL) {
            val saved = shortSummaries(bookId)
            val byChapter = context.chapters.filter { limit == null || it.endPage <= limit }.mapNotNull { chapter ->
                saved[chapter.id]?.let { chapter to it }
            }
            if (byChapter.isNotEmpty()) return byChapter.toSummaryFragments()
        }
        val k = if (QueryRouter.route(question) == QueryScope.GLOBAL) GLOBAL_K else SPECIFIC_K
        return retriever.retrieve(bookId, question, k, limit?.let { listOf(1..it) }).toFragments(context.chapters)
    }

    /**
     * Pregunta sobre una parte del libro. Si pide un resumen: el del capítulo, si es uno entero, o los
     * de los capítulos de esa parte (o fragmentos repartidos por ella); si no, la búsqueda híbrida solo
     * en sus páginas. Si el lector aún no ha llegado ahí (anti-spoilers), nada.
     */
    private suspend fun locatedFragments(
        bookId: String,
        search: Search,
        located: LocatedQuestion,
        context: LocationContext
    ): List<Fragment> {
        val summary = QueryRouter.isSummary(search.question) || QueryRouter.route(search.question) == QueryScope.GLOBAL
        return when {
            located.pages.isEmpty() -> emptyList()
            !summary -> retriever.retrieve(bookId, search.query, SPECIFIC_K, located.pages)
                .toFragments(context.chapters)
            else -> wholeChapter(located.location, context)
                ?.let { chapterFragments(bookId, it, context.reading.spoilerLimit) }
                ?: rangeFragments(bookId, located.pages, context.chapters)
        }
    }

    /** El capítulo, si la pregunta es sobre uno solo y entero («resume el capítulo 3»). */
    private fun wholeChapter(location: BookLocation, context: LocationContext): Chapter? {
        val chapters = location as? BookLocation.Chapters ?: return null
        if (chapters.refs.size != 1 || chapters.stretch != Stretch.WHOLE) return null
        return ChapterResolver.resolve(chapters.refs.single(), context.chapters, context.reading.currentPage)
    }

    /**
     * Para resumir una parte del libro: los resúmenes breves de los capítulos que caen enteros en
     * ella, si están todos; si no, fragmentos repartidos por sus páginas.
     */
    private suspend fun rangeFragments(bookId: String, pages: List<IntRange>, chapters: List<Chapter>): List<Fragment> {
        val inside = chapters.filter { chapter ->
            pages.any { chapter.startPage >= it.first && chapter.endPage <= it.last }
        }
        val saved = shortSummaries(bookId)
        val byChapter = inside.mapNotNull { chapter -> saved[chapter.id]?.let { chapter to it } }
        return if (byChapter.isNotEmpty() && byChapter.size == inside.size) {
            byChapter.toSummaryFragments()
        } else {
            retriever.spread(bookId, pages, CHAPTER_K).toFragments(chapters)
        }
    }

    /** Resúmenes breves guardados, por id de capítulo. */
    private suspend fun shortSummaries(bookId: String): Map<Long?, String> =
        summaries.all(bookId).filter { it.kind == SummaryKind.CHAPTER_SHORT }.associate { it.chapterId to it.text }

    private fun List<Pair<Chapter, String>>.toSummaryFragments() = mapIndexed { i, (chapter, text) ->
        Fragment(i + 1, text, chapter.startPage, chapter.endPage, chapter.title, summary = true)
    }

    /**
     * Para resumir un capítulo: su resumen breve si ya existe (y se ha leído entero, con
     * anti-spoilers); si no, fragmentos repartidos por todo él, hasta donde se ha leído. Un capítulo
     * que aún no se ha empezado no aporta nada: la respuesta dirá que no lo ha encontrado.
     */
    private suspend fun chapterFragments(bookId: String, chapter: Chapter, limit: Int?): List<Fragment> {
        if (limit != null && chapter.startPage > limit) return emptyList()
        if (limit == null || chapter.endPage <= limit) {
            summaries.get(bookId, chapter.id, SummaryKind.CHAPTER_SHORT)?.let {
                return listOf(
                    Fragment(1, it.text, chapter.startPage, chapter.endPage, chapter.title, summary = true)
                )
            }
        }
        val end = if (limit == null) chapter.endPage else minOf(chapter.endPage, limit)
        return retriever.spread(bookId, chapter.startPage, end, CHAPTER_K).toFragments(listOf(chapter))
    }

    /** Fragmentos para el modelo: los trozos seguidos del libro se unen en uno ([ChunkMerger]). */
    private fun List<ChunkEntity>.toFragments(chapters: List<Chapter>) =
        ChunkMerger.merge(this).mapIndexed { i, chunk ->
            Fragment(
                i + 1,
                chunk.text,
                chunk.startPage,
                chunk.endPage,
                chapters.firstOrNull { it.id == chunk.chapterId || chunk.startPage in it }?.title
            )
        }

    private fun String.shortened(max: Int) = if (length <= max) this else take(max).trimEnd() + "…"

    /**
     * Dónde está el lector, con el capítulo actual y el anterior por su título (el número interno del
     * índice no sirve: en los libros con partes, el capítulo 38 del índice es «Parte 6. Capítulo 6»),
     * y a qué páginas se refiere la pregunta, si habla de una parte del libro.
     */
    private fun position(reading: ReadingContext, chapters: List<Chapter>, located: LocatedQuestion?): String {
        val page = reading.currentPage
        val current = ChapterResolver.resolve(ChapterRef.Current, chapters, page)
        val previous = ChapterResolver.resolve(ChapterRef.Previous, chapters, page)
        return buildString {
            if (current == null) {
                append("El lector está en la página $page.")
            } else {
                append("El lector está en la página $page, en el capítulo ${describe(current, chapters)}.")
                if (previous != null) append(" El capítulo anterior es ${describe(previous, chapters)}.")
            }
            if (located != null) append(" ").append(scope(located))
        }
    }

    private fun scope(located: LocatedQuestion): String = if (located.pages.isEmpty()) {
        "La pregunta se refiere a una parte del libro que el lector aún no ha leído."
    } else {
        val pages = located.pages.joinToString(", ") { range ->
            if (range.first == range.last) "${range.first}" else "${range.first}–${range.last}"
        }
        "La pregunta se refiere a las páginas $pages."
    }

    private fun describe(chapter: Chapter, chapters: List<Chapter>): String {
        val part = ChapterResolver.partOf(chapters, chapter)?.takeUnless { chapter.title.startsWith(it) }
        val pages = " (p. ${chapter.startPage}–${chapter.endPage})"
        return "«${chapter.title}»" + (part?.let { " de «$it»" } ?: "") + pages
    }

    private fun render(fragments: List<Fragment>): String = fragments.joinToString("\n") { f ->
        val pages = if (f.startPage == f.endPage) "${f.startPage}" else "${f.startPage}-${f.endPage}"
        val chapter = f.chapter?.let { " capitulo=\"${it.replace("\"", "'")}\"" }.orEmpty()
        val text = if (f.summary) "Resumen del capítulo: ${f.text}" else f.text
        "<fragmento id=\"${f.number}\" paginas=\"$pages\"$chapter>\n$text\n</fragmento>"
    }

    /** Para la sección «Fuentes» de la respuesta: el fragmento tal cual se envió. */
    private fun Fragment.toSource() = ChatSource(
        number = number,
        kind = if (summary) SourceKind.SUMMARY else SourceKind.BOOK,
        text = text,
        startPage = startPage,
        endPage = endPage,
        chapter = chapter
    )

    private suspend fun bookContext(bookId: String, chapters: List<Chapter>): String {
        val book = books.getBook(bookId)
        // Sin el número interno del índice: el modelo lo confundiría con el del título («Capítulo 6»).
        val list = chapters.joinToString("\n") { "- ${it.title} (p. ${it.startPage}–${it.endPage})" }
        return prompts.render(
            R.raw.rag_book_context_v1,
            "title" to (book?.title ?: ""),
            "author" to (book?.author ?: "desconocido"),
            "pages" to (book?.pageCount ?: 0),
            "chapters" to list.ifEmpty { "(sin capítulos detectados)" }
        )
    }

    /** Si falla la reformulación, se usa la pregunta tal cual: no merece la pena fallar por esto. */
    private suspend fun rewrite(history: List<ChatMessage>, question: String, model: String): String = runCatching {
        val transcript = history.takeLast(REWRITE_CONTEXT).joinToString("\n") {
            (if (it.role == ChatRole.USER) "Usuario: " else "Asistente: ") + it.text.take(REWRITE_CHARS)
        }
        llm.complete(
            LlmRequest(
                model = model,
                messages = listOf(
                    LlmMessage(
                        LlmRole.USER,
                        prompts.render(
                            R.raw.rag_rewrite_v1,
                            "history" to transcript,
                            "question" to question
                        )
                    )
                ),
                maxTokens = REWRITE_MAX_TOKENS,
                thinking = Thinking.MINIMAL
            )
        ).text.trim().ifEmpty { question }
    }.getOrDefault(question)

    private companion object {
        const val FAILED_ANSWER = "No se ha podido responder a esta pregunta."
        const val MAX_HISTORY_MESSAGES = 6
        const val SPECIFIC_K = 8
        const val GLOBAL_K = 12
        const val CHAPTER_K = 16
        const val ANSWER_MAX_TOKENS = 16_000L
        const val REWRITE_CONTEXT = 4
        const val REWRITE_CHARS = 600
        const val REWRITE_MAX_TOKENS = 300L
        const val HISTORY_ANSWER_CHARS = 1_200
    }
}
