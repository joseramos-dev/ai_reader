package dev.joseramos.aireader.feature.reader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.joseramos.aireader.feature.characters.CharacterSheet
import dev.joseramos.aireader.feature.reader.summary.AiActionsSheet
import dev.joseramos.aireader.feature.reader.summary.CatchUpSheet
import dev.joseramos.aireader.feature.reader.summary.SummarySheet
import dev.joseramos.aireader.feature.reader.summary.SummaryTarget
import dev.joseramos.aireader.feature.reader.summary.SummaryViewModel
import kotlinx.serialization.Serializable

/** Lector de un libro. [page] (base 1) abre en esa página, por ejemplo al tocar una cita del chat. */
@Serializable
data class ReaderRoute(val bookId: String, val page: Int? = null)

fun NavGraphBuilder.readerScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCharacters: (String) -> Unit
) {
    composable<ReaderRoute>(
        enterTransition = { slideIntoContainer(SlideDirection.Start) },
        popExitTransition = { slideOutOfContainer(SlideDirection.End) }
    ) {
        ReaderDestination(onBack, onOpenChat, onOpenSettings, onOpenCharacters)
    }
}

@Composable
private fun ReaderDestination(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCharacters: (String) -> Unit
) {
    val viewModel: ReaderViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val extras by viewModel.extras.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val summaryViewModel: SummaryViewModel = hiltViewModel()
    var aiSheet by remember { mutableStateOf(false) }
    var summaryTarget by remember { mutableStateOf<SummaryTarget?>(null) }
    var catchUp by remember { mutableStateOf(false) }
    var characterId by remember { mutableStateOf<Long?>(null) }
    val literature = state.book?.isLiterature == true
    val openCharacters = { onOpenCharacters(viewModel.bookId) }

    // Media3 necesita el permiso de notificaciones (Android 13+) para mostrar los controles.
    var pendingListenPage by remember { mutableStateOf<Int?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingListenPage?.let(viewModel::listen)
        pendingListenPage = null
    }
    val listen: (Int) -> Unit = { page ->
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.listen(page)
        } else {
            pendingListenPage = page
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    ReaderScreen(
        state = state,
        playback = playback,
        render = viewModel::render,
        actions = ReaderActions(
            onBack = onBack,
            onPageVisible = viewModel::onPageVisible,
            onSetMode = viewModel::setMode,
            onSetTextScale = viewModel::setTextScale,
            onListen = listen,
            onPause = viewModel::pause,
            onListenFrom = viewModel::listenFrom,
            onOpenAi = { aiSheet = true },
            onChapterSummary = { summaryTarget = SummaryTarget(it) },
            onToggleBookmark = viewModel::toggleBookmark,
            onBookmarkNote = viewModel::setBookmarkNote,
            onDeleteBookmark = viewModel::deleteBookmark,
            onDismissBookmarkSuggestion = viewModel::dismissBookmarkSuggestion,
            onOpenCharacters = openCharacters.takeIf { literature },
            onOpenCharacter = { characterId = it },
            onRecap = {
                viewModel.dismissRecapOffer()
                catchUp = true
            },
            onDismissRecap = viewModel::dismissRecapOffer
        ),
        extras = extras,
        jumpRequests = viewModel.jumpRequests
    )

    if (aiSheet) {
        AiActionsSheet(
            currentChapter = state.currentChapter,
            currentPage = state.currentPage,
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
            onSummarizeChapter = {
                aiSheet = false
                summaryTarget = SummaryTarget(it)
            },
            onSummarizeBook = {
                aiSheet = false
                summaryTarget = SummaryTarget(chapter = null)
            },
            onAsk = {
                aiSheet = false
                onOpenChat(viewModel.bookId)
            },
            onDismiss = { aiSheet = false }
        )
    }
    if (catchUp) {
        CatchUpSheet(
            page = state.currentPage,
            chapters = state.chapters,
            viewModel = summaryViewModel,
            onOpenChapter = {
                catchUp = false
                summaryTarget = SummaryTarget(it)
            },
            onOpenSettings = {
                catchUp = false
                onOpenSettings()
            },
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
    summaryTarget?.let { target ->
        SummarySheet(
            target = target,
            bookTitle = state.book?.title.orEmpty(),
            viewModel = summaryViewModel,
            onChangeTarget = { summaryTarget = it },
            onOpenSettings = {
                summaryTarget = null
                onOpenSettings()
            },
            onDismiss = { summaryTarget = null }
        )
    }

    playback.error?.let { error ->
        PlaybackProblemSheet(
            error = error,
            voice = voice,
            onDownloadVoice = viewModel::downloadVoice,
            onVoiceReady = {
                viewModel.dismissPlaybackError()
                viewModel.listen(state.currentPage)
            },
            onDismiss = viewModel::dismissPlaybackError
        )
    }
}
