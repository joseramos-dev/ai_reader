package dev.joseramos.aireader.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.joseramos.aireader.core.designsystem.generated.resources.Res
import dev.joseramos.aireader.core.designsystem.generated.resources.inter_bold
import dev.joseramos.aireader.core.designsystem.generated.resources.inter_medium
import dev.joseramos.aireader.core.designsystem.generated.resources.inter_regular
import dev.joseramos.aireader.core.designsystem.generated.resources.inter_semibold
import org.jetbrains.compose.resources.Font

/**
 * Inter (licencia OFL, ver licenses/Inter-OFL.txt): la alternativa libre más cercana a SF Pro. Las fuentes son
 * recursos de Compose y solo se pueden cargar dentro de la composición.
 */
@Composable
internal fun rememberInter(): FontFamily {
    val regular = Font(Res.font.inter_regular, FontWeight.Normal)
    val medium = Font(Res.font.inter_medium, FontWeight.Medium)
    val semiBold = Font(Res.font.inter_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.inter_bold, FontWeight.Bold)
    return remember(regular, medium, semiBold, bold) { FontFamily(regular, medium, semiBold, bold) }
}

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

private fun style(
    family: FontFamily,
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Double = 0.0
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.em
)

@Suppress("MagicNumber") // Los tamaños y espaciados de la escala tipográfica son los propios tokens de diseño.
internal fun appTypography(family: FontFamily) = AppTypography(
    largeTitle = style(family, 34, 41, FontWeight.Bold, tracking = -0.011),
    title = style(family, 22, 28, FontWeight.SemiBold, tracking = -0.009),
    headline = style(family, 17, 22, FontWeight.SemiBold, tracking = -0.006),
    body = style(family, 17, 22, tracking = -0.006),
    callout = style(family, 16, 21, tracking = -0.004),
    subheadline = style(family, 15, 20, tracking = -0.002),
    footnote = style(family, 13, 18),
    caption = style(family, 12, 16)
)

/** Sin tema (vistas previas, pruebas): la fuente del sistema. */
internal val FallbackTypography = appTypography(FontFamily.Default)

@Composable
internal fun rememberAppTypography(): AppTypography {
    val family = rememberInter()
    return remember(family) { appTypography(family) }
}
