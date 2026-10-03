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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.joseramos.aireader.ai.llm.SummaryJob
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
 * del libro) se piden en «Pregunta al libro». Sin clave de API las acciones se muestran bloqueadas,
 * con un aviso para introducirla ([onAddApiKey]).
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
 * Resumen de un capítulo, que se abre desde «Ponerme al día». Si no existe, se genera al abrir la hoja. Los errores se
 * explican con una acción (por ejemplo, ir a Ajustes si falta la clave).
 */
@Composable
internal fun SummarySheet(
    target: SummaryTarget,
    viewModel: SummaryViewModel,
    onChangeTarget: (SummaryTarget) -> Unit,
    onAddApiKey: () -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val summary = viewModel.summaryFor(state, target)
    val job = viewModel.jobFor(state, target)
    // Si no hay resumen, se genera al abrir; también si un intento anterior falló (por ejemplo, por
    // falta de clave), para que no se quede mostrando un error ya resuelto.
    LaunchedEffect(target) {
        if (viewModel.summaryFor(viewModel.state.value, target) == null &&
            viewModel.jobFor(viewModel.state.value, target) !is SummaryJob.Running
        ) {
            viewModel.generate(target)
        }
    }
    val colors = AppTheme.colors
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = target.chapter.title,
        skipPartiallyExpanded = false
    ) {
        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false, true).forEachIndexed { i, detailed ->
                    SegmentedButton(
                        selected = target.detailed == detailed,
                        onClick = { onChangeTarget(target.copy(detailed = detailed)) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2)
                    ) { Text(stringResource(if (detailed) R.string.ai_detailed else R.string.ai_brief)) }
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
                            onAddApiKey,
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
