package dev.joseramos.aireader.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing

/** Estado vacío: icono, título, explicación breve y, como mucho, una acción. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = AppTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(colors.accentFill),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(title, style = AppTheme.typography.title, color = colors.label, textAlign = TextAlign.Center)
        Text(
            message,
            style = AppTheme.typography.subheadline,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.s))
            PrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}
