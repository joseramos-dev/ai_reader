package dev.joseramos.aireader.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.BarSize

data class TabItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/**
 * Barra de pestañas inferior translúcida. Se superpone al contenido, que debe reservar
 * [BarSize.tabBar] de margen inferior (ver `LocalBottomBarHeight`).
 */
@Composable
fun TabBar(
    items: List<TabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    val haptics = LocalHapticFeedback.current
    val separator = colors.separator
    Row(
        modifier
            .fillMaxWidth()
            .barBlur(hazeState)
            .drawBehind { drawLine(separator, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1f) }
            .navigationBarsPadding()
            .height(BarSize.tabBar),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            val iconTint = if (selected) colors.accent else colors.secondaryLabel
            val labelTint = if (selected) colors.accentText else colors.secondaryLabel
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = selected, role = Role.Tab) {
                        if (!selected) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onSelect(index)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    if (selected) item.selectedIcon else item.icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(26.dp)
                )
                Text(item.label, style = AppTheme.typography.caption.copy(fontSize = 10.sp), color = labelTint)
            }
        }
    }
}
