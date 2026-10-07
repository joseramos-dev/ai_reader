package dev.joseramos.aireader.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.BarSize
import dev.joseramos.aireader.core.designsystem.theme.LocalBottomBarHeight
import dev.joseramos.aireader.core.designsystem.theme.Spacing

/**
 * Pantalla con título grande al estilo iOS: el título forma parte del contenido y, al
 * desplazarse, aparece un título compacto en una barra superior translúcida.
 *
 * @param grouped usa el fondo gris de las listas agrupadas (Ajustes) en vez del blanco.
 */
@Composable
fun LargeTitleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    grouped: Boolean = false,
    navigationAction: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    val colors = AppTheme.colors
    val hazeState = rememberHazeState()
    val listState = rememberLazyListState()
    val thresholdPx = with(LocalDensity.current) { 36.dp.toPx() }
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > thresholdPx }
    }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, label = "barAlpha")
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier.fillMaxSize().background(if (grouped) colors.groupedBackground else colors.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(
                top = statusBar + BarSize.topBar,
                bottom = navigationBar + LocalBottomBarHeight.current + Spacing.l
            )
        ) {
            item(key = "large-title") {
                Text(
                    text = title,
                    style = AppTheme.typography.largeTitle,
                    color = colors.label,
                    modifier = Modifier
                        .padding(start = Spacing.m, end = Spacing.m, bottom = Spacing.xs)
                        .semantics { heading() }
                )
            }
            content()
        }

        val separator = colors.separator
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (collapsed) Modifier.barBlur(hazeState) else Modifier)
                .drawBehind {
                    if (barAlpha > 0f) {
                        drawLine(
                            separator.copy(alpha = separator.alpha * barAlpha),
                            Offset(0f, size.height),
                            Offset(size.width, size.height),
                            strokeWidth = 1f
                        )
                    }
                }
                .statusBarsPadding()
                .height(BarSize.topBar)
        ) {
            Row(Modifier.align(Alignment.CenterStart).padding(start = Spacing.xxs)) {
                navigationAction?.invoke()
            }
            Text(
                text = title,
                style = AppTheme.typography.headline,
                color = colors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 72.dp).alpha(barAlpha)
            )
            Row(
                Modifier.align(Alignment.CenterEnd).padding(end = Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}
