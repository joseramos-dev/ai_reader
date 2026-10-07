package dev.joseramos.aireader.feature.characters

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.core.designsystem.component.ApiKeySheet
import dev.joseramos.aireader.core.designsystem.component.BarIconButton
import dev.joseramos.aireader.core.designsystem.component.Cell
import dev.joseramos.aireader.core.designsystem.component.CostConfirmDialog
import dev.joseramos.aireader.core.designsystem.component.EmptyState
import dev.joseramos.aireader.core.designsystem.component.GroupedSection
import dev.joseramos.aireader.core.designsystem.component.LargeTitleScaffold
import dev.joseramos.aireader.core.designsystem.component.PrimaryButton
import dev.joseramos.aireader.core.designsystem.component.SecondaryButton
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel

/** Menú de personajes desbloqueados de un libro. */
@Serializable
data class CharactersRoute(val bookId: String)

/** Grafo de relaciones entre los personajes desbloqueados. */
@Serializable
data class RelationsGraphRoute(val bookId: String)

fun NavGraphBuilder.charactersScreens(
    onBack: () -> Unit,
    onOpenPage: (bookId: String, page: Int) -> Unit,
    onOpenGraph: (bookId: String) -> Unit
) {
    composable<CharactersRoute>(
        enterTransition = { slideIntoContainer(SlideDirection.Start) },
        popExitTransition = { slideOutOfContainer(SlideDirection.End) }
    ) {
        val viewModel: CharactersViewModel = koinViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        var askKey by rememberSaveable { mutableStateOf(false) }
        CharactersScreen(
            state = state,
            onBack = onBack,
            onOpenPage = { page -> state.book?.let { onOpenPage(it.id, page) } },
            onOpenGraph = { state.book?.let { onOpenGraph(it.id) } },
            onOpenSettings = { askKey = true },
            onSort = viewModel::setSort,
            onAnalyze = viewModel::analyze
        )
        if (askKey) ApiKeySheet(onSave = viewModel::saveApiKeyAndAnalyze, onDismiss = { askKey = false })
        val confirmation by viewModel.costConfirmation.collectAsStateWithLifecycle()
        confirmation?.let {
            CostConfirmDialog(
                estimatedTokens = it.estimatedTokens,
                remainingTokens = it.remainingTokens,
                onConfirm = viewModel::confirmAnalysis,
                onDismiss = viewModel::cancelAnalysis
            )
        }
    }
    composable<RelationsGraphRoute>(
        enterTransition = { slideIntoContainer(SlideDirection.Start) },
        popExitTransition = { slideOutOfContainer(SlideDirection.End) }
    ) {
        val viewModel: RelationsGraphViewModel = koinViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        RelationsGraphScreen(
            state = state,
            onBack = onBack,
            onOpenPage = { page -> onOpenPage(viewModel.bookId, page) },
            onSetChapterLimit = viewModel::setChapterLimit,
            onSetHideMinor = viewModel::setHideMinor
        )
    }
}

@Composable
private fun CharactersScreen(
    state: CharactersUiState,
    onBack: () -> Unit,
    onOpenPage: (Int) -> Unit,
    onOpenGraph: () -> Unit,
    onOpenSettings: () -> Unit,
    onSort: (CharacterSort) -> Unit,
    onAnalyze: () -> Unit
) {
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val book = state.book

    LargeTitleScaffold(
        title = stringResource(R.string.characters_title),
        grouped = true,
        navigationAction = {
            BarIconButton(Icons.AutoMirrored.Rounded.ArrowBackIos, stringResource(R.string.characters_back), onBack)
        },
        actions = {
            if (state.snapshot.relations.isNotEmpty()) {
                BarIconButton(Icons.Outlined.Hub, stringResource(R.string.characters_graph), onOpenGraph)
            }
        }
    ) {
        if (state.loading || book == null) return@LargeTitleScaffold
        if (!book.isLiterature) {
            item(key = "not-literature") {
                EmptyState(
                    icon = Icons.Outlined.People,
                    title = stringResource(R.string.characters_not_literature_title),
                    message = stringResource(R.string.characters_not_literature_message),
                    modifier = Modifier.padding(top = 64.dp)
                )
            }
            return@LargeTitleScaffold
        }
        item(key = "status") {
            AnalysisStatus(state, book.indexStatus, onAnalyze, onOpenSettings)
        }
        if (state.snapshot.characters.isEmpty()) {
            if (state.progress.scanned > 0 || state.progress.running) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Outlined.People,
                        title = stringResource(R.string.characters_empty_title),
                        message = stringResource(R.string.characters_empty_message),
                        modifier = Modifier.padding(top = 48.dp)
                    )
                }
            }
            return@LargeTitleScaffold
        }
        item(key = "sort") {
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs)
            ) {
                CharacterSort.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = state.sort == mode,
                        onClick = { onSort(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, CharacterSort.entries.size)
                    ) {
                        Text(
                            stringResource(
                                if (mode == CharacterSort.APPEARANCE) {
                                    R.string.characters_sort_appearance
                                } else {
                                    R.string.characters_sort_importance
                                }
                            )
                        )
                    }
                }
            }
        }
        item(key = "list") {
            GroupedSection {
                state.characters.forEach { character ->
                    row {
                        Cell(
                            title = character.name,
                            subtitle = character.otherNames.takeIf { it.isNotEmpty() }
                                ?.joinToString(" · ") { it.name }
                                ?: stringResource(R.string.characters_first_page, character.firstPage),
                            value = character.mentions.takeIf { it > 0 }?.let {
                                stringResource(R.string.characters_mentions, it)
                            },
                            showChevron = true,
                            onClick = { selectedId = character.id }
                        )
                    }
                }
            }
        }
    }

    selectedId?.let { state.snapshot.character(it) }?.let { character ->
        CharacterSheet(
            character = character,
            snapshot = state.snapshot,
            onOpenPage = { page ->
                selectedId = null
                onOpenPage(page)
            },
            onOpenCharacter = { selectedId = it },
            onDismiss = { selectedId = null }
        )
    }
}

@Composable
private fun AnalysisStatus(
    state: CharactersUiState,
    indexStatus: IndexStatus,
    onAnalyze: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = AppTheme.colors
    val progress = state.progress
    if (progress.complete) return
    val indexed = indexStatus in setOf(IndexStatus.READY, IndexStatus.TEXT_READY, IndexStatus.EMBEDDING)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        val footnote = AppTheme.typography.footnote
        when {
            !indexed -> Text(
                stringResource(R.string.characters_book_not_ready),
                style = footnote,
                color = colors.secondaryLabel
            )
            progress.running -> {
                Text(
                    stringResource(
                        R.string.characters_analyzing,
                        (progress.scanned + 1).coerceAtMost(progress.total),
                        progress.total
                    ),
                    style = footnote,
                    color = colors.secondaryLabel
                )
                LinearProgressIndicator(
                    progress = { if (progress.total > 0) progress.scanned.toFloat() / progress.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.fill,
                    drawStopIndicator = {}
                )
            }
            !state.hasApiKey -> {
                Text(stringResource(R.string.characters_no_key), style = footnote, color = colors.secondaryLabel)
                SecondaryButton(
                    stringResource(R.string.characters_open_settings),
                    onOpenSettings,
                    Modifier.fillMaxWidth()
                )
            }
            progress.needsApiKey -> {
                Text(stringResource(R.string.characters_invalid_key), style = footnote, color = colors.destructive)
                SecondaryButton(
                    stringResource(R.string.characters_open_settings),
                    onOpenSettings,
                    Modifier.fillMaxWidth()
                )
            }
            progress.failed -> {
                Text(stringResource(R.string.characters_failed), style = footnote, color = colors.destructive)
                SecondaryButton(stringResource(R.string.characters_retry), onAnalyze, Modifier.fillMaxWidth())
            }
            else -> {
                Text(stringResource(R.string.characters_analyze_hint), style = footnote, color = colors.secondaryLabel)
                PrimaryButton(stringResource(R.string.characters_analyze), onAnalyze, Modifier.fillMaxWidth())
            }
        }
    }
}
