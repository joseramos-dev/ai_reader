package dev.joseramos.aireader.feature.reader

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.joseramos.aireader.ai.characters.CharacterAnalysis
import dev.joseramos.aireader.ai.characters.CharacterBrowser
import dev.joseramos.aireader.ai.characters.CharactersSnapshot
import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.common.IoDispatcher
import dev.joseramos.aireader.core.data.book.Book
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.Bookmark
import dev.joseramos.aireader.core.data.book.BookmarkRepository
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.Highlight
import dev.joseramos.aireader.core.data.book.HighlightRepository
import dev.joseramos.aireader.core.data.book.PageText
import dev.joseramos.aireader.core.data.book.ReadingPosition
import dev.joseramos.aireader.core.data.book.ReadingPositionRepository
import dev.joseramos.aireader.core.data.db.IndexStatus
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

/** Estado del lector. [currentPage] es en base 1. */
data class ReaderUiState(
    val book: Book? = null,
    val opened: Boolean = false,
    val pageSizes: List<Size> = emptyList(),
    val currentPage: Int = 1,
    /** Capítulos (nivel 0): los que se resumen y por los que salta la voz. */
    val chapters: List<Chapter> = emptyList(),
    /** Índice completo, con los apartados de cada capítulo, para el menú. */
    val contents: List<Chapter> = emptyList(),
    val pages: List<PageText> = emptyList(),
    val mode: ReaderMode = ReaderMode.PDF,
    val textScale: Float = 1f,
    val error: String? = null
) {
    val pageCount: Int get() = pageSizes.size
    val currentChapter: Chapter? get() = chapters.lastOrNull { currentPage >= it.startPage }

    /** La entrada más concreta del índice (capítulo o apartado) en la que está la página actual. */
    val currentEntry: Chapter? get() = contents.lastOrNull { currentPage >= it.startPage }
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
    val offerRecap: Boolean = false,
    /** Subrayados de texto (solo modo texto), con nota opcional. */
    val highlights: List<Highlight> = emptyList()
)

private data class ReaderMeta(
    val opened: Boolean = false,
    val pageSizes: List<Size> = emptyList(),
    val currentPage: Int = 1,
    val mode: ReaderMode = ReaderMode.PDF,
    val error: String? = null
)

private data class OpeningPrompts(val bookmarkSuggestion: Int? = null, val offerRecap: Boolean = false)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    contentRepository: BookContentRepository,
    private val positions: ReadingPositionRepository,
    private val bookmarks: BookmarkRepository,
    private val highlights: HighlightRepository,
    private val settings: SettingsRepository,
    private val playbackController: PlaybackController,
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

    /**
     * El libro, sin el progreso de la indexación cuando ya no se está extrayendo el texto: el lector
     * solo lo enseña mientras tanto, y así cada avance de los capítulos o de la búsqueda del chat (se
     * escriben muchos) no vuelve a emitir el estado ni a recomponer el lector.
     */
    private val book = bookRepository.observeBook(bookId)
        .map { book ->
            val extracting =
                book?.indexStatus == IndexStatus.PENDING || book?.indexStatus == IndexStatus.EXTRACTING_TEXT
            if (book == null || extracting) book else book.copy(indexProgress = 0f)
        }
        .distinctUntilChanged()

    val state: StateFlow<ReaderUiState> = combine(
        book,
        contentRepository.observeContents(bookId),
        contentRepository.observePages(bookId),
        settings.settings,
        meta
    ) { book, contents, pages, appSettings, m ->
        ReaderUiState(
            book = book,
            opened = m.opened,
            pageSizes = m.pageSizes,
            currentPage = m.currentPage,
            chapters = contents.filter { it.level == 0 },
            contents = contents,
            pages = pages,
            mode = m.mode,
            textScale = appSettings.textScale,
            error = m.error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderUiState())

    /** Personajes solo en novelas: en otros libros no se calcula nada. */
    private val characters: Flow<CharactersSnapshot> = book
        .map { it?.isLiterature == true }
        .distinctUntilChanged()
        .flatMapLatest { literature ->
            if (literature) characterBrowser.observe(bookId) else flowOf(CharactersSnapshot())
        }

    val extras: StateFlow<ReaderExtras> = combine(
        bookmarks.observe(bookId),
        characters,
        prompts,
        highlights.observe(bookId)
    ) { marks, snapshot, p, hls ->
        ReaderExtras(marks, snapshot, p.bookmarkSuggestion, p.offerRecap, hls)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderExtras())

    /** Lectura en voz alta de este libro (vacía si suena otro). */
    val playback: StateFlow<ReaderPlayback> = playbackController.state.map { playing ->
        if (playing.bookId != bookId) {
            ReaderPlayback()
        } else {
            ReaderPlayback(
                isPlaying = playing.isPlaying,
                isListening = playing.isListening,
                location = playing.position?.let { PhraseLocation(it.page, it.paragraph, it.phrase) },
                phrase = playing.phrase,
                error = playing.error,
                language = playing.language
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderPlayback())

    fun listen(page: Int) {
        viewModelScope.launch { playbackController.play(bookId, page) }
    }

    fun listenFrom(location: PhraseLocation) {
        viewModelScope.launch {
            playbackController.playFrom(bookId, ReadingPosition(location.page, location.paragraph, location.phrase))
        }
    }

    /** Controles de la voz mientras se escucha este libro. */
    val listening = ListeningActions(
        onPause = playbackController::pause,
        onResume = { viewModelScope.launch { playbackController.resume() } },
        onPreviousPhrase = playbackController::previous,
        onNextPhrase = playbackController::next,
        onStop = playbackController::stop
    )

    fun dismissPlaybackError() = playbackController.clearError()

    init {
        // Si sonaba otro libro, se para y se olvida (frases, audio y mini reproductor) al abrir este.
        viewModelScope.launch { playbackController.onBookOpened(bookId) }
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
            val started = SystemClock.elapsedRealtime()
            // Los tamaños de página se guardan en caché: medirlos abre todas las páginas del PDF.
            val (opened, sizes) = withContext(io) {
                PdfPageRenderer(File(book.filePath), File(context.cacheDir, "page_sizes/$bookId")).let {
                    it to it.pageSizes
                }
            }
            Log.d(TAG, "PDF de ${opened.pageCount} páginas abierto en ${SystemClock.elapsedRealtime() - started} ms")
            renderer = opened
            val start = (route.page ?: saved?.page ?: 1).coerceIn(1, opened.pageCount)
            meta.update { it.copy(opened = true, pageSizes = sizes, currentPage = start) }

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
        meta.update { it.copy(mode = mode) }
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

    fun addHighlight(page: Int, paragraph: Int, range: IntRange, note: String? = null) {
        viewModelScope.launch { highlights.add(bookId, page, paragraph, range, note) }
    }

    fun setHighlightNote(id: Long, note: String?) {
        viewModelScope.launch { highlights.setNote(id, note) }
    }

    fun deleteHighlight(id: Long) {
        viewModelScope.launch { highlights.delete(id) }
    }

    fun dismissRecapOffer() = prompts.update { it.copy(offerRecap = false) }

    override fun onCleared() {
        val toRelease = renderer ?: return
        appScope.launch { toRelease.release() }
    }

    private companion object {
        const val TAG = "Reader"
        const val KEY_MODE = "mode"
        const val SAVE_DEBOUNCE_MS = 800L
        const val READ_DWELL_MS = 4_000L
        const val RECAP_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
