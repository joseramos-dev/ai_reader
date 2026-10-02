package dev.joseramos.aireader.feature.chat

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
import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.data.book.Book
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.ChatMessage
import dev.joseramos.aireader.core.data.book.ChatRepository
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.core.data.settings.DailyUsage
import dev.joseramos.aireader.core.data.settings.SecretStore
import dev.joseramos.aireader.core.data.settings.UsageRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Si se puede preguntar y, si no, por qué (para explicarlo con una acción). */
sealed interface ChatAvailability {
    data object Ready : ChatAvailability

    data object NoApiKey : ChatAvailability

    data class ModelMissing(val model: ModelState) : ChatAvailability

    data class Indexing(val progress: Float) : ChatAvailability

    data object Failed : ChatAvailability
}

data class ChatUiState(
    val book: Book? = null,
    val messages: List<ChatMessage> = emptyList(),
    val streaming: String? = null,
    val answering: Boolean = false,
    val error: String? = null,
    val availability: ChatAvailability = ChatAvailability.Ready,
    val usage: DailyUsage = DailyUsage()
)

private data class ChatInputs(val book: Book?, val hasKey: Boolean, val model: ModelState, val usage: DailyUsage)

private data class Transient(val streaming: String? = null, val answering: Boolean = false, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    books: BookRepository,
    private val secrets: SecretStore,
    private val chat: ChatRepository,
    private val askBook: AskBook,
    private val retriever: HybridRetriever,
    private val models: ModelManager,
    usage: UsageRepository,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {
    val bookId = savedStateHandle.toRoute<ChatRoute>().bookId
    private val threadId = MutableStateFlow<Long?>(null)
    private val transient = MutableStateFlow(Transient())
    private var answerJob: Job? = null

    private val messages = threadId.filterNotNull().flatMapLatest {
        chat.observeMessages(it)
    }.onStart { emit(emptyList()) }

    private val inputs = combine(books.observeBook(bookId), secrets.hasApiKey, models.states, usage.today) {
            book,
            hasKey,
            modelStates,
            today
        ->
        ChatInputs(book, hasKey, modelStates[ModelCatalog.e5Small.id] ?: ModelState.NotInstalled, today)
    }

    val state: StateFlow<ChatUiState> = combine(inputs, messages, transient) { input, messages, t ->
        val book = input.book
        ChatUiState(
            book = book,
            messages = messages,
            streaming = t.streaming,
            answering = t.answering,
            error = t.error,
            availability = availability(book, input.hasKey, input.model),
            usage = input.usage
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch { threadId.value = chat.currentThread(bookId) }
    }

    fun send(question: String) {
        val thread = threadId.value ?: return
        val text = question.trim()
        if (text.isEmpty() || transient.value.answering) return
        answerJob = viewModelScope.launch {
            transient.value = Transient(streaming = "", answering = true)
            try {
                askBook.ask(bookId, thread, text).collect { event ->
                    when (event) {
                        is AskEvent.Delta -> transient.update {
                            it.copy(streaming = (it.streaming ?: "") + event.text)
                        }
                        is AskEvent.Completed -> transient.update { it.copy(streaming = null) }
                    }
                }
                transient.value = Transient()
            } catch (e: LlmException) {
                transient.value = Transient(error = e.message)
            } catch (e: EmbeddingModelMissingException) {
                transient.value = Transient(error = e.message)
            }
        }
    }

    fun stop() {
        answerJob?.cancel()
        transient.value = Transient()
    }

    fun dismissError() = transient.update { it.copy(error = null) }

    fun newConversation() {
        stop()
        viewModelScope.launch { threadId.value = chat.newThread(bookId) }
    }

    fun downloadModel() = models.download(ModelCatalog.e5Small.id)

    fun saveApiKey(key: String) {
        viewModelScope.launch { secrets.setApiKey(key) }
    }

    override fun onCleared() {
        // El modelo de embeddings ocupa memoria: se libera al salir del chat.
        appScope.launch { retriever.release() }
    }

    private fun availability(book: Book?, hasKey: Boolean, model: ModelState): ChatAvailability = when {
        !hasKey -> ChatAvailability.NoApiKey
        book == null -> ChatAvailability.Indexing(0f)
        book.indexStatus == IndexStatus.READY -> ChatAvailability.Ready
        book.indexStatus == IndexStatus.FAILED -> ChatAvailability.Failed
        model !is ModelState.Installed -> ChatAvailability.ModelMissing(model)
        else -> ChatAvailability.Indexing(book.indexProgress)
    }
}
