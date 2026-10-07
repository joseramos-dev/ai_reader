package dev.joseramos.aireader.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.BarSize
import dev.joseramos.aireader.core.designsystem.theme.Radius
import dev.joseramos.aireader.core.designsystem.theme.Spacing

object GroupedSectionDefaults {
    /** Las líneas empiezan donde empieza el texto de la fila. */
    val DividerInset = Spacing.m

    /** Para secciones cuyas filas llevan icono: la línea empieza tras el icono, como en iOS. */
    val IconDividerInset = Spacing.m + CELL_ICON_SIZE + Spacing.s
}

private val CELL_ICON_SIZE = 29.dp

/** Ámbito para declarar las filas de una [GroupedSection]. */
class GroupedSectionScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/**
 * Sección de lista agrupada con esquinas redondeadas (inset grouped de iOS): cabecera,
 * filas separadas por líneas finas y pie opcional.
 */
@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    dividerInset: Dp = GroupedSectionDefaults.DividerInset,
    content: GroupedSectionScope.() -> Unit
) {
    val colors = AppTheme.colors
    val rows = GroupedSectionScope().apply(content).rows
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs)) {
        if (header != null) {
            Text(
                text = header.uppercase(),
                style = AppTheme.typography.footnote,
                color = colors.secondaryLabel,
                modifier = Modifier.padding(start = Spacing.m, bottom = 6.dp)
            )
        }
        Column(Modifier.clip(RoundedCornerShape(Radius.cell)).background(colors.surface)) {
            rows.forEachIndexed { index, row ->
                row()
                if (index < rows.lastIndex) {
                    HorizontalDivider(
                        Modifier.padding(start = dividerInset),
                        thickness = 0.5.dp,
                        color = colors.separator
                    )
                }
            }
        }
        if (footer != null) {
            Text(
                text = footer,
                style = AppTheme.typography.footnote,
                color = colors.secondaryLabel,
                modifier = Modifier.padding(start = Spacing.m, end = Spacing.m, top = 6.dp)
            )
        }
    }
}

/**
 * Fila de lista: icono opcional en un cuadrado de color, título, subtítulo, valor a la
 * derecha y un elemento final (interruptor, chevron…).
 */
@Composable
fun Cell(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBackground: Color = AppTheme.colors.accent,
    value: String? = null,
    destructive: Boolean = false,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = AppTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = BarSize.minTouch)
            .padding(horizontal = Spacing.m, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        if (icon != null) {
            Box(
                Modifier.size(CELL_ICON_SIZE).clip(RoundedCornerShape(7.dp)).background(iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = AppTheme.typography.body,
                color = if (destructive) colors.destructive else colors.label
            )
            if (subtitle != null) {
                Text(text = subtitle, style = AppTheme.typography.footnote, color = colors.secondaryLabel)
            }
        }
        if (value != null) {
            Text(text = value, style = AppTheme.typography.body, color = colors.secondaryLabel, maxLines = 1)
        }
        trailing?.invoke()
        if (showChevron) {
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.tertiaryLabel,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
