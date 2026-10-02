package dev.joseramos.aireader.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.joseramos.aireader.core.designsystem.R

/** Inter (licencia OFL, ver licenses/Inter-OFL.txt): la alternativa libre más cercana a SF Pro. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

/** Escala tipográfica tipo iOS (docs/02-diseno-tecnico.md §3.2). */
@Immutable
data class AppTypography(
    val largeTitle: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val callout: TextStyle,
    val subheadline: TextStyle,
    val footnote: TextStyle,
    val caption: TextStyle
)

private fun style(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
    TextStyle(
        fontFamily = Inter,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = tracking.em
    )

internal val DefaultTypography = AppTypography(
    largeTitle = style(34, 41, FontWeight.Bold, tracking = -0.011),
    title = style(22, 28, FontWeight.SemiBold, tracking = -0.009),
    headline = style(17, 22, FontWeight.SemiBold, tracking = -0.006),
    body = style(17, 22, tracking = -0.006),
    callout = style(16, 21, tracking = -0.004),
    subheadline = style(15, 20, tracking = -0.002),
    footnote = style(13, 18),
    caption = style(12, 16)
)
