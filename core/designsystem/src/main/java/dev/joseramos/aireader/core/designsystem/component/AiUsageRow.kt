package dev.joseramos.aireader.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import kotlin.math.roundToInt

/**
 * Fila con el anillo de consumo de IA del día: lo que queda, lo gastado frente al límite diario y
 * cuándo se renueva la cuota.
 */
@Composable
fun AiUsageRow(
    remaining: Float,
    usedTokens: Long,
    budgetTokens: Long,
    exhausted: Boolean,
    resetsAt: Long,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    val percent = (remaining * PERCENT).roundToInt()
    val resetTime = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(resetsAt))
    Row(
        modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        UsageRing(
            remaining = remaining,
            contentDescription = stringResource(R.string.usage_ring_description, percent),
            size = 44.dp,
            showPercent = true
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (exhausted) R.string.usage_exhausted_title else R.string.usage_title),
                style = AppTheme.typography.subheadline,
                color = if (exhausted) colors.destructive else colors.label
            )
            Text(
                if (exhausted) {
                    stringResource(R.string.usage_exhausted_detail, resetTime)
                } else {
                    stringResource(
                        R.string.usage_detail,
                        formatTokens(usedTokens),
                        formatTokens(budgetTokens),
                        resetTime
                    )
                },
                style = AppTheme.typography.footnote,
                color = colors.secondaryLabel
            )
        }
    }
}

/** «12,3 k» o «1,2 M» tokens. */
fun formatTokens(tokens: Long): String = when {
    tokens >= MILLION -> "%.1f M".format(tokens / MILLION.toDouble())
    tokens >= THOUSAND -> "%.1f k".format(tokens / THOUSAND.toDouble())
    else -> tokens.toString()
}

private const val PERCENT = 100
private const val THOUSAND = 1_000L
private const val MILLION = 1_000_000L
