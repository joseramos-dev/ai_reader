package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.ai.llm.KeyPointsGenerator
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
import dev.joseramos.aireader.core.data.book.ChapterKeyPoints
import dev.joseramos.aireader.core.data.book.ChatMessage
import dev.joseramos.aireader.core.data.book.ChatRepository
import dev.joseramos.aireader.core.data.book.ChatSource
import dev.joseramos.aireader.core.data.book.SourceKind
import dev.joseramos.aireader.core.data.db.ChatRole
import dev.joseramos.aireader.core.data.db.ChunkEntity
import dev.joseramos.aireader.core.data.settings.CostConfirmation
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import dev.joseramos.aireader.core.data.settings.UsageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/** Qué está haciendo el chat antes de que llegue la respuesta, para mostrarlo. */
sealed interface AskStage {
    /** Situando la pregunta en el libro y buscando los fragmentos. */
    data object Searching : AskStage

    /** Generando los hechos clave que faltan: [done] de [total] capítulos. */
    data class LoadingKeyPoints(val done: Int, val total: Int) : AskStage

    /** Enviando la pregunta al modelo. */
    data object Sending : AskStage

    /** El modelo la ha recibido y está preparando la respuesta. */
    data object Waiting : AskStage
}

sealed interface AskEvent {
    /** Fase en la que está la respuesta (hasta que empieza a llegar el texto). */
    data class Stage(val stage: AskStage) : AskEvent

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
 *    sola (modelo de análisis).
 * 2. Resumen de una parte del libro («resume este capítulo», «¿qué pasa al final?») y preguntas
 *    globales → hechos clave de sus capítulos, con su página ([KeyPointsGenerator] genera los que
 *    falten); otras preguntas sobre una parte → búsqueda híbrida solo en sus páginas; concretas →
 *    búsqueda híbrida (top 8).
 * 3. Prompt: instrucciones + contexto del libro (prefijo estable, que aprovecha la caché implícita de
 *    Gemini) + reglas anti-spoilers si toca + fragmentos numerados y la posición del lector.
 * 4. Respuesta en streaming con el modelo del chat (Gemini Flash) y validación de las citas `[p. N]`.
 *
 * Con anti-spoilers, todo lo que se envía al modelo (fragmentos, hechos clave y lista de capítulos)
 * llega como mucho hasta [ReadingContext.spoilerLimit].
 */
class AskBook(
    private val llm: LlmClient,
    private val retriever: HybridRetriever,
    private val locator: BookLocator,
    private val prompts: Prompts,
    private val books: BookRepository,
    private val content: BookContentRepository,
    private val keyPoints: KeyPointsGenerator,
    private val chat: ChatRepository,
    private val settings: SettingsRepository,
    private val usage: UsageRepository
) {
    /**
     * Lo que se busca para una pregunta: [question] (la reformulada, si hizo falta), [query] (sin la
     * expresión que la sitúa en el libro) y la parte del libro a la que se refiere, si es el caso.
     */
    private data class Search(val question: String, val query: String, val located: LocatedQuestion?)

    /** Avisos de la respuesta a quien pregunta: la fase ([stage]) y la confirmación de un coste alto. */
    private class Hooks(val stage: suspend (AskStage) -> Unit, val confirmCost: suspend (CostConfirmation) -> Boolean)

    /**
     * [confirmCost]: si generar los hechos clave que faltan no cabe en lo que queda del presupuesto de
     * hoy, se pregunta; sin confirmar, se responde con el texto del libro.
     */
    fun ask(
        bookId: String,
        threadId: Long,
        question: String,
        reading: ReadingContext = ReadingContext(),
        confirmCost: suspend (CostConfirmation) -> Boolean = { false }
    ): Flow<AskEvent> = flow {
        val history = chat.messages(threadId).takeLast(MAX_HISTORY_MESSAGES)
        chat.add(threadId, ChatRole.USER, question)
        try {
            val hooks = Hooks({ emit(AskEvent.Stage(it)) }, confirmCost)
            askAndRespond(bookId, threadId, question, history, reading, hooks)
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
        reading: ReadingContext,
        hooks: Hooks
    ) {
        hooks.stage(AskStage.Searching)
        val config = settings.settings.first()
        val chapters = content.chapters(bookId)
        val limit = reading.spoilerLimit
        // Los títulos de los capítulos que aún no se han leído también pueden destripar.
        val visibleChapters = if (limit == null) chapters else chapters.filter { it.startPage <= limit }
        val book = books.getBook(bookId)
        val pageCount = book?.pageCount ?: chapters.maxOfOrNull { it.endPage } ?: 0
        val context = LocationContext(chapters, pageCount, book?.isLiterature == true, reading)
        val search = search(bookId, question, history, context, config.analysisModel, hooks)
        val fragments = fragmentsFor(bookId, search, context, hooks)
        val system = buildList {
            add(SystemBlock(prompts.render("rag_system_v1")))
            add(SystemBlock(bookContext(bookId, visibleChapters)))
            // Con el libro entero leído no hay nada que ocultar: las reglas anti-spoilers solo harían
            // que el modelo se negara a contar «qué pasa al final».
            if (limit != null && limit < pageCount) {
                add(SystemBlock(prompts.render("rag_spoilers_v1", "page" to limit, "pages" to pageCount)))
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
                        "rag_question_v1",
                        "fragments" to render(fragments, context.literature),
                        "position" to position(reading, visibleChapters, search.located),
                        "question" to question
                    )
                ),
            maxTokens = ANSWER_MAX_TOKENS
        )

        hooks.stage(AskStage.Sending)
        val answer = StringBuilder()
        llm.streamChat(request).collect { event ->
            when (event) {
                LlmEvent.Sent -> hooks.stage(AskStage.Waiting)
                is LlmEvent.Text -> {
                    answer.append(event.delta)
                    emit(AskEvent.Delta(event.delta))
                }
                is LlmEvent.Done -> Unit
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
        model: String,
        hooks: Hooks
    ): Search {
        // Para situar una pregunta respecto a un hecho («después del crimen»), los hechos clave que falten.
        val prepareEvents: suspend (List<Chapter>) -> Unit = { chapters ->
            if (prepareKeyPoints(bookId, chapters, hooks) != null) hooks.stage(AskStage.Searching)
        }
        val original = locator.locate(bookId, question, context, prepareEvents)
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
            locator.locate(bookId, standalone, context, prepareEvents)
        } else {
            null
        }
        return Search(standalone, located?.query ?: standalone, located)
    }

    private suspend fun fragmentsFor(
        bookId: String,
        search: Search,
        context: LocationContext,
        hooks: Hooks
    ): List<Fragment> {
        search.located?.let { return locatedFragments(bookId, search, it, context, hooks) }
        val question = search.question
        val limit = context.reading.spoilerLimit
        if (QueryRouter.route(question) == QueryScope.GLOBAL) {
            // Sobre el libro en conjunto: los hechos clave de todo lo leído.
            val read = context.chapters.filter { limit == null || it.startPage <= limit }
            keyPointFragments(bookId, read, null, limit, hooks)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val k = if (QueryRouter.route(question) == QueryScope.GLOBAL) GLOBAL_K else SPECIFIC_K
        return retriever.retrieve(bookId, question, k, limit?.let { listOf(1..it) }).toFragments(context.chapters)
    }

    /**
     * Pregunta sobre una parte del libro. Si pide un resumen: los hechos clave de esa parte (de todo el
     * capítulo, si es uno entero; si no, solo los que ocurren en sus páginas); si no, la búsqueda híbrida
     * solo en sus páginas. Si el lector aún no ha llegado ahí (anti-spoilers), nada.
     */
    private suspend fun locatedFragments(
        bookId: String,
        search: Search,
        located: LocatedQuestion,
        context: LocationContext,
        hooks: Hooks
    ): List<Fragment> {
        val summary = QueryRouter.isSummary(search.question) || QueryRouter.route(search.question) == QueryScope.GLOBAL
        val limit = context.reading.spoilerLimit
        if (located.pages.isEmpty()) return emptyList()
        if (!summary) {
            return retriever.retrieve(bookId, search.query, SPECIFIC_K, located.pages).toFragments(context.chapters)
        }
        val chapter = wholeChapter(located.location, context)
        if (chapter != null) {
            if (limit != null && chapter.startPage > limit) return emptyList()
            return keyPointFragments(bookId, listOf(chapter), null, limit, hooks)
                ?: spreadFragments(bookId, chapter, limit, CHAPTER_K)
        }
        val inside = context.chapters.filter { c ->
            located.pages.any { c.startPage <= it.last && c.endPage >= it.first }
        }
        return keyPointFragments(bookId, inside, located.pages, limit, hooks)?.takeIf { it.isNotEmpty() }
            ?: retriever.spread(bookId, located.pages, CHAPTER_K).toFragments(context.chapters)
    }

    /** El capítulo, si la pregunta es sobre uno solo y entero («resume el capítulo 3»). */
    private fun wholeChapter(location: BookLocation, context: LocationContext): Chapter? {
        val chapters = location as? BookLocation.Chapters ?: return null
        if (chapters.refs.size != 1 || chapters.stretch != Stretch.WHOLE) return null
        return ChapterResolver.resolve(chapters.refs.single(), context.chapters, context.reading.currentPage)
    }

    /**
     * Los hechos clave de [chapters] que ocurren en [pages] (todas, si es `null`) y, con anti-spoilers,
     * hasta [limit]: un fragmento por capítulo, generando antes los que falten (avisando del progreso).
     * Si generarlos no cabe en lo que queda del presupuesto de hoy y no se confirma, `null`: se responde
     * con el texto. Los capítulos que se quedan sin ellos (Gemini se negó o falló) aportan unos
     * fragmentos repartidos de su texto.
     */
    private suspend fun keyPointFragments(
        bookId: String,
        chapters: List<Chapter>,
        pages: List<IntRange>?,
        limit: Int?,
        hooks: Hooks
    ): List<Fragment>? {
        val saved = prepareKeyPoints(bookId, chapters, hooks) ?: return null
        val plan = KeyPointFragments.plan(chapters, saved, limit) { point ->
            pages == null ||
                pages.any { point.page in it }
        }
        val byChapter = plan.fragments.associate { (chapter, fragment) -> chapter.id to listOf(fragment) } +
            plan.withoutKeyPoints.associate { it.id to spreadFragments(bookId, it, limit, FALLBACK_K) }
        return chapters.flatMap { byChapter[it.id].orEmpty() }.mapIndexed { i, f -> f.copy(number = i + 1) }
    }

    /**
     * Los hechos clave de [chapters], generando antes los que falten (avisando del progreso). Si generarlos
     * no cabe en lo que queda del presupuesto de hoy y no se confirma, `null`.
     */
    private suspend fun prepareKeyPoints(
        bookId: String,
        chapters: List<Chapter>,
        hooks: Hooks
    ): Map<Long, ChapterKeyPoints>? {
        val cost = keyPoints.estimate(bookId, chapters).total
        if (cost > 0) {
            val confirmation = usage.today.first().confirmationFor(cost)
            if (confirmation != null && !hooks.confirmCost(confirmation)) return null
        }
        return keyPoints.ensure(bookId, chapters) { done, total ->
            hooks.stage(AskStage.LoadingKeyPoints(done, total))
        }
    }

    /** Fragmentos repartidos por [chapter], hasta donde se ha leído; nada si aún no se ha empezado. */
    private suspend fun spreadFragments(bookId: String, chapter: Chapter, limit: Int?, k: Int): List<Fragment> {
        val end = if (limit == null) chapter.endPage else minOf(chapter.endPage, limit)
        if (end < chapter.startPage) return emptyList()
        return retriever.spread(bookId, chapter.startPage, end, k).toFragments(listOf(chapter))
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

    private fun render(fragments: List<Fragment>, literature: Boolean): String = fragments.joinToString("\n") { f ->
        val pages = if (f.startPage == f.endPage) "${f.startPage}" else "${f.startPage}-${f.endPage}"
        val chapter = f.chapter?.let { " capitulo=\"${it.replace("\"", "'")}\"" }.orEmpty()
        val heading = if (literature) "Hechos clave del capítulo" else "Ideas clave del capítulo"
        val text = if (f.keyPoints) "$heading (con la página de cada uno):\n${f.text}" else f.text
        "<fragmento id=\"${f.number}\" paginas=\"$pages\"$chapter>\n$text\n</fragmento>"
    }

    /** Para la sección «Fuentes» de la respuesta: el fragmento tal cual se envió. */
    private fun Fragment.toSource() = ChatSource(
        number = number,
        kind = if (keyPoints) SourceKind.KEY_POINTS else SourceKind.BOOK,
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
            "rag_book_context_v1",
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
                            "rag_rewrite_v1",
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

        /** Fragmentos de texto de un capítulo que se ha quedado sin hechos clave. */
        const val FALLBACK_K = 2
        const val ANSWER_MAX_TOKENS = 16_000L
        const val REWRITE_CONTEXT = 4
        const val REWRITE_CHARS = 600
        const val REWRITE_MAX_TOKENS = 300L
        const val HISTORY_ANSWER_CHARS = 1_200
    }
}
