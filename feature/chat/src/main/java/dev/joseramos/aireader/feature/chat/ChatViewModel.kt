package dev.joseramos.aireader.feature.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.joseramos.aireader.ai.embeddings.EmbeddingModelMissingException
import dev.joseramos.aireader.ai.llm.LlmException
import dev.joseramos.aireader.ai.models.ModelCatalog
import dev.joseramos.aireader.ai.models.ModelManager
import dev.joseramos.aireader.ai.models.ModelState
import dev.joseramos.aireader.ai.rag.AskBook
import dev.joseramos.aireader.ai.rag.AskEvent
import dev.joseramos.aireader.ai.rag.HybridRetriever
import dev.joseramos.aireader.ai.rag.ReadingContext
import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.common.IoDispatcher
import dev.joseramos.aireader.core.data.book.Book
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.ChatMessage
import dev.joseramos.aireader.core.data.book.ChatRepository
import dev.joseramos.aireader.core.data.book.ReadingPositionRepository
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.core.data.settings.BudgetLevel
import dev.joseramos.aireader.core.data.settings.DailyUsage
import dev.joseramos.aireader.core.data.settings.SecretStore
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import dev.joseramos.aireader.core.data.settings.UsageRepository
import dev.joseramos.aireader.indexing.IndexScheduler
import dev.joseramos.aireader.indexing.StageCrashGuard
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val book: Book? = null,
    val messages: List<ChatMessage> = emptyList(),
    val streaming: String? = null,
    val answering: Boolean = false,
    val error: String? = null,
    val availability: ChatAvailability = ChatAvailability.Loading,
    val usage: DailyUsage = DailyUsage(),
    /** Anti-spoilers: las respuestas no revelan nada posterior a [spoilerLimit] (la página más avanzada leída). */
    val antiSpoilers: Boolean = true,
    val spoilerLimit: Int = 1
)

private data class Spoilers(val enabled: Boolean, val limit: Int)

private data class ChatInputs(
    val book: Book?,
    val hasKey: Boolean,
    val model: ModelState,
    val usage: DailyUsage,
    val hasChunks: Boolean
)

private data class Transient(val streaming: String? = null, val answering: Boolean = false, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    books: BookRepository,
    private val scheduler: IndexScheduler,
    private val crashGuard: StageCrashGuard,
    private val secrets: SecretStore,
    private val chat: ChatRepository,
    private val askBook: AskBook,
    private val retriever: HybridRetriever,
    private val settings: SettingsRepository,
    private val positions: ReadingPositionRepository,
    private val models: ModelManager,
    private val usage: UsageRepository,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) : ViewModel() {
    val bookId = savedStateHandle.toRoute<ChatRoute>().bookId
    private val threadId = MutableStateFlow<Long?>(null)
    private val transient = MutableStateFlow(Transient())
    private var answerJob: Job? = null

    private val messages = threadId.filterNotNull().flatMapLatest {
        chat.observeMessages(it)
    }.onStart { emit(emptyList()) }

    private val inputs = combine(
        books.observeBook(bookId),
        secrets.hasApiKey,
        models.states,
        usage.today,
        retriever.observeSearchable(bookId)
    ) { book, hasKey, modelStates, today, hasChunks ->
        ChatInputs(book, hasKey, modelStates[ModelCatalog.e5Small.id] ?: ModelState.NotInstalled, today, hasChunks)
    }

    private val spoilers = combine(settings.observeAntiSpoilers(bookId), positions.observeMaxPage(bookId)) { on, max ->
        Spoilers(on, maxOf(1, max))
    }

    val state: StateFlow<ChatUiState> = combine(inputs, messages, transient, spoilers) { input, messages, t, spoilers ->
        val book = input.book
        ChatUiState(
            book = book,
            messages = messages,
            streaming = t.streaming,
            answering = t.answering,
            error = t.error,
            availability = chatAvailability(
                book?.indexStatus,
                book?.indexProgress ?: 0f,
                input.hasKey,
                input.model,
                input.hasChunks
            ),
            usage = input.usage,
            antiSpoilers = spoilers.enabled,
            spoilerLimit = spoilers.limit
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch { threadId.value = chat.currentThread(bookId) }
        // Si la indexación se quedó a medias sin trabajo pendiente (Android lo canceló), se reanuda;
        // si sigue en marcha, no se toca (KEEP). Así el chat no espera a algo que ya no corre.
        viewModelScope.launch {
            val book = books.observeBook(bookId).filterNotNull().first()
            if (book.indexStatus in RESUMABLE) scheduler.enqueue(bookId)
        }
    }

    fun send(question: String) {
        val thread = threadId.value ?: return
        val text = question.trim()
        if (text.isEmpty() || transient.value.answering) return
        answerJob = viewModelScope.launch {
            transient.value = Transient(streaming = "", answering = true)
            try {
                askBook.ask(bookId, thread, text, readingContext()).collect { event ->
                    when (event) {
                        is AskEvent.Delta -> transient.update {
                            it.copy(streaming = (it.streaming ?: "") + event.text)
                        }
                        is AskEvent.Completed -> transient.update { it.copy(streaming = null) }
                    }
                }
                transient.value = Transient()
            } catch (e: LlmException) {
                // La causa lleva el código y el estado HTTP de Gemini (nunca la clave).
                Log.w(TAG, "La IA no ha podido responder en el chat de $bookId", e)
                transient.value = Transient(error = e.message)
            } catch (e: EmbeddingModelMissingException) {
                transient.value = Transient(error = e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // Cualquier otro fallo (base de datos, búsqueda, un Error nativo…) se muestra en vez de cerrar la app.
                Log.e(TAG, "Fallo respondiendo en el chat de $bookId", e)
                transient.value = Transient(error = GENERIC_ERROR)
            }
        }
    }

    /** Activa o desactiva anti-spoilers para este libro (se recuerda). */
    fun setAntiSpoilers(enabled: Boolean) {
        viewModelScope.launch { settings.setAntiSpoilers(bookId, enabled) }
    }

    /** La página actual y, con anti-spoilers, el límite: la más avanzada entre la leída y la actual. */
    private suspend fun readingContext(): ReadingContext {
        val current = positions.get(bookId)?.page ?: 1
        val ui = state.value
        return ReadingContext(current, if (ui.antiSpoilers) maxOf(ui.spoilerLimit, current) else null)
    }

    fun stop() {
        answerJob?.cancel()
        transient.value = Transient()
    }

    fun dismissError() = transient.update { it.copy(error = null) }

    fun dismissBudgetAlert(level: BudgetLevel) {
        viewModelScope.launch { usage.dismissAlert(level) }
    }

    fun newConversation() {
        stop()
        viewModelScope.launch { threadId.value = chat.newThread(bookId) }
    }

    fun downloadModel() = models.download(ModelCatalog.e5Small.id)

    /** «Reintentar»: vuelve a preparar el libro para el chat, olvidando los fallos anteriores. */
    fun retryIndexing() {
        viewModelScope.launch {
            withContext(io) { crashGuard.reset(bookId) }
            scheduler.enqueue(bookId, replace = true)
        }
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch { secrets.setApiKey(key) }
    }

    override fun onCleared() {
        // El modelo de embeddings ocupa memoria: se libera al salir del chat.
        appScope.launch { retriever.release() }
    }

    private companion object {
        val RESUMABLE = setOf(IndexStatus.PENDING, IndexStatus.EXTRACTING_TEXT, IndexStatus.EMBEDDING)
        const val TAG = "ChatViewModel"
        const val GENERIC_ERROR = "No se pudo responder. Inténtalo de nuevo."
    }
}
