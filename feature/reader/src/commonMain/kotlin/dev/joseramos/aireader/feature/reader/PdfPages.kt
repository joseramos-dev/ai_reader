package dev.joseramos.aireader.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.pdf.PageSize
import kotlin.math.roundToInt

private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2f

/** Por encima de este zoom las páginas se vuelven a renderizar al doble de resolución. */
private const val HIGH_RES_ZOOM = 1.4f

/** Resaltado del pasaje de una fuente del chat: fondo translúcido y una raya debajo de cada línea. */
private const val PASSAGE_ALPHA = 0.28f
private val PASSAGE_UNDERLINE = 1.5.dp

/**
 * Páginas del PDF en scroll vertical continuo. El zoom con dos dedos (y el doble toque) escala
 * toda la columna; con un dedo se sigue desplazando en vertical. [passage] son los rectángulos (en
 * puntos del PDF) del pasaje de una fuente del chat, por página: se resaltan sobre ella.
 */
@Composable
internal fun PdfPages(
    listState: LazyListState,
    pageSizes: List<PageSize>,
    render: suspend (index: Int, widthPx: Int) -> ImageBitmap?,
    contentPadding: PaddingValues,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    passage: Map<Int, List<Rect>> = emptyMap()
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            val maxOffset = (scale - 1) * size.width / 2
                            offsetX = (offsetX + event.calculatePan().x).coerceIn(-maxOffset, maxOffset)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
                        offsetX = 0f
                    }
                )
            }
    ) {
        val baseWidth = constraints.maxWidth
        val renderWidth = if (scale > HIGH_RES_ZOOM) baseWidth * 2 else baseWidth
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                }
        ) {
            items(pageSizes.size, key = { it }) { index ->
                PdfPage(index, pageSizes[index], renderWidth, render, passage[index + 1].orEmpty())
            }
        }
    }
}

@Composable
private fun PdfPage(
    index: Int,
    size: PageSize,
    widthPx: Int,
    render: suspend (Int, Int) -> ImageBitmap?,
    passage: List<Rect>
) {
    val bitmap by produceState<ImageBitmap?>(null, index, widthPx) {
        value = render(index, widthPx)
    }
    val accent = AppTheme.colors.accent
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(size.width.toFloat() / size.height.coerceAtLeast(1))
            .background(Color.White)
            .drawWithContent {
                drawContent()
                if (passage.isEmpty()) return@drawWithContent
                // De puntos del PDF a píxeles de la página tal como se dibuja.
                val scale = this.size.width / size.width.coerceAtLeast(1)
                val underline = PASSAGE_UNDERLINE.toPx()
                passage.forEach { rect ->
                    val topLeft = Offset(rect.left * scale, rect.top * scale)
                    drawRect(
                        accent.copy(alpha = PASSAGE_ALPHA),
                        topLeft,
                        GeometrySize(
                            rect.width * scale,
                            rect.height * scale
                        )
                    )
                    drawRect(
                        accent,
                        Offset(topLeft.x, rect.bottom * scale - underline),
                        GeometrySize(rect.width * scale, underline)
                    )
                }
            }
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Página ${index + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Página (base 1) que ocupa la mayor parte de la pantalla. */
internal fun LazyListState.dominantPage(): Int {
    val items = layoutInfo.visibleItemsInfo
    if (items.isEmpty()) return firstVisibleItemIndex + 1
    val viewportEnd = layoutInfo.viewportEndOffset
    val best = items.maxBy { item ->
        val top = item.offset.coerceAtLeast(0)
        val bottom = (item.offset + item.size).coerceAtMost(viewportEnd)
        (bottom - top).toFloat().roundToInt()
    }
    return best.index + 1
}
