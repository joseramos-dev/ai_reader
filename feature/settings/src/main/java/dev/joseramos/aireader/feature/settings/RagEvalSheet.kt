package dev.joseramos.aireader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.joseramos.aireader.ai.rag.EvalReport
import dev.joseramos.aireader.ai.rag.ModeScore
import dev.joseramos.aireader.ai.rag.RagScoring
import dev.joseramos.aireader.core.designsystem.component.AppBottomSheet
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing

/**
 * Resultado de la evaluación de la búsqueda: recall@k y MRR de la búsqueda vectorial, la de texto
 * completo y la fusión, y las preguntas que la fusión no resuelve entre los 8 primeros.
 */
@Composable
internal fun RagEvalSheet(state: RagEvalState, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_rag_eval),
        skipPartiallyExpanded = false
    ) {
        Column(
            Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            when (state) {
                RagEvalState.Running -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
                    Text(stringResource(R.string.settings_rag_eval_running), color = colors.secondaryLabel)
                }
                is RagEvalState.Failed -> Text(state.message, color = colors.destructive)
                is RagEvalState.Done -> Report(state.report)
            }
        }
    }
}

@Composable
private fun Report(report: EvalReport) {
    val colors = AppTheme.colors
    val mono = AppTheme.typography.footnote.copy(fontFamily = FontFamily.Monospace)
    Text(
        stringResource(R.string.settings_rag_eval_questions, report.results.size),
        style = AppTheme.typography.subheadline,
        color = colors.label
    )
    Text(
        "         " + RagScoring.KS.joinToString("") { "R@$it".padStart(COLUMN) } + "MRR".padStart(COLUMN),
        style = mono,
        color = colors.secondaryLabel
    )
    ScoreRow(stringResource(R.string.settings_rag_eval_vector), report.vector)
    ScoreRow(stringResource(R.string.settings_rag_eval_text), report.text)
    ScoreRow(stringResource(R.string.settings_rag_eval_hybrid), report.hybrid)
    if (report.missingBooks.isNotEmpty()) {
        Text(
            stringResource(R.string.settings_rag_eval_missing, report.missingBooks.joinToString(", ")),
            style = AppTheme.typography.footnote,
            color = colors.destructive
        )
    }
    val misses = report.results.filter { (it.hybridRank ?: Int.MAX_VALUE) > MISS_AFTER }
    if (misses.isNotEmpty()) {
        Text(
            stringResource(R.string.settings_rag_eval_misses).uppercase(),
            style = AppTheme.typography.footnote,
            color = colors.secondaryLabel,
            modifier = Modifier.padding(top = Spacing.s)
        )
        misses.forEach { miss ->
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xxs)) {
                Text(miss.question, style = AppTheme.typography.subheadline, color = colors.label)
                Text(
                    stringResource(
                        R.string.settings_rag_eval_miss_detail,
                        miss.book,
                        miss.pages.joinToString(", "),
                        rankLabel(miss.vectorRank),
                        rankLabel(miss.textRank),
                        rankLabel(miss.hybridRank)
                    ),
                    style = AppTheme.typography.footnote,
                    color = colors.secondaryLabel
                )
            }
        }
    }
}

@Composable
private fun ScoreRow(label: String, score: ModeScore) {
    Text(
        label.take(LABEL).padEnd(LABEL) +
            RagScoring.KS.joinToString("") { "%.0f%%".format((score.recallAt[it] ?: 0.0) * PERCENT).padStart(COLUMN) } +
            "%.2f".format(score.mrr).padStart(COLUMN),
        style = AppTheme.typography.footnote.copy(fontFamily = FontFamily.Monospace),
        color = AppTheme.colors.label
    )
}

private fun rankLabel(rank: Int?) = rank?.toString() ?: "—"

private const val LABEL = 9
private const val COLUMN = 6
private const val PERCENT = 100
private const val MISS_AFTER = 8
