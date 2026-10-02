package dev.joseramos.aireader.feature.reader

import android.graphics.Bitmap
import android.util.Size
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.joseramos.aireader.ai.characters.CharacterAnalysis
import dev.joseramos.aireader.ai.characters.CharacterBrowser
import dev.joseramos.aireader.ai.characters.CharactersSnapshot
import dev.joseramos.aireader.ai.models.ModelCatalog
import dev.joseramos.aireader.ai.models.ModelInfo
import dev.joseramos.aireader.ai.models.ModelManager
import dev.joseramos.aireader.ai.models.ModelState
import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.common.IoDispatcher
import dev.joseramos.aireader.core.data.book.Book
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.Bookmark
import dev.joseramos.aireader.core.data.book.BookmarkRepository
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.PageText
import dev.joseramos.aireader.core.data.book.ReadingPosition
import dev.joseramos.aireader.core.data.book.ReadingPositionRepository
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import dev.joseramos.aireader.pdf.PdfPageRenderer
import dev.joseramos.aireader.tts.PlaybackController
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReaderMode { PDF, TEXT }

/** Estado del lector. Las páginas de [startPage] y [currentPage] son en base 1. */
data class ReaderUiState(
    val book: Book? = null,
    val opened: Boolean = false,
    val pageSizes: List<Size> = emptyList(),
    val startPage: Int = 1,
    val currentPage: Int = 1,
    val chapters: List<Chapter> = emptyList(),
    val pages: List<PageText> = emptyList(),
    val mode: ReaderMode = ReaderMode.PDF,
    val textScale: Float = 1f,
    val error: String? = null
) {
    val pageCount: Int get() = pageSizes.size
    val currentChapter: Chapter? get() = chapters.lastOrNull { currentPage >= it.startPage }
}

/**
 * Lo que el lector añade sobre el libro: marcapáginas, personajes desbloqueados (solo en novelas)
 * y las sugerencias que se muestran al abrirlo.
 */
data class ReaderExtras(
    val bookmarks: List<Bookmark> = emptyList(),
    val characters: CharactersSnapshot = CharactersSnapshot(),
    /** Página del último marcapáginas, para el chip «Ir al marcapáginas» al abrir. */
    val bookmarkSuggestion: Int? = null,
    /** El libro llevaba más de una semana sin abrirse: se ofrece «¿Repasamos lo anterior?». */
    val offerRecap: Boolean = false
)

private data class ReaderMeta(
    val opened: Boolean = false,
    val pageSizes: List<Size> = emptyList(),
    val startPage: Int = 1,
    val currentPage: Int = 1,
    val mode: ReaderMode = ReaderMode.PDF,
    val error: String? = null
)

private data class OpeningPrompts(val bookmarkSuggestion: Int? = null, val offerRecap: Boolean = false)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    contentRepository: BookContentRepository,
    private val positions: ReadingPositionRepository,
    private val bookmarks: BookmarkRepository,
    private val settings: SettingsRepository,
    private val playbackController: PlaybackController,
    private val modelManager: ModelManager,
    characterBrowser: CharacterBrowser,
    private val characterAnalysis: CharacterAnalysis,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ReaderRoute>()
    val bookId = route.bookId

    private var renderer: PdfPageRenderer? = null
    private val meta = MutableStateFlow(ReaderMeta(mode = savedStateHandle[KEY_MODE] ?: ReaderMode.PDF))
    private val savedState = savedStateHandle
    private val visiblePage = MutableStateFlow<Int?>(null)
    private val prompts = MutableStateFlow(OpeningPrompts())
    private val jumps = Channel<Int>(Channel.CONFLATED)

    /** Saltos de página pedidos desde fuera de la lista (ficha de personaje, marcapáginas…). */
    val jumpRequests: Flow<Int> = jumps.receiveAsFlow()

    val state: StateFlow<ReaderUiState> = combine(
        bookRepository.observeBook(bookId),
        contentRepository.observeChapters(bookId),
        contentRepository.observePages(bookId),
        settings.settings,
        meta
    ) { book, chapters, pages, appSettings, m ->
        ReaderUiState(
            book = book,
            opened = m.opened,
            pageSizes = m.pageSizes,
            startPage = m.startPage,
            currentPage = m.currentPage,
            chapters = chapters,
            pages = pages,
            mode = m.mode,
            textScale = appSettings.textScale,
            error = m.error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderUiState())

    /** Personajes solo en novelas: en otros libros no se calcula nada. */
    private val characters: Flow<CharactersSnapshot> = bookRepository.observeBook(bookId)
        .map { it?.isLiterature == true }
        .distinctUntilChanged()
        .flatMapLatest { literature ->
            if (literature) characterBrowser.observe(bookId) else flowOf(CharactersSnapshot())
        }

    val extras: StateFlow<ReaderExtras> = combine(bookmarks.observe(bookId), characters, prompts) {
            marks,
            snapshot,
            p
        ->
        ReaderExtras(marks, snapshot, p.bookmarkSuggestion, p.offerRecap)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderExtras())

    /** Lectura en voz alta de este libro (vacía si suena otro). */
    val playback: StateFlow<ReaderPlayback> = playbackController.state.map { playing ->
        if (playing.bookId != bookId) {
            ReaderPlayback()
        } else {
            ReaderPlayback(
                isPlaying = playing.isPlaying,
                location = playing.position?.let { PhraseLocation(it.page, it.paragraph, it.phrase) },
                phrase = playing.phrase,
                error = playing.error
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderPlayback())

    /** Estado de la voz que necesita este libro (en español o en inglés, según su idioma). */
    val voice: StateFlow<ModelState> = combine(playbackController.state, modelManager.states) { playing, models ->
        models[playing.voiceId] ?: ModelState.NotInstalled
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelState.NotInstalled)

    /** Voz que falta para leer este libro. */
    val missingVoice: ModelInfo get() = ModelCatalog.byId(playbackController.state.value.voiceId)

    fun listen(page: Int) {
        viewModelScope.launch { playbackController.play(bookId, page) }
    }

    fun listenFrom(location: PhraseLocation) {
        viewModelScope.launch {
            playbackController.playFrom(bookId, ReadingPosition(location.page, location.paragraph, location.phrase))
        }
    }

    fun pause() = playbackController.pause()

    fun downloadVoice() = modelManager.download(playbackController.state.value.voiceId)

    fun dismissPlaybackError() = playbackController.clearError()

    init {
        viewModelScope.launch { open() }
        viewModelScope.launch {
            visiblePage.filterNotNull().distinctUntilChanged().debounce(SAVE_DEBOUNCE_MS).collect { page ->
                // Si la voz ya guardó una posición más precisa en esta página, se respeta.
                if (positions.get(bookId)?.page != page) positions.save(bookId, ReadingPosition(page))
            }
        }
        viewModelScope.launch {
            // Una página cuenta como leída (y desbloquea personajes) si se queda en pantalla un rato,
            // no por pasar por ella deslizando.
            visiblePage.filterNotNull().distinctUntilChanged().debounce(READ_DWELL_MS).collect { page ->
                positions.markRead(bookId, page)
            }
        }
    }

    private suspend fun open() {
        val book = bookRepository.getBook(bookId) ?: return
        val saved = positions.get(bookId)
        bookRepository.markOpened(bookId)
        try {
            val opened = withContext(io) { PdfPageRenderer(File(book.filePath)) }
            renderer = opened
            val start = (route.page ?: saved?.page ?: 1).coerceIn(1, opened.pageCount)
            meta.update { it.copy(opened = true, pageSizes = opened.pageSizes, startPage = start, currentPage = start) }

            val lastBookmark = bookmarks.observe(bookId).first().maxByOrNull { it.createdAt }
            val longBreak = book.lastOpenedAt?.let { System.currentTimeMillis() - it > RECAP_AFTER_MS } == true
            prompts.value = OpeningPrompts(
                bookmarkSuggestion = lastBookmark?.page?.takeIf { route.page == null && it != start },
                offerRecap = longBreak && route.page == null && (saved?.page ?: 1) > 1
            )
        } catch (e: IOException) {
            meta.update { it.copy(error = e.message ?: "No se pudo abrir el PDF") }
        }
        if (book.isLiterature) characterAnalysis.startIfAuto(bookId)
    }

    /** Página [index] (base 0) renderizada a [widthPx]. */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = runCatching { renderer?.render(index, widthPx) }.getOrNull()

    fun onPageVisible(page: Int) {
        meta.update { it.copy(currentPage = page) }
        visiblePage.value = page
    }

    fun jumpTo(page: Int) {
        jumps.trySend(page)
    }

    fun setMode(mode: ReaderMode) {
        savedState[KEY_MODE] = mode
        meta.update { it.copy(mode = mode, startPage = it.currentPage) }
    }

    fun setTextScale(scale: Float) {
        viewModelScope.launch { settings.setTextScale(scale) }
    }

    fun toggleBookmark(page: Int) {
        viewModelScope.launch { bookmarks.toggle(bookId, page) }
    }

    fun setBookmarkNote(page: Int, note: String?) {
        viewModelScope.launch { bookmarks.setNote(bookId, page, note) }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { bookmarks.delete(id) }
    }

    fun dismissBookmarkSuggestion() = prompts.update { it.copy(bookmarkSuggestion = null) }

    fun dismissRecapOffer() = prompts.update { it.copy(offerRecap = false) }

    override fun onCleared() {
        val toRelease = renderer ?: return
        appScope.launch { toRelease.release() }
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val SAVE_DEBOUNCE_MS = 800L
        const val READ_DWELL_MS = 4_000L
        const val RECAP_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
