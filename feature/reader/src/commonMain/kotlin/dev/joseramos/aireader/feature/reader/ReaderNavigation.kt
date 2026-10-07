package dev.joseramos.aireader.feature.reader

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.designsystem.component.ApiKeySheet
import dev.joseramos.aireader.core.designsystem.platform.rememberWithNotificationPermission
import dev.joseramos.aireader.feature.characters.CharacterSheet
import dev.joseramos.aireader.feature.reader.summary.AiActionsSheet
import dev.joseramos.aireader.feature.reader.summary.CatchUpSheet
import dev.joseramos.aireader.feature.reader.summary.KeyPointsSheet
import dev.joseramos.aireader.feature.reader.summary.KeyPointsViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel

/**
 * Lector de un libro. [page] (base 1) abre en esa página, por ejemplo al tocar una cita del chat. Con
 * [sourceMessageId] y [sourceNumber] (una fuente de una respuesta del chat), resalta además ese pasaje.
 */
@Serializable
data class ReaderRoute(
    val bookId: String,
    val page: Int? = null,
    val sourceMessageId: Long? = null,
    val sourceNumber: Int? = null
)

fun NavGraphBuilder.readerScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit, onOpenCharacters: (String) -> Unit) {
    composable<ReaderRoute>(
        enterTransition = { slideIntoContainer(SlideDirection.Start) },
        popExitTransition = { slideOutOfContainer(SlideDirection.End) }
    ) {
        ReaderDestination(onBack, onOpenChat, onOpenCharacters)
    }
}

@Composable
private fun ReaderDestination(onBack: () -> Unit, onOpenChat: (String) -> Unit, onOpenCharacters: (String) -> Unit) {
    val viewModel: ReaderViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val extras by viewModel.extras.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val keyPointsViewModel: KeyPointsViewModel = koinViewModel()
    var aiSheet by remember { mutableStateOf(false) }
    val aiUsage by keyPointsViewModel.today.collectAsStateWithLifecycle()
    val hasApiKey by keyPointsViewModel.hasApiKey.collectAsStateWithLifecycle()

    // Clave de API pedida desde una hoja de IA: al guardarla se reintenta lo que se estaba haciendo.
    var retryAfterKey by remember { mutableStateOf<(() -> Unit)?>(null) }
    var keyPointsChapter by remember { mutableStateOf<Chapter?>(null) }
    var catchUp by remember { mutableStateOf(false) }
    var characterId by remember { mutableStateOf<Long?>(null) }
    val literature = state.book?.isLiterature == true
    val openCharacters = remember(viewModel, onOpenCharacters) { { onOpenCharacters(viewModel.bookId) } }

    // Media3 necesita el permiso de notificaciones (Android 13+) para mostrar los controles.
    val listen = rememberWithNotificationPermission<Int>(viewModel::listen)

    // ReaderActions no es un `data class`: sin recordarla, cada recomposición crea una instancia
    // nueva (con lambdas nuevas) y ningún hijo que la reciba puede saltarse su recomposición, aunque
    // nada relevante haya cambiado. Las claves son las variables que de verdad hacen falta dentro.
    val actions = remember(viewModel, onBack, listen, openCharacters, literature) {
        ReaderActions(
            onBack = onBack,
            onPageVisible = viewModel::onPageVisible,
            onSetMode = viewModel::setMode,
            onSetTextScale = viewModel::setTextScale,
            onListen = listen,
            listening = viewModel.listening,
            onListenFrom = viewModel::listenFrom,
            onOpenAi = { aiSheet = true },
            onToggleBookmark = viewModel::toggleBookmark,
            onBookmarkNote = viewModel::setBookmarkNote,
            onDeleteBookmark = viewModel::deleteBookmark,
            onDismissBookmarkSuggestion = viewModel::dismissBookmarkSuggestion,
            onAddHighlight = viewModel::addHighlight,
            onEditHighlightNote = viewModel::setHighlightNote,
            onDeleteHighlight = viewModel::deleteHighlight,
            onOpenCharacters = openCharacters.takeIf { literature },
            onOpenCharacter = { characterId = it },
            onRecap = {
                viewModel.dismissRecapOffer()
                catchUp = true
            },
            onDismissRecap = viewModel::dismissRecapOffer
        )
    }

    ReaderScreen(
        state = state,
        playback = playback,
        render = viewModel::render,
        actions = actions,
        extras = extras,
        jumpRequests = viewModel.jumpRequests
    )

    if (aiSheet) {
        AiActionsSheet(
            currentPage = state.currentPage,
            hasApiKey = hasApiKey,
            usage = aiUsage,
            onDismissBudgetAlert = keyPointsViewModel::dismissBudgetAlert,
            onCatchUp = {
                aiSheet = false
                catchUp = true
            },
            onCharacters = if (literature) {
                {
                    aiSheet = false
                    openCharacters()
                }
            } else {
                null
            },
            onAsk = {
                aiSheet = false
                onOpenChat(viewModel.bookId)
            },
            // Al guardar la clave se vuelve a abrir la hoja, ya desbloqueada.
            onAddApiKey = {
                aiSheet = false
                retryAfterKey = { aiSheet = true }
            },
            onDismiss = { aiSheet = false }
        )
    }
    if (catchUp) {
        CatchUpSheet(
            page = state.currentPage,
            chapters = state.chapters,
            viewModel = keyPointsViewModel,
            onOpenChapter = {
                catchUp = false
                keyPointsChapter = it
            },
            onAddApiKey = { retryAfterKey = { keyPointsViewModel.requestRecap(state.currentPage) } },
            onDismiss = { catchUp = false }
        )
    }
    characterId?.let { extras.characters.character(it) }?.let { character ->
        CharacterSheet(
            character = character,
            snapshot = extras.characters,
            onOpenPage = { page ->
                characterId = null
                viewModel.jumpTo(page)
            },
            onOpenCharacter = { characterId = it },
            onDismiss = { characterId = null }
        )
    }
    keyPointsChapter?.let { chapter ->
        KeyPointsSheet(
            chapter = chapter,
            literature = literature,
            viewModel = keyPointsViewModel,
            onOpenPage = { page ->
                keyPointsChapter = null
                viewModel.jumpTo(page)
            },
            onAddApiKey = { retryAfterKey = { keyPointsViewModel.generate(chapter) } },
            onDismiss = { keyPointsChapter = null }
        )
    }

    retryAfterKey?.let { retry ->
        ApiKeySheet(
            onSave = { key -> keyPointsViewModel.saveApiKey(key, retry) },
            onDismiss = { retryAfterKey = null }
        )
    }

    playback.error?.let { error ->
        PlaybackProblemSheet(
            error = error,
            language = playback.language,
            onRetry = {
                viewModel.dismissPlaybackError()
                viewModel.listen(state.currentPage)
            },
            onDismiss = viewModel::dismissPlaybackError
        )
    }
}
