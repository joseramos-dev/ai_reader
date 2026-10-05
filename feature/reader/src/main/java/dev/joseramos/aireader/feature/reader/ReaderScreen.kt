package dev.joseramos.aireader.feature.reader

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.Highlight
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.core.data.settings.AppSettings
import dev.joseramos.aireader.core.designsystem.component.AppBottomSheet
import dev.joseramos.aireader.core.designsystem.component.BarIconButton
import dev.joseramos.aireader.core.designsystem.component.Cell
import dev.joseramos.aireader.core.designsystem.component.EmptyState
import dev.joseramos.aireader.core.designsystem.component.barBlur
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.BarSize
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.text.Language
import dev.joseramos.aireader.tts.PlaybackError
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** Estado de la lectura en voz alta que muestra el lector (lo aporta F5). */
data class ReaderPlayback(
    val isPlaying: Boolean = false,
    /** Se está escuchando este libro (sonando o en pausa): se muestran los controles de la voz. */
    val isListening: Boolean = false,
    val location: PhraseLocation? = null,
    val phrase: String? = null,
    val error: PlaybackError? = null,
    /** Idioma del libro: el de la voz que hay que instalar si falta. */
    val language: Language = Language.SPANISH
)

/** Acciones del lector; las de voz, IA y personajes son opcionales para poder montarlo por fases. */
class ReaderActions(
    val onBack: () -> Unit,
    val onPageVisible: (Int) -> Unit,
    val onSetMode: (ReaderMode) -> Unit,
    val onSetTextScale: (Float) -> Unit,
    val onListen: ((page: Int) -> Unit)? = null,
    /** Controles mientras se escucha: frase y página anterior/siguiente, pausa y detener. */
    val listening: ListeningActions? = null,
    /** Seguir la lectura en voz alta desde una frase tocada en el modo texto. */
    val onListenFrom: ((PhraseLocation) -> Unit)? = null,
    val onOpenAi: (() -> Unit)? = null,
    val onToggleBookmark: (page: Int) -> Unit = {},
    val onBookmarkNote: (page: Int, note: String?) -> Unit = { _, _ -> },
    val onDeleteBookmark: (id: Long) -> Unit = {},
    val onDismissBookmarkSuggestion: () -> Unit = {},
    val onAddHighlight: (page: Int, paragraph: Int, range: IntRange, note: String?) -> Unit = { _, _, _, _ -> },
    val onEditHighlightNote: (id: Long, note: String?) -> Unit = { _, _ -> },
    val onDeleteHighlight: (id: Long) -> Unit = {},
    val onOpenCharacters: (() -> Unit)? = null,
    val onOpenCharacter: (Long) -> Unit = {},
    val onRecap: (() -> Unit)? = null,
    val onDismissRecap: () -> Unit = {}
)

private enum class ReaderSheet { CHAPTERS, APPEARANCE }

private enum class ContentsTab { CHAPTERS, BOOKMARKS, HIGHLIGHTS }

@Composable
internal fun ReaderScreen(
    state: ReaderUiState,
    playback: ReaderPlayback,
    render: suspend (Int, Int) -> Bitmap?,
    actions: ReaderActions,
    extras: ReaderExtras = ReaderExtras(),
    jumpRequests: Flow<Int> = emptyFlow()
) {
    val colors = AppTheme.colors
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var sheet by rememberSaveable { mutableStateOf<ReaderSheet?>(null) }
    var goToPage by rememberSaveable { mutableStateOf(false) }
    var notePage by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingHighlight by remember { mutableStateOf<Highlight?>(null) }
    val scope = rememberCoroutineScope()
    val hazeState = rememberHazeState()

    if (state.error != null) {
        EmptyState(
            Icons.Outlined.TextFields,
            stringResource(R.string.reader_open_error),
            state.error,
            Modifier.statusBarsPadding()
        )
        return
    }
    if (!state.opened) {
        Box(Modifier.fillMaxSize().background(colors.groupedBackground))
        return
    }
    // Mientras se extrae el texto el libro no se lee (la biblioteca tampoco deja abrirlo); se llega
    // aquí, por ejemplo, al abrir un PDF desde otra app. Capítulos y chat no lo impiden.
    val book = state.book
    if (book != null && (book.indexStatus == IndexStatus.PENDING || book.indexStatus == IndexStatus.EXTRACTING_TEXT)) {
        EmptyState(
            Icons.Outlined.TextFields,
            stringResource(R.string.reader_preparing_title),
            stringResource(
                R.string.reader_preparing_message,
                (book.indexProgress * 2 * PERCENT).roundToInt().coerceIn(0, PERCENT)
            ),
            Modifier.statusBarsPadding()
        )
        return
    }

    // Una lista por modo; ambas tienen un elemento por página, así que el índice es la página - 1.
    // Se parte de currentPage (no de un valor fijado solo al abrir el libro): así, si la pantalla se
    // recompone entera al volver de otra pantalla (p. ej. el chat de IA), la lista se repone donde
    // estaba el usuario de verdad, no donde se abrió el libro.
    val listState = remember(state.mode) { LazyListState(state.currentPage - 1) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.dominantPage() }.collect(actions.onPageVisible)
    }
    // Mientras se escucha, la vista sigue a la página que se está leyendo (también al saltar con los
    // controles de la voz, aunque se haya desplazado a mano a otra página).
    var followRequests by remember { mutableIntStateOf(0) }
    var pageTransitionLoading by remember { mutableStateOf(false) }
    LaunchedEffect(playback.location?.page, playback.isPlaying, followRequests) {
        val page = playback.location?.page ?: return@LaunchedEffect
        if (playback.isListening && page != listState.dominantPage()) {
            pageTransitionLoading = true
            try {
                listState.animateScrollToItem(page - 1)
            } finally {
                pageTransitionLoading = false
            }
        }
    }
    val listening = remember(actions.listening) { actions.listening?.following { followRequests++ } }
    val scrollTo: (Int) -> Unit = { page ->
        scope.launch {
            listState.scrollToItem(
                (page - 1).coerceIn(
                    0,
                    state.pageCount - 1
                )
            )
        }
    }
    LaunchedEffect(listState, jumpRequests) { jumpRequests.collect(scrollTo) }
    // El chip «Ir al marcapáginas» se ofrece solo unos segundos.
    LaunchedEffect(extras.bookmarkSuggestion) {
        if (extras.bookmarkSuggestion != null) {
            delay(BOOKMARK_CHIP_MS)
            actions.onDismissBookmarkSuggestion()
        }
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val controlsHeight = if (playback.isListening) CONTROLS_HEIGHT + LISTENING_EXTRA_HEIGHT else CONTROLS_HEIGHT
    val contentPadding = PaddingValues(top = topInset + BarSize.topBar, bottom = bottomInset + controlsHeight)

    Box(
        Modifier.fillMaxSize().background(
            if (state.mode ==
                ReaderMode.PDF
            ) {
                colors.groupedBackground
            } else {
                colors.background
            }
        )
    ) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            when (state.mode) {
                ReaderMode.PDF -> PdfPages(
                    listState = listState,
                    pageSizes = state.pageSizes,
                    render = render,
                    contentPadding = contentPadding,
                    onTap = { controlsVisible = !controlsVisible }
                )
                ReaderMode.TEXT -> TextModeContent(
                    state = state,
                    playback = playback,
                    extras = extras,
                    actions = actions,
                    listState = listState,
                    contentPadding = contentPadding,
                    onCreateHighlight = { actions.onAddHighlight(it.page, it.paragraph, it.range, null) },
                    onTapHighlight = { editingHighlight = it },
                    onTap = { controlsVisible = !controlsVisible }
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopControls(
                state = state,
                marked = extras.bookmarks.any { it.page == state.currentPage },
                actions = actions,
                onBookmarkNote = { notePage = state.currentPage },
                modifier = Modifier.barBlur(hazeState)
            )
        }
        AnimatedVisibility(
            visible = pageTransitionLoading,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
        }
        extras.bookmarkSuggestion?.let { page ->
            BookmarkSuggestionChip(
                page = page,
                onGo = {
                    scrollTo(page)
                    actions.onDismissBookmarkSuggestion()
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset + BarSize.topBar + Spacing.xs)
            )
        }
        AnimatedVisibility(
            visible = controlsVisible || playback.isListening,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomControls(
                state = state,
                playback = playback,
                extras = extras,
                expanded = controlsVisible,
                actions = actions,
                listening = listening,
                onChapters = { sheet = ReaderSheet.CHAPTERS },
                onAppearance = { sheet = ReaderSheet.APPEARANCE },
                onGoToPage = { goToPage = true },
                onSeek = scrollTo,
                modifier = Modifier.barBlur(hazeState)
            )
        }
    }

    when (sheet) {
        ReaderSheet.CHAPTERS -> ContentsSheet(
            state = state,
            extras = extras,
            onSelectPage = { page ->
                sheet = null
                scrollTo(page)
            },
            onDeleteBookmark = actions.onDeleteBookmark,
            onDeleteHighlight = actions.onDeleteHighlight,
            onDismiss = { sheet = null }
        )
        ReaderSheet.APPEARANCE -> AppearanceSheet(state, actions, onDismiss = { sheet = null })
        null -> Unit
    }
    notePage?.let { page ->
        BookmarkNoteDialog(
            page = page,
            initial = extras.bookmarks.firstOrNull { it.page == page }?.note,
            onSave = { note ->
                actions.onBookmarkNote(page, note)
                notePage = null
            },
            onDismiss = { notePage = null }
        )
    }
    editingHighlight?.let { h ->
        HighlightSheet(
            quotedText = quotedText(h, state.pages).orEmpty(),
            initialNote = h.note,
            onSave = { note ->
                actions.onEditHighlightNote(h.id, note)
                editingHighlight = null
            },
            onRemove = {
                actions.onDeleteHighlight(h.id)
                editingHighlight = null
            },
            onDismiss = { editingHighlight = null }
        )
    }
    if (goToPage) {
        GoToPageDialog(state.pageCount, onGo = { page ->
            goToPage = false
            scrollTo(page)
        }, onDismiss = {
            goToPage =
                false
        })
    }
}

@Composable
private fun TextModeContent(
    state: ReaderUiState,
    playback: ReaderPlayback,
    extras: ReaderExtras,
    actions: ReaderActions,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onCreateHighlight: (PendingHighlight) -> Unit,
    onTapHighlight: (Highlight) -> Unit,
    onTap: () -> Unit
) {
    val book = state.book
    // Se muestra el texto en cuanto existe, aunque la indexación siga con capítulos o embeddings.
    if (state.pages.isEmpty()) {
        val failed = book?.indexStatus == IndexStatus.FAILED
        val extracting = book?.indexStatus == IndexStatus.EXTRACTING_TEXT
        EmptyState(
            icon = Icons.Outlined.TextFields,
            title = stringResource(
                if (failed) R.string.reader_text_failed_title else R.string.reader_text_preparing_title
            ),
            message = when {
                failed -> stringResource(R.string.reader_text_failed_message)
                extracting -> stringResource(
                    R.string.reader_text_preparing_message,
                    ((book.indexProgress) * 2 * PERCENT).roundToInt().coerceAtMost(PERCENT)
                )
                else -> stringResource(R.string.reader_text_waiting_message)
            },
            modifier = Modifier.padding(contentPadding).pointerInput(Unit) { detectTapGestures { onTap() } }
        )
        return
    }
    TextPages(
        listState = listState,
        pageCount = state.pageCount,
        pages = state.pages,
        textScale = state.textScale,
        highlight = playback.location,
        highlights = extras.highlights,
        contentPadding = contentPadding,
        names = extras.characters.index,
        onTapCharacter = actions.onOpenCharacter,
        // Solo con la voz en marcha (sonando o en pausa); si no, tocar muestra u oculta los controles.
        onTapPhrase = actions.onListenFrom?.takeIf { playback.location != null },
        onCreateHighlight = onCreateHighlight,
        onTapHighlight = onTapHighlight,
        onTap = onTap,
        chapters = state.contents
    )
}

@Composable
private fun TopControls(
    state: ReaderUiState,
    marked: Boolean,
    actions: ReaderActions,
    onBookmarkNote: () -> Unit,
    modifier: Modifier
) {
    val colors = AppTheme.colors
    Box(modifier.fillMaxWidth().statusBarsPadding().height(BarSize.topBar)) {
        BarIconButton(
            Icons.AutoMirrored.Rounded.ArrowBackIos,
            stringResource(R.string.reader_back),
            actions.onBack,
            Modifier.align(Alignment.CenterStart).padding(start = Spacing.xxs)
        )
        Row(Modifier.align(Alignment.CenterEnd).padding(end = Spacing.xxs)) {
            actions.onOpenCharacters?.let {
                BarIconButton(Icons.Outlined.People, stringResource(R.string.reader_characters), it)
            }
            BookmarkButton(marked, { actions.onToggleBookmark(state.currentPage) }, onBookmarkNote)
        }
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                state.book?.title ?: stringResource(R.string.reader_untitled),
                style = AppTheme.typography.headline,
                color = colors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            state.currentChapter?.let {
                Text(
                    it.title,
                    style = AppTheme.typography.caption,
                    color = colors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BottomControls(
    state: ReaderUiState,
    playback: ReaderPlayback,
    extras: ReaderExtras,
    expanded: Boolean,
    actions: ReaderActions,
    listening: ListeningActions?,
    onChapters: () -> Unit,
    onAppearance: () -> Unit,
    onGoToPage: () -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier
) {
    val colors = AppTheme.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.m, vertical = Spacing.xs)) {
        if (extras.offerRecap && actions.onRecap != null) {
            RecapOfferCard(
                onRecap = actions.onRecap,
                onDismiss = actions.onDismissRecap,
                modifier = Modifier.padding(bottom = Spacing.xs)
            )
        }
        if (expanded && state.mode == ReaderMode.PDF) {
            PageCharacterChips(
                characters = extras.characters,
                page = state.pages.firstOrNull { it.page == state.currentPage },
                onOpenCharacter = actions.onOpenCharacter,
                modifier = Modifier.padding(bottom = Spacing.xxs)
            )
        }
        if (playback.isListening && playback.phrase != null) {
            // Tira «leyendo ahora»: en modo PDF no se puede resaltar sobre la página.
            Text(
                playback.phrase,
                style = AppTheme.typography.subheadline,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxs)
            )
        }
        if (playback.isListening && listening != null) ListeningBar(playback.isPlaying, listening)
        if (expanded) {
            val shownPage = dragging?.roundToInt() ?: state.currentPage
            Text(
                stringResource(R.string.reader_page_of, shownPage, state.pageCount),
                style = AppTheme.typography.footnote,
                color = colors.secondaryLabel,
                modifier = Modifier.align(
                    Alignment.CenterHorizontally
                ).clickable(role = Role.Button, onClick = onGoToPage).padding(Spacing.xxs)
            )
            if (state.pageCount > 1) {
                Slider(
                    value = dragging ?: state.currentPage.toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { onSeek(it.roundToInt()) }
                        dragging = null
                    },
                    valueRange = 1f..state.pageCount.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.fill
                    )
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ControlButton(
                    Icons.AutoMirrored.Rounded.FormatListBulleted,
                    stringResource(R.string.reader_chapters),
                    onChapters
                )
                // Mientras se escucha, la pausa y el resto de controles están en la barra de la voz.
                if (actions.onListen != null && !playback.isListening) {
                    ControlButton(Icons.Outlined.Headphones, stringResource(R.string.reader_listen)) {
                        actions.onListen.invoke(state.currentPage)
                    }
                }
                actions.onOpenAi?.let {
                    ControlButton(Icons.Outlined.AutoAwesome, stringResource(R.string.reader_ai), it)
                }
                ControlButton(Icons.Outlined.TextFields, stringResource(R.string.reader_appearance), onAppearance)
            }
        }
    }
}

/** Las mismas acciones, avisando además de que la vista debe volver a la página que se lee. */
private fun ListeningActions.following(onFollow: () -> Unit): ListeningActions {
    fun wrap(action: () -> Unit): () -> Unit = {
        action()
        onFollow()
    }
    return ListeningActions(
        onPause = onPause,
        onResume = wrap(onResume),
        onPreviousPhrase = wrap(onPreviousPhrase),
        onNextPhrase = wrap(onNextPhrase),
        onStop = onStop
    )
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier.clickable(
            role = Role.Button,
            onClick = onClick
        ).heightIn(min = BarSize.minTouch).padding(horizontal = Spacing.s, vertical = Spacing.xxs),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
        Text(label, style = AppTheme.typography.caption, color = colors.secondaryLabel)
    }
}

/** Hoja ☰: capítulos y marcapáginas, como el índice de un lector de libros de iOS. */
@Composable
private fun ContentsSheet(
    state: ReaderUiState,
    extras: ReaderExtras,
    onSelectPage: (Int) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onDeleteHighlight: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(ContentsTab.CHAPTERS) }
    AppBottomSheet(onDismissRequest = onDismiss, skipPartiallyExpanded = false) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
            ContentsTab.entries.forEachIndexed { i, entry ->
                SegmentedButton(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    shape = SegmentedButtonDefaults.itemShape(i, ContentsTab.entries.size),
                    // Sin la marca de selección: con solo un icono, el relleno ya indica la pestaña.
                    icon = {}
                ) {
                    val (icon, label) = when (entry) {
                        ContentsTab.CHAPTERS -> Icons.AutoMirrored.Rounded.FormatListBulleted to R.string.reader_chapters
                        ContentsTab.BOOKMARKS -> Icons.Outlined.BookmarkBorder to R.string.reader_bookmarks
                        ContentsTab.HIGHLIGHTS -> Icons.Outlined.Highlight to R.string.reader_highlights
                    }
                    Icon(icon, contentDescription = stringResource(label), modifier = Modifier.size(22.dp))
                }
            }
        }
        when (tab) {
            ContentsTab.CHAPTERS -> ChaptersList(state.contents, state.currentEntry) { onSelectPage(it.startPage) }
            ContentsTab.BOOKMARKS -> BookmarksList(
                bookmarks = extras.bookmarks,
                chapters = state.chapters,
                pages = state.pages,
                onSelect = { onSelectPage(it.page) },
                onDelete = { onDeleteBookmark(it.id) }
            )
            ContentsTab.HIGHLIGHTS -> HighlightsList(
                highlights = extras.highlights,
                chapters = state.chapters,
                pages = state.pages,
                onSelect = { onSelectPage(it.page) },
                onDelete = { onDeleteHighlight(it.id) }
            )
        }
    }
}

@Composable
private fun ChaptersList(chapters: List<Chapter>, current: Chapter?, onSelect: (Chapter) -> Unit) {
    if (chapters.isEmpty()) {
        Text(
            stringResource(R.string.reader_no_chapters),
            style = AppTheme.typography.subheadline,
            color = AppTheme.colors.secondaryLabel,
            modifier = Modifier.padding(Spacing.l)
        )
        return
    }
    LazyColumn(Modifier.heightIn(max = 560.dp)) {
        items(chapters, key = { it.id }) { chapter ->
            // Los apartados van sangrados bajo su capítulo.
            Cell(
                title = chapter.title,
                subtitle = stringResource(R.string.reader_page_short, chapter.startPage),
                modifier = Modifier.padding(start = SECTION_INDENT * chapter.level),
                onClick = { onSelect(chapter) },
                trailing = {
                    if (chapter.id == current?.id) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = AppTheme.colors.accentText,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun AppearanceSheet(state: ReaderUiState, actions: ReaderActions, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(state.textScale) }
    AppBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.reader_appearance)) {
        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                stringResource(R.string.reader_mode),
                style = AppTheme.typography.footnote,
                color = AppTheme.colors.secondaryLabel
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ReaderMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { actions.onSetMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, ReaderMode.entries.size)
                    ) {
                        Text(
                            stringResource(
                                if (mode ==
                                    ReaderMode.PDF
                                ) {
                                    R.string.reader_mode_pdf
                                } else {
                                    R.string.reader_mode_text
                                }
                            )
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.reader_text_size),
                style = AppTheme.typography.footnote,
                color = AppTheme.colors.secondaryLabel
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", style = AppTheme.typography.footnote, color = AppTheme.colors.label)
                Slider(
                    value = scale,
                    onValueChange = { scale = it },
                    onValueChangeFinished = { actions.onSetTextScale(scale) },
                    valueRange = AppSettings.MIN_TEXT_SCALE..AppSettings.MAX_TEXT_SCALE,
                    modifier = Modifier.weight(1f).padding(horizontal = Spacing.s),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AppTheme.colors.accent)
                )
                Text("A", style = AppTheme.typography.title, color = AppTheme.colors.label)
            }
        }
    }
}

@Composable
private fun GoToPageDialog(pageCount: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val page = text.toIntOrNull()?.takeIf { it in 1..pageCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_go_to_page)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter(Char::isDigit).take(5) },
                placeholder = { Text("1–$pageCount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(enabled = page != null, onClick = {
                page?.let(onGo)
            }) { Text(stringResource(R.string.reader_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_cancel)) } }
    )
}

private val CONTROLS_HEIGHT = 140.dp

/** Lo que añaden la barra de la voz y la frase que se está leyendo. */
private val LISTENING_EXTRA_HEIGHT = ListeningBarHeight + 44.dp
private val SECTION_INDENT = 16.dp
private const val BOOKMARK_CHIP_MS = 6_000L
private const val PERCENT = 100
