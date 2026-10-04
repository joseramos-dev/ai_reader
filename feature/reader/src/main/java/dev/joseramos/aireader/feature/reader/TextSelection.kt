package dev.joseramos.aireader.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Radius
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import kotlin.math.roundToInt

/**
 * Selección de texto dentro de un párrafo. [a] y [b] son posiciones de cursor (entre caracteres) de
 * cada tirador, sin orden: el rango seleccionado va de la menor a la mayor. Así, si un tirador se
 * arrastra más allá del otro, los papeles de inicio y fin se intercambian solos.
 */
internal data class TextSelection(val page: Int, val paragraph: Int, val a: Int, val b: Int) {
    val start: Int get() = minOf(a, b)
    val end: Int get() = maxOf(a, b)
    fun isIn(page: Int, paragraph: Int) = this.page == page && this.paragraph == paragraph
}

/** Rango de la palabra en [offset] (o el carácter, si no hay palabra), para empezar la selección. */
internal fun wordAt(layout: TextLayoutResult, offset: Int): IntRange {
    val length = layout.layoutInput.text.length
    val word = layout.getWordBoundary(offset.coerceIn(0, length))
    if (word.start < word.end) return word.start until word.end
    val start = offset.coerceIn(0, maxOf(length - 1, 0))
    return start until minOf(start + 1, length)
}

/** Punto (coordenadas del párrafo) donde está el borde [start] o final del cursor [caret]. */
private fun caretPoint(layout: TextLayoutResult, caret: Int, isStart: Boolean): Pair<Offset, Int> {
    val length = layout.layoutInput.text.length
    return if (isStart || caret == 0) {
        val c = caret.coerceIn(0, maxOf(length - 1, 0))
        val box = if (length == 0) Rect.Zero else layout.getBoundingBox(c)
        Offset(box.left, box.bottom) to layout.getLineForOffset(c)
    } else {
        // El final se ancla al último carácter seleccionado, no al siguiente: si este empieza
        // renglón, el tirador quedaría en el renglón de abajo.
        val c = (caret - 1).coerceIn(0, maxOf(length - 1, 0))
        val box = layout.getBoundingBox(c)
        Offset(box.right, box.bottom) to layout.getLineForOffset(c)
    }
}

/**
 * Los dos tiradores de [selection], en coordenadas del párrafo cuyo texto se maquetó en [layout].
 * Cada uno se arrastra por su cuenta y llama a [onChange] con la nueva selección; [onDragging]
 * avisa de cuándo empieza y termina el arrastre (para ocultar la barra de acciones mientras tanto).
 */
@Composable
internal fun SelectionHandles(
    layout: TextLayoutResult,
    selection: TextSelection,
    onChange: (TextSelection) -> Unit,
    onDragging: (Boolean) -> Unit
) {
    SelectionHandle(layout, selection, moveA = true, onChange, onDragging)
    SelectionHandle(layout, selection, moveA = false, onChange, onDragging)
}

@Composable
private fun SelectionHandle(
    layout: TextLayoutResult,
    selection: TextSelection,
    moveA: Boolean,
    onChange: (TextSelection) -> Unit,
    onDragging: (Boolean) -> Unit
) {
    val caret = if (moveA) selection.a else selection.b
    val other = if (moveA) selection.b else selection.a
    val isStart = caret < other
    val (anchor, _) = caretPoint(layout, caret, isStart)
    val currentLayout by rememberUpdatedState(layout)
    val currentSelection by rememberUpdatedState(selection)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnDragging by rememberUpdatedState(onDragging)
    val color = AppTheme.colors.accent
    val half = with(LocalDensity.current) { (HANDLE_TOUCH / 2).toPx() }
    Canvas(
        Modifier
            .offset { IntOffset((anchor.x - half).roundToInt(), anchor.y.roundToInt()) }
            .size(HANDLE_TOUCH)
            .pointerInput(moveA) {
                // Punto que se sigue al arrastrar: el centro del renglón del cursor, no el dedo
                // (que está debajo, sobre el tirador), para que el texto bajo el dedo no se salte.
                var point = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        val s = currentSelection
                        val c = if (moveA) s.a else s.b
                        val o = if (moveA) s.b else s.a
                        val (p, line) = caretPoint(currentLayout, c, c < o)
                        val lineHeight = currentLayout.getLineBottom(line) - currentLayout.getLineTop(line)
                        point = Offset(p.x, p.y - lineHeight / 2)
                        currentOnDragging(true)
                    },
                    onDragEnd = { currentOnDragging(false) },
                    onDragCancel = { currentOnDragging(false) }
                ) { change, amount ->
                    change.consume()
                    point += amount
                    val s = currentSelection
                    val offset = currentLayout.getOffsetForPosition(point)
                    val fixed = if (moveA) s.b else s.a
                    // Nunca una selección vacía: al cruzar el otro tirador se salta esa posición.
                    if (offset != fixed) currentOnChange(if (moveA) s.copy(a = offset) else s.copy(b = offset))
                }
            }
    ) {
        // Gota de Android: un círculo con la esquina que toca el texto en punta, hacia fuera de la selección.
        val r = HANDLE_RADIUS.toPx()
        val cx = size.width / 2
        val center = Offset(if (isStart) cx - r else cx + r, r)
        drawCircle(color, r, center)
        drawRect(color, Offset(if (isStart) cx - r else cx, 0f), Size(r, r))
    }
}

/**
 * Barra con la acción de subrayar, flotando sobre la selección (o debajo, si arriba no cabe).
 * [top] y [bottom] son el borde superior del primer renglón y el inferior del último, en
 * coordenadas del párrafo; [minTop] es lo que tapa la barra superior de la pantalla.
 */
@Composable
internal fun SelectionToolbar(top: Float, bottom: Float, centerX: Float, minTop: Int, onHighlight: () -> Unit) {
    val density = LocalDensity.current
    val gap = with(density) { Spacing.xs.roundToPx() }
    val below = with(density) { HANDLE_TOUCH.roundToPx() }
    val provider = object : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowSize: IntSize,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize
        ): IntOffset {
            val x = (anchorBounds.left + centerX.roundToInt() - popupContentSize.width / 2)
                .coerceIn(gap, maxOf(gap, windowSize.width - popupContentSize.width - gap))
            val above = anchorBounds.top + top.roundToInt() - popupContentSize.height - gap
            val y = if (above >= minTop) above else anchorBounds.top + bottom.roundToInt() + below
            return IntOffset(x, y)
        }
    }
    Popup(popupPositionProvider = provider) {
        Surface(
            shape = RoundedCornerShape(Radius.button),
            color = AppTheme.colors.surfaceElevated,
            shadowElevation = 6.dp
        ) {
            Box(Modifier.clickable(onClick = onHighlight).padding(horizontal = Spacing.m, vertical = Spacing.xs)) {
                Text(
                    stringResource(R.string.selection_highlight),
                    style = AppTheme.typography.subheadline,
                    color = AppTheme.colors.accentText
                )
            }
        }
    }
}

/** Bordes de la selección para colocar la barra: arriba del primer renglón, abajo del último y centro. */
internal fun selectionBounds(layout: TextLayoutResult, selection: TextSelection): Triple<Float, Float, Float> {
    val (startPoint, startLine) = caretPoint(layout, selection.start, isStart = true)
    val (endPoint, endLine) = caretPoint(layout, selection.end, isStart = false)
    val centerX = if (startLine == endLine) (startPoint.x + endPoint.x) / 2 else layout.size.width / 2f
    return Triple(layout.getLineTop(startLine), layout.getLineBottom(endLine), centerX)
}

private val HANDLE_TOUCH = 44.dp
private val HANDLE_RADIUS = 10.dp
