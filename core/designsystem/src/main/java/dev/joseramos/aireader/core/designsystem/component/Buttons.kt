package dev.joseramos.aireader.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.BarSize
import dev.joseramos.aireader.core.designsystem.theme.Radius
import dev.joseramos.aireader.core.designsystem.theme.Spacing

private enum class ButtonKind { Filled, Tinted, Plain }

/** Botón principal: relleno de acento. Para la acción más importante de la pantalla. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) = AppButton(ButtonKind.Filled, text, onClick, modifier, icon, enabled)

/** Botón secundario: fondo de acento suave y texto de acento. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) = AppButton(ButtonKind.Tinted, text, onClick, modifier, icon, enabled)

/** Botón de solo texto, para acciones en barras y diálogos. */
@Composable
fun PlainButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) = AppButton(ButtonKind.Plain, text, onClick, modifier, icon, enabled)

/** Botón de icono para barras (atrás, más opciones…), con área táctil de 44 dp. */
@Composable
fun BarIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .size(BarSize.minTouch)
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = AppTheme.colors.accentText,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun AppButton(
    kind: ButtonKind,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    enabled: Boolean
) {
    val colors = AppTheme.colors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 600f), label = "buttonScale")
    val (background, content) = when (kind) {
        ButtonKind.Filled -> colors.accent to colors.onAccent
        ButtonKind.Tinted -> colors.accentFill to colors.accentText
        ButtonKind.Plain -> Color.Transparent to colors.accentText
    }
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(Radius.button))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .heightIn(min = if (kind == ButtonKind.Plain) BarSize.minTouch else 50.dp)
            .padding(horizontal = if (kind == ButtonKind.Plain) Spacing.xs else Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(text, style = AppTheme.typography.headline, color = content)
    }
}
