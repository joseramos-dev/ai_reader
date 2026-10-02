package dev.joseramos.aireader.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.joseramos.aireader.core.designsystem.theme.AppTheme

/**
 * Fondo translúcido con desenfoque de lo que hay detrás, como las barras de iOS.
 * En Android 11 o anterior Haze lo sustituye por un color semitransparente.
 */
@Composable
fun Modifier.barBlur(state: HazeState): Modifier {
    val tint = AppTheme.colors.barBackground
    return hazeBlur(
        HazeInput.Backdrop(state),
        HazeBlurStyle {
            blurRadius(24.dp)
            noiseFactor(0f)
            colorEffects(listOf(HazeColorEffect.tint(tint)))
            fallbackColorEffect(HazeColorEffect.tint(tint.copy(alpha = 0.97f)))
        }
    )
}
