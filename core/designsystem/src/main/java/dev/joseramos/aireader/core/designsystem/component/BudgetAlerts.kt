package dev.joseramos.aireader.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.joseramos.aireader.core.designsystem.R
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import java.text.DateFormat
import java.util.Date

/** Gravedad del aviso de consumo: cerca del presupuesto, superado, o cuota de Gemini agotada. */
enum class BudgetAlertKind { NEAR, OVER, EXHAUSTED }

/**
 * Aviso de consumo de IA que se cierra con «Entendido». Solo informa: la IA sigue funcionando
 * (salvo [BudgetAlertKind.EXHAUSTED], que es Gemini quien no admite más peticiones hoy).
 */
@Composable
fun BudgetAlertBanner(
    kind: BudgetAlertKind,
    usedTokens: Long,
    budgetTokens: Long,
    resetsAt: Long,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    val resetTime = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(resetsAt))
    val text = when (kind) {
        BudgetAlertKind.NEAR -> stringResource(
            R.string.budget_alert_near,
            formatTokens(usedTokens),
            formatTokens(budgetTokens)
        )
        BudgetAlertKind.OVER -> stringResource(
            R.string.budget_alert_over,
            formatTokens(usedTokens),
            formatTokens(budgetTokens),
            resetTime
        )
        BudgetAlertKind.EXHAUSTED -> stringResource(R.string.budget_alert_exhausted, resetTime)
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.fill, RoundedCornerShape(12.dp))
            .padding(start = Spacing.m, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        Text(
            text,
            style = AppTheme.typography.footnote,
            color = if (kind == BudgetAlertKind.NEAR) colors.label else colors.destructive,
            modifier = Modifier.weight(1f)
        )
        PlainButton(stringResource(R.string.budget_alert_dismiss), onDismiss)
    }
}

/**
 * Confirmación antes de una tarea de IA grande que no cabe en lo que queda del presupuesto de hoy.
 * [estimatedTokens] es una estimación local por el tamaño del texto.
 */
@Composable
fun CostConfirmDialog(estimatedTokens: Long, remainingTokens: Long, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cost_confirm_title)) },
        text = {
            Text(
                stringResource(
                    R.string.cost_confirm_message,
                    formatTokens(estimatedTokens),
                    formatTokens(remainingTokens)
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.cost_confirm_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cost_confirm_cancel)) }
        }
    )
}
