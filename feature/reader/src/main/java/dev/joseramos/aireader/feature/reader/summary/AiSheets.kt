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
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.joseramos.aireader.ai.llm.KeyPointsJob
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.KeyPoint
import dev.joseramos.aireader.core.data.db.KeyPointsStatus
import dev.joseramos.aireader.core.data.settings.BudgetLevel
import dev.joseramos.aireader.core.data.settings.DailyUsage
import dev.joseramos.aireader.core.designsystem.component.AiUsageRow
import dev.joseramos.aireader.core.designsystem.component.AppBottomSheet
import dev.joseramos.aireader.core.designsystem.component.BudgetAlertBanner
import dev.joseramos.aireader.core.designsystem.component.BudgetAlertKind
import dev.joseramos.aireader.core.designsystem.component.Cell
import dev.joseramos.aireader.core.designsystem.component.GroupedSection
import dev.joseramos.aireader.core.designsystem.component.GroupedSectionDefaults
import dev.joseramos.aireader.core.designsystem.component.PlainButton
import dev.joseramos.aireader.core.designsystem.component.PrimaryButton
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.feature.reader.R

/**
 * Hoja ✦ del lector: las acciones de IA disponibles para el libro. Los resúmenes (de un capítulo o
 * del libro) se piden en «Pregunta al libro», que los escribe a partir de los hechos clave. Sin clave
 * de API las acciones se muestran bloqueadas, con un aviso para introducirla ([onAddApiKey]).
 */
@Composable
internal fun AiActionsSheet(
    currentPage: Int,
    hasApiKey: Boolean,
    onAsk: () -> Unit,
    onCatchUp: () -> Unit,
    onCharacters: (() -> Unit)?,
    onAddApiKey: () -> Unit,
    usage: DailyUsage,
    onDismissBudgetAlert: (BudgetLevel) -> Unit,
    onDismiss: () -> Unit
) {
    val locked = !hasApiKey
    val lockedModifier = if (locked) Modifier.alpha(LOCKED_ALPHA) else Modifier
    AppBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.ai_title)) {
        if (locked) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                Text(
                    stringResource(R.string.ai_locked_message),
                    style = AppTheme.typography.subheadline,
                    color = AppTheme.colors.secondaryLabel
                )
                PrimaryButton(stringResource(R.string.ai_open_settings), onAddApiKey, Modifier.fillMaxWidth())
            }
        } else {
            AiUsageRow(
                remaining = usage.remaining,
                usedTokens = usage.tokens,
                budgetTokens = usage.budget,
                exhausted = usage.exhausted,
                resetsAt = usage.resetsAt,
                modifier = Modifier.padding(horizontal = Spacing.xs),
                overBudget = usage.level == BudgetLevel.OVER
            )
            usage.alert?.let { level ->
                BudgetAlertBanner(
                    kind = budgetAlertKind(usage, level),
                    usedTokens = usage.tokens,
                    budgetTokens = usage.budget,
                    resetsAt = usage.resetsAt,
                    onDismiss = { onDismissBudgetAlert(level) },
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs)
                )
            }
        }
        GroupedSection(dividerInset = GroupedSectionDefaults.IconDividerInset) {
            if (currentPage > 1) {
                row {
                    Cell(
                        title = stringResource(R.string.ai_catch_up),
                        subtitle = stringResource(R.string.ai_catch_up_subtitle, currentPage),
                        icon = Icons.Outlined.History,
                        iconBackground = Color(0xFF5E5CE6),
                        showChevron = !locked,
                        onClick = onCatchUp.takeUnless { locked },
                        modifier = lockedModifier
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
                        showChevron = !locked,
                        onClick = open.takeUnless { locked },
                        modifier = lockedModifier
                    )
                }
            }
            row {
                Cell(
                    title = stringResource(R.string.ai_ask),
                    subtitle = stringResource(R.string.ai_ask_subtitle),
                    icon = Icons.Outlined.ChatBubbleOutline,
                    iconBackground = Color(0xFF30B0C7),
                    showChevron = !locked,
                    onClick = onAsk.takeUnless { locked },
                    modifier = lockedModifier
                )
            }
        }
    }
}

/** Qué aviso de consumo corresponde a [level]: si Gemini agotó la cuota, eso; si no, el del presupuesto. */
internal fun budgetAlertKind(usage: DailyUsage, level: BudgetLevel): BudgetAlertKind = when {
    usage.exhausted -> BudgetAlertKind.EXHAUSTED
    level == BudgetLevel.OVER -> BudgetAlertKind.OVER
    else -> BudgetAlertKind.NEAR
}

/** Opacidad de las acciones de IA mientras no hay clave de API. */
private const val LOCKED_ALPHA = 0.4f

/**
 * Hechos clave de un capítulo (ideas clave si no es una novela: [literature]), que se abre desde
 * «Ponerme al día»: frases esquemáticas, cada una con su página, que se abre al tocarla ([onOpenPage]).
 * Si aún no los tiene, se generan al abrir la hoja. Los errores se explican con una acción (por ejemplo,
 * ir a Ajustes si falta la clave).
 */
@Composable
internal fun KeyPointsSheet(
    chapter: Chapter,
    literature: Boolean,
    viewModel: KeyPointsViewModel,
    onOpenPage: (Int) -> Unit,
    onAddApiKey: () -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyPoints = state.keyPoints[chapter.id]
    val job = viewModel.jobFor(state, chapter)
    // Si faltan, se generan al abrir; también si un intento anterior falló (por ejemplo, por falta de
    // clave), para que no se quede mostrando un error ya resuelto.
    LaunchedEffect(chapter.id) {
        val now = viewModel.state.value
        if (now.keyPoints[chapter.id] == null && viewModel.jobFor(now, chapter) !is KeyPointsJob.Running) {
            viewModel.generate(chapter)
        }
    }
    val colors = AppTheme.colors
    AppBottomSheet(onDismissRequest = onDismiss, title = chapter.title, skipPartiallyExpanded = false) {
        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                stringResource(if (literature) R.string.key_points_events else R.string.key_points_ideas).uppercase(),
                style = AppTheme.typography.footnote,
                color = colors.secondaryLabel
            )
            when {
                job is KeyPointsJob.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    CircularProgressIndicator(
                        Modifier.padding(vertical = Spacing.m),
                        color = colors.accent,
                        strokeWidth = 2.dp
                    )
                    Text(
                        stringResource(
                            if (literature) {
                                R.string.key_points_generating_events
                            } else {
                                R.string.key_points_generating_ideas
                            }
                        ),
                        style = AppTheme.typography.subheadline,
                        color = colors.secondaryLabel
                    )
                }
                job is KeyPointsJob.Failed -> {
                    Text(job.message, style = AppTheme.typography.subheadline, color = colors.destructive)
                    if (job.needsApiKey) {
                        PrimaryButton(stringResource(R.string.ai_open_settings), onAddApiKey, Modifier.fillMaxWidth())
                    } else {
                        PrimaryButton(stringResource(R.string.ai_retry), {
                            viewModel.generate(chapter)
                        }, Modifier.fillMaxWidth())
                    }
                }
                keyPoints != null -> {
                    when (keyPoints.status) {
                        KeyPointsStatus.READY -> KeyPointsList(keyPoints.points, onOpenPage)
                        KeyPointsStatus.REFUSED -> Notice(stringResource(R.string.key_points_refused))
                        KeyPointsStatus.EMPTY -> Notice(stringResource(R.string.key_points_empty))
                    }
                    PlainButton(stringResource(R.string.ai_regenerate), {
                        viewModel.generate(chapter, replace = true)
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

/** Los puntos, uno por fila: la página (que abre el libro en ella) y la frase. */
@Composable
private fun KeyPointsList(points: List<KeyPoint>, onOpenPage: (Int) -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        points.forEach { point ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                val openPage = stringResource(R.string.key_points_open_page, point.page)
                Text(
                    stringResource(R.string.key_points_page, point.page),
                    style = AppTheme.typography.footnote,
                    color = colors.accentText,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.accentFill)
                        .clickable(role = Role.Button, onClickLabel = openPage) { onOpenPage(point.page) }
                        .padding(horizontal = Spacing.xs, vertical = 2.dp)
                )
                Text(point.text, style = AppTheme.typography.body, color = colors.label, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = AppTheme.typography.subheadline, color = AppTheme.colors.secondaryLabel)
}
