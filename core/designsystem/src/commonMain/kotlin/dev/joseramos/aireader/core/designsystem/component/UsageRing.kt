package dev.joseramos.aireader.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import kotlin.math.roundToInt

/**
 * Anillo de progreso con lo que queda de un recurso (por ejemplo, el consumo de IA del día): lleno
 * cuando no se ha usado nada y vacío cuando se ha agotado. Se pone rojo por debajo del 10 %.
 *
 * @param remaining de 0 a 1.
 * @param showPercent muestra el porcentaje en el centro (en anillos de 36 dp o más).
 */
@Composable
fun UsageRing(
    remaining: Float,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    showPercent: Boolean = false
) {
    val colors = AppTheme.colors
    val animated by animateFloatAsState(remaining.coerceIn(0f, 1f), label = "usageRing")
    val color = if (remaining < LOW) colors.destructive else colors.accent
    Box(
        modifier.size(size).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * STROKE_RATIO
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(colors.fill, 0f, FULL_CIRCLE, false, topLeft, arcSize, style = Stroke(stroke))
            if (animated > 0f) {
                drawArc(
                    color,
                    START_ANGLE,
                    FULL_CIRCLE * animated,
                    false,
                    topLeft,
                    arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
        if (showPercent) {
            Text(
                "${(remaining.coerceIn(0f, 1f) * PERCENT).roundToInt()}%",
                style = AppTheme.typography.caption.copy(fontSize = (size.value * PERCENT_TEXT_RATIO).sp),
                color = colors.label
            )
        }
    }
}

private const val LOW = 0.1f
private const val STROKE_RATIO = 0.12f
private const val FULL_CIRCLE = 360f
private const val START_ANGLE = -90f
private const val PERCENT = 100
private const val PERCENT_TEXT_RATIO = 0.24f
