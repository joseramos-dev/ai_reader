package dev.joseramos.aireader.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Radius
import dev.joseramos.aireader.core.designsystem.theme.Spacing

/**
 * Hoja inferior con asa, esquinas de 20 dp y título opcional. Es la forma de mostrar
 * capítulos, voz, hechos clave y opciones sin cambiar de pantalla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = if (colors.isDark) colors.surfaceElevated else colors.groupedBackground,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = Spacing.xs, bottom = Spacing.xxs)
                    .size(width = 36.dp, height = 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.tertiaryLabel)
            )
        }
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.l)) {
            if (title != null) {
                Text(
                    text = title,
                    style = AppTheme.typography.headline,
                    color = colors.label,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = Spacing.s)
                        .semantics { heading() }
                )
            }
            content()
        }
    }
}
