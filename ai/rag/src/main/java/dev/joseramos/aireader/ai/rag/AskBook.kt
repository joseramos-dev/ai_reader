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
import dev.joseramos.aireader.core.data.book.SummaryRepository
import dev.joseramos.aireader.core.data.db.ChatRole
import dev.joseramos.aireader.core.data.db.SummaryKind
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

sealed interface AskEvent {
    /** Fragmento de la respuesta según llega. */
    data class Delta(val text: String) : AskEvent

    /** Respuesta final con las citas validadas (ya guardada en el chat). */
    data class Completed(val answer: CitedAnswer) : AskEvent
}

/**
 * Responde una pregunta sobre un libro (RAG):
 * 1. Con historial, reformula la pregunta para que se entienda sola (modelo de resúmenes).
 * 2. Preguntas globales → resúmenes de capítulo; concretas → búsqueda híbrida (top 8).
 * 3. Prompt: instrucciones + contexto del libro (prefijo estable, que aprovecha la caché implícita de
 *    Gemini) + fragmentos numerados.
 * 4. Respuesta en streaming con el modelo del chat (Gemini Flash) y validación de las citas `[p. N]`.
 */
class AskBook @Inject constructor(
    private val llm: LlmClient,
    private val retriever: HybridRetriever,
    private val prompts: Prompts,
    private val books: BookRepository,
    private val content: BookContentRepository,
    private val summaries: SummaryRepository,
    private val chat: ChatRepository,
    private val settings: SettingsRepository
) {
    fun ask(bookId: String, threadId: Long, question: String): Flow<AskEvent> = flow {
        val history = chat.messages(threadId).takeLast(MAX_HISTORY_MESSAGES)
        chat.add(threadId, ChatRole.USER, question)
        val config = settings.settings.first()
        val chapters = content.chapters(bookId)

        val standalone = if (history.isEmpty()) question else rewrite(history, question, config.summaryModel)
        val fragments = fragmentsFor(bookId, standalone, chapters)
        val request = LlmRequest(
            model = config.chatModel,
            system = listOf(
                SystemBlock(prompts.render(R.raw.rag_system_v1)),
                SystemBlock(bookContext(bookId, chapters))
            ),
            messages =
            history.map { LlmMessage(if (it.role == ChatRole.USER) LlmRole.USER else LlmRole.ASSISTANT, it.text) } +
                LlmMessage(
                    LlmRole.USER,
                    prompts.render(
                        R.raw.rag_question_v1,
                        "fragments" to render(fragments),
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
        chat.add(threadId, ChatRole.ASSISTANT, cited.text, cited.pages)
        emit(AskEvent.Completed(cited))
    }

    private suspend fun fragmentsFor(bookId: String, question: String, chapters: List<Chapter>): List<Fragment> {
        if (QueryRouter.route(question) == QueryScope.GLOBAL) {
            val chapterSummaries = summaries.all(bookId).filter { it.kind == SummaryKind.CHAPTER_SHORT }
            val byChapter = chapters.mapNotNull { chapter ->
                chapterSummaries.firstOrNull { it.chapterId == chapter.id }?.let { chapter to it.text }
            }
            if (byChapter.isNotEmpty()) {
                return byChapter.mapIndexed { i, (chapter, text) ->
                    Fragment(i + 1, "Resumen del capítulo: $text", chapter.startPage, chapter.endPage, chapter.title)
                }
            }
        }
        val k = if (QueryRouter.route(question) == QueryScope.GLOBAL) GLOBAL_K else SPECIFIC_K
        return retriever.retrieve(bookId, question, k).mapIndexed { i, chunk ->
            Fragment(
                i + 1,
                chunk.text,
                chunk.startPage,
                chunk.endPage,
                chapters.firstOrNull {
                    it.id == chunk.chapterId
                }?.title
            )
        }
    }

    private fun render(fragments: List<Fragment>): String = fragments.joinToString("\n") { f ->
        val pages = if (f.startPage == f.endPage) "${f.startPage}" else "${f.startPage}-${f.endPage}"
        val chapter = f.chapter?.let { " capitulo=\"${it.replace("\"", "'")}\"" }.orEmpty()
        "<fragmento id=\"${f.number}\" paginas=\"$pages\"$chapter>\n${f.text}\n</fragmento>"
    }

    private suspend fun bookContext(bookId: String, chapters: List<Chapter>): String {
        val book = books.getBook(bookId)
        val list = chapters.joinToString("\n") { "${it.number}. ${it.title} (p. ${it.startPage}–${it.endPage})" }
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
        const val MAX_HISTORY_MESSAGES = 6
        const val SPECIFIC_K = 8
        const val GLOBAL_K = 12
        const val ANSWER_MAX_TOKENS = 16_000L
        const val REWRITE_CONTEXT = 4
        const val REWRITE_CHARS = 600
        const val REWRITE_MAX_TOKENS = 300L
    }
}
