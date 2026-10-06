package dev.joseramos.aireader.feature.reader.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.joseramos.aireader.ai.llm.KeyPointsJob
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.db.KeyPointsStatus
import dev.joseramos.aireader.core.designsystem.component.AppBottomSheet
import dev.joseramos.aireader.core.designsystem.component.CostConfirmDialog
import dev.joseramos.aireader.core.designsystem.component.PlainButton
import dev.joseramos.aireader.core.designsystem.component.PrimaryButton
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Radius
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.feature.reader.R

/**
 * «Ponerme al día»: el repaso «Hasta ahora…» hasta [page], escrito a partir de los hechos clave, y los
 * capítulos ya leídos con sus primeros hechos clave (al tocar uno se abren todos). Nada de lo que viene
 * después de [page] (docs/02-diseno-tecnico.md §6.5).
 */
@Composable
internal fun CatchUpSheet(
    page: Int,
    chapters: List<Chapter>,
    viewModel: KeyPointsViewModel,
    onOpenChapter: (Chapter) -> Unit,
    onAddApiKey: () -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recap = state.recap
    val job = viewModel.recapJob(state)
    val current = viewModel.recapIsCurrent(recap, chapters, page)
    val previous = chapters.filter { chapter ->
        chapter.endPage <
            (chapters.lastOrNull { page >= it.startPage }?.startPage ?: 1)
    }
    val hasSomething = page > 1
    LaunchedEffect(page) {
        val now = viewModel.state.value
        if (hasSomething &&
            viewModel.recapJob(now) !is KeyPointsJob.Running &&
            !viewModel.recapIsCurrent(now.recap, chapters, page)
        ) {
            viewModel.requestRecap(page)
        }
    }
    val confirmation by viewModel.costConfirmation.collectAsStateWithLifecycle()
    confirmation?.let { (_, cost) ->
        CostConfirmDialog(
            estimatedTokens = cost.estimatedTokens,
            remainingTokens = cost.remainingTokens,
            onConfirm = viewModel::confirmRecap,
            onDismiss = viewModel::cancelRecap
        )
    }
    val colors = AppTheme.colors
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.recap_title),
        skipPartiallyExpanded = false
    ) {
        Column(
            Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            if (!hasSomething) {
                Text(
                    stringResource(R.string.recap_nothing),
                    style = AppTheme.typography.subheadline,
                    color = colors.secondaryLabel
                )
                return@Column
            }
            Text(stringResource(R.string.recap_so_far), style = AppTheme.typography.headline, color = colors.label)
            when {
                job is KeyPointsJob.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    CircularProgressIndicator(
                        Modifier.padding(vertical = Spacing.xs),
                        color = colors.accent,
                        strokeWidth = 2.dp
                    )
                    Text(
                        stringResource(R.string.recap_generating),
                        style = AppTheme.typography.subheadline,
                        color = colors.secondaryLabel
                    )
                }
                job is KeyPointsJob.Failed -> {
                    Text(job.message, style = AppTheme.typography.subheadline, color = colors.destructive)
                    if (job.needsApiKey) {
                        PrimaryButton(
                            stringResource(R.string.ai_open_settings),
                            onAddApiKey,
                            Modifier.fillMaxWidth()
                        )
                    } else {
                        PrimaryButton(stringResource(R.string.ai_retry), {
                            viewModel.requestRecap(page)
                        }, Modifier.fillMaxWidth())
                    }
                }
                recap != null && current -> {
                    Text(recap.text, style = AppTheme.typography.body, color = colors.label)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.recap_until, recap.untilPage),
                            style = AppTheme.typography.footnote,
                            color = colors.secondaryLabel,
                            modifier = Modifier.weight(1f)
                        )
                        PlainButton(stringResource(R.string.ai_regenerate), {
                            viewModel.requestRecap(page)
                        }, icon = Icons.Outlined.Refresh)
                    }
                }
                // Sin repaso al día (por ejemplo, se canceló por el presupuesto): se puede pedir.
                else -> PrimaryButton(stringResource(R.string.recap_generate), {
                    viewModel.requestRecap(page)
                }, Modifier.fillMaxWidth())
            }
            if (previous.isNotEmpty()) {
                Text(
                    stringResource(R.string.recap_previous_chapters).uppercase(),
                    style = AppTheme.typography.footnote,
                    color = colors.secondaryLabel,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
                Column(Modifier.clip(RoundedCornerShape(Radius.cell)).background(colors.surface)) {
                    previous.forEachIndexed { i, chapter ->
                        val keyPoints = state.keyPoints[chapter.id]
                        val preview = when (keyPoints?.status) {
                            null -> stringResource(R.string.recap_chapter_pending)
                            KeyPointsStatus.REFUSED -> stringResource(R.string.key_points_refused)
                            KeyPointsStatus.EMPTY -> stringResource(R.string.key_points_empty)
                            KeyPointsStatus.READY -> keyPoints.points.joinToString("\n") { "• ${it.text}" }
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onOpenChapter(chapter) }
                                .padding(horizontal = Spacing.m, vertical = Spacing.s)
                        ) {
                            Text(chapter.title, style = AppTheme.typography.body, color = colors.label)
                            Text(
                                preview,
                                style = AppTheme.typography.footnote,
                                color = colors.secondaryLabel,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (i < previous.lastIndex) {
                            HorizontalDivider(
                                Modifier.padding(start = Spacing.m),
                                thickness = 0.5.dp,
                                color = colors.separator
                            )
                        }
                    }
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
