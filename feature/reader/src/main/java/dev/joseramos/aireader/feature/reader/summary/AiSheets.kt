package dev.joseramos.aireader.feature.reader.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.joseramos.aireader.ai.llm.SummaryJob
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.designsystem.component.AppBottomSheet
import dev.joseramos.aireader.core.designsystem.component.Cell
import dev.joseramos.aireader.core.designsystem.component.GroupedSection
import dev.joseramos.aireader.core.designsystem.component.GroupedSectionDefaults
import dev.joseramos.aireader.core.designsystem.component.PlainButton
import dev.joseramos.aireader.core.designsystem.component.PrimaryButton
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.feature.reader.R

/** Hoja ✦ del lector: las acciones de IA disponibles para el libro y el capítulo actual. */
@Composable
internal fun AiActionsSheet(
    currentChapter: Chapter?,
    currentPage: Int,
    onSummarizeChapter: (Chapter) -> Unit,
    onSummarizeBook: () -> Unit,
    onAsk: () -> Unit,
    onCatchUp: () -> Unit,
    onCharacters: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    AppBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.ai_title)) {
        GroupedSection(dividerInset = GroupedSectionDefaults.IconDividerInset) {
            if (currentChapter != null) {
                row {
                    Cell(
                        title = stringResource(R.string.ai_summarize_chapter),
                        subtitle = currentChapter.title,
                        icon = Icons.Outlined.AutoAwesome,
                        showChevron = true,
                        onClick = { onSummarizeChapter(currentChapter) }
                    )
                }
            }
            if (currentPage > 1) {
                row {
                    Cell(
                        title = stringResource(R.string.ai_catch_up),
                        subtitle = stringResource(R.string.ai_catch_up_subtitle, currentPage),
                        icon = Icons.Outlined.History,
                        iconBackground = Color(0xFF5E5CE6),
                        showChevron = true,
                        onClick = onCatchUp
                    )
                }
            }
            onCharacters?.let { open ->
                row {
                    Cell(
                        title = stringResource(R.string.ai_characters),
                        subtitle = stringResource(R.string.ai_characters_subtitle),
                        icon = Icons.Outlined.People,
                        iconBackground = Color(0xFFFF9500),
                        showChevron = true,
                        onClick = open
                    )
                }
            }
            row {
                Cell(
                    title = stringResource(R.string.ai_summarize_book),
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    iconBackground = Color(0xFF34C759),
                    showChevron = true,
                    onClick = onSummarizeBook
                )
            }
            row {
                Cell(
                    title = stringResource(R.string.ai_ask),
                    subtitle = stringResource(R.string.ai_ask_subtitle),
                    icon = Icons.Outlined.ChatBubbleOutline,
                    iconBackground = Color(0xFF30B0C7),
                    showChevron = true,
                    onClick = onAsk
                )
            }
        }
    }
}

/**
 * Resumen de un capítulo o del libro. Si no existe, se genera al abrir la hoja. Los errores se
 * explican con una acción (por ejemplo, ir a Ajustes si falta la clave).
 */
@Composable
internal fun SummarySheet(
    target: SummaryTarget,
    bookTitle: String,
    viewModel: SummaryViewModel,
    onChangeTarget: (SummaryTarget) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val summary = viewModel.summaryFor(state, target)
    val job = viewModel.jobFor(state, target)
    LaunchedEffect(target) {
        if (viewModel.summaryFor(viewModel.state.value, target) == null &&
            viewModel.jobFor(viewModel.state.value, target) == null
        ) {
            viewModel.generate(target)
        }
    }
    val colors = AppTheme.colors
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = target.chapter?.title ?: bookTitle,
        skipPartiallyExpanded = false
    ) {
        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (target.chapter != null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false, true).forEachIndexed { i, detailed ->
                        SegmentedButton(
                            selected = target.detailed == detailed,
                            onClick = { onChangeTarget(target.copy(detailed = detailed)) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2)
                        ) { Text(stringResource(if (detailed) R.string.ai_detailed else R.string.ai_brief)) }
                    }
                }
            }
            when {
                job is SummaryJob.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    CircularProgressIndicator(
                        Modifier.padding(vertical = Spacing.m),
                        color = colors.accent,
                        strokeWidth = 2.dp
                    )
                    Text(
                        stringResource(R.string.ai_generating),
                        style = AppTheme.typography.subheadline,
                        color = colors.secondaryLabel
                    )
                }
                job is SummaryJob.Failed -> {
                    Text(job.message, style = AppTheme.typography.subheadline, color = colors.destructive)
                    if (job.needsApiKey) {
                        PrimaryButton(
                            stringResource(R.string.ai_open_settings),
                            onOpenSettings,
                            Modifier.fillMaxWidth()
                        )
                    } else {
                        PrimaryButton(stringResource(R.string.ai_retry), {
                            viewModel.generate(target)
                        }, Modifier.fillMaxWidth())
                    }
                }
                summary != null -> {
                    Text(
                        summary.text,
                        style = AppTheme.typography.body,
                        color = colors.label,
                        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())
                    )
                    PlainButton(stringResource(R.string.ai_regenerate), {
                        viewModel.generate(target)
                    }, icon = Icons.Outlined.Refresh)
                }
            }
            Text(
                stringResource(R.string.ai_disclaimer),
                style = AppTheme.typography.caption,
                color = colors.tertiaryLabel
            )
        }
    }
}
