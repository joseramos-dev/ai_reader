package dev.joseramos.aireader.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.joseramos.aireader.ai.characters.NameIndex
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.Highlight
import dev.joseramos.aireader.core.data.book.PageText
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.text.HeadingDetector
import dev.joseramos.aireader.text.ParagraphSpan
import dev.joseramos.aireader.text.PhraseSplitter
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Frase que está sonando: página (base 1), párrafo y frase dentro del párrafo. */
data class PhraseLocation(val page: Int, val paragraph: Int, val phrase: Int)

/** Posición de un párrafo dentro del libro: página (base 1) e índice del párrafo en ella. */
private data class ParagraphRef(val page: Int, val paragraph: Int)

/** Selección que se quiere subrayar: página, párrafo y rango de caracteres. */
internal data class PendingHighlight(val page: Int, val paragraph: Int, val range: IntRange)

/**
 * Modo lectura de texto: el texto limpio de cada página, con tipografía propia y tamaño ajustable.
 * Un elemento de la lista por página, para que el número de página coincida con el modo PDF; las
 * páginas se separan con una línea y su número. Los títulos salen en negrita y a un tamaño según su
 * nivel (el que tenían en el PDF), y se respetan los saltos de renglón del PDF (versos, listas).
 * En las novelas, los nombres de los personajes ya desbloqueados se subrayan y abren su ficha. Los
 * subrayados guardados ([highlights]) se pintan y, al tocarlos, llaman a [onTapHighlight]. Para crear
 * uno nuevo se mantiene pulsado un punto del párrafo (selecciona la palabra) y se arrastra para
 * extender la selección (no hay selección nativa de Android: así el rango exacto se calcula
 * directamente con las coordenadas del propio párrafo, sin depender de APIs internas de Compose). Al
 * soltar, la selección queda con dos tiradores para ajustarla y una barra con «Subrayar», que llama a
 * [onCreateHighlight]. Tocar fuera o volver atrás la descarta; si no hay selección, tocar llama a
 * [onTap]. Con [onTapPhrase] (mientras suena la voz), tocar una frase sigue la lectura desde ella.
 * [passage] es el pasaje de una fuente del chat que se ha abierto: se resalta, sin guardarse.
 */
@Composable
internal fun TextPages(
    listState: LazyListState,
    pageCount: Int,
    pages: List<PageText>,
    textScale: Float,
    highlight: PhraseLocation?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    names: NameIndex = NameIndex.EMPTY,
    highlights: List<Highlight> = emptyList(),
    onTapCharacter: (Long) -> Unit = {},
    onTapPhrase: ((PhraseLocation) -> Unit)? = null,
    onCreateHighlight: (PendingHighlight) -> Unit = {},
    onTapHighlight: (Highlight) -> Unit = {},
    onTap: () -> Unit = {},
    chapters: List<Chapter> = emptyList(),
    passage: List<ParagraphSpan> = emptyList()
) {
    val byPage = remember(pages) { pages.associateBy { it.page } }
    val titlesByPage = remember(chapters) { chapters.groupBy({ it.startPage }, { it.title }) }
    val highlightsByParagraph = remember(highlights) { highlights.groupBy { ParagraphRef(it.page, it.paragraph) } }
    val passageByParagraph = remember(passage) {
        passage.associate { ParagraphRef(it.page, it.paragraph) to (it.start until it.end) }
    }
    // Si se abre en la página del pasaje, la lista baja una vez hasta donde empieza (puede quedar
    // varias pantallas por debajo del principio de la página).
    val passageStart = passage.firstOrNull()
    var revealPassage by remember(passageStart) {
        mutableStateOf(passageStart != null && listState.firstVisibleItemIndex + 1 == passageStart.page)
    }
    val revealMargin = with(LocalDensity.current) { PASSAGE_MARGIN.roundToPx() }
    val scope = rememberCoroutineScope()
    val colors = AppTheme.colors
    val bodyStyle = AppTheme.typography.body.copy(
        fontSize = (BODY_SIZE * textScale).sp,
        lineHeight = LINE_HEIGHT.em,
        textAlign = TextAlign.Start
    )
    val headingStyles = HEADING_SIZES.map { size ->
        bodyStyle.copy(
            fontSize = (size * textScale).sp,
            fontWeight = FontWeight.Bold,
            lineHeight = HEADING_LINE_HEIGHT.em
        )
    }
    val haptics = LocalHapticFeedback.current
    // Selección en curso (al arrastrar y después, hasta subrayarla o descartarla).
    var selection by remember { mutableStateOf<TextSelection?>(null) }
    // Mientras se arrastra (la selección inicial o un tirador) no se muestra la barra de acciones.
    var dragging by remember { mutableStateOf(false) }
    val minToolbarTop = with(LocalDensity.current) { contentPadding.calculateTopPadding().roundToPx() }
    BackHandler(enabled = selection != null) { selection = null }

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize().pointerInput(onTap) {
            detectTapGestures { if (selection != null) selection = null else onTap() }
        }
    ) {
        items(pageCount, key = { it }) { index ->
            val page = byPage[index + 1]
            val titles = titlesByPage[index + 1].orEmpty()
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(bottom = PAGE_GAP),
                verticalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                PageSeparator(index + 1)
                page?.paragraphs.orEmpty().forEachIndexed { p, paragraph ->
                    val ref = ParagraphRef(index + 1, p)
                    // Nivel del PDF (por tamaño de letra); si no lo hay, se adivina por el texto.
                    val guessed = remember(paragraph, titles) { HeadingDetector.isHeading(paragraph, titles) }
                    val level = page?.levelOf(p)?.takeIf { it > 0 } ?: if (guessed) GUESSED_LEVEL else 0
                    val heading = level > 0
                    val phrase = highlight?.takeIf { it.page == index + 1 && it.paragraph == p }?.phrase
                    val saved = highlightsByParagraph[ref].orEmpty()
                    val selected = selection?.takeIf { it.isIn(ref.page, ref.paragraph) }
                    val live = selected?.let { it.start until it.end }
                    val source = passageByParagraph[ref]
                    val textKeys =
                        arrayOf(paragraph, phrase, names, colors, onTapCharacter, saved, onTapHighlight, live, source)
                    val text = remember(*textKeys) {
                        annotated(
                            paragraph,
                            phrase,
                            colors.highlight,
                            names,
                            colors.accent,
                            onTapCharacter,
                            saved,
                            colors.accent.copy(alpha = SAVED_HIGHLIGHT_ALPHA),
                            onTapHighlight,
                            live,
                            colors.accent.copy(alpha = LIVE_SELECTION_ALPHA),
                            source,
                            colors.accent.copy(alpha = PASSAGE_ALPHA)
                        )
                    }
                    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    val tap = if (onTapPhrase == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(paragraph, onTapPhrase) {
                            detectTapGestures { position ->
                                if (selection != null) {
                                    selection = null
                                    return@detectTapGestures
                                }
                                val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                                onTapPhrase(PhraseLocation(index + 1, p, phraseAt(paragraph, offset)))
                            }
                        }
                    }
                    // Mantener pulsado selecciona la palabra (no interfiere con el scroll, que es un
                    // arrastre sin pausa previa); arrastrar extiende la selección desde ella; al soltar
                    // queda con sus tiradores.
                    val select = Modifier.pointerInput(paragraph) {
                        var word = IntRange.EMPTY
                        detectDragGesturesAfterLongPress(
                            onDragStart = start@{ position ->
                                val current = layout ?: return@start
                                if (paragraph.isEmpty()) return@start
                                word = wordAt(current, current.getOffsetForPosition(position))
                                selection = TextSelection(ref.page, ref.paragraph, word.first, word.last + 1)
                                dragging = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false }
                        ) drag@{ change, _ ->
                            val offset = layout?.getOffsetForPosition(change.position) ?: return@drag
                            val s = selection?.takeIf { it.isIn(ref.page, ref.paragraph) } ?: return@drag
                            // Hacia delante se ancla al inicio de la palabra; hacia atrás, a su final.
                            selection = if (offset >= word.first) {
                                s.copy(a = word.first, b = maxOf(offset, word.last + 1))
                            } else {
                                s.copy(a = word.last + 1, b = offset)
                            }
                            change.consume()
                        }
                    }
                    // Al soltar tras seleccionar, el dedo no cuenta como toque: sin esto, los enlaces del
                    // párrafo (nombres, subrayados), que reciben el evento antes, abrirían su hoja.
                    val swallowRelease = Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (dragging) event.changes.forEach { if (it.changedToUp()) it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    // Por encima de los párrafos vecinos, para que los tiradores (que asoman por debajo)
                    // se dibujen encima y se puedan tocar.
                    val reveal = if (revealPassage && passageStart?.page == ref.page && passageStart.paragraph == p) {
                        Modifier.onGloballyPositioned { coordinates ->
                            if (!revealPassage) return@onGloballyPositioned
                            revealPassage = false
                            val line =
                                layout?.let {
                                    it.getLineTop(it.getLineForOffset(passageStart.start.coerceIn(0, paragraph.length)))
                                }
                                    ?: 0f
                            val offset = (coordinates.positionInParent().y + line).roundToInt() - revealMargin
                            scope.launch { listState.scrollToItem(index, maxOf(0, offset)) }
                        }
                    } else {
                        Modifier
                    }
                    Box(
                        (if (heading) Modifier.padding(top = Spacing.m, bottom = Spacing.xxs) else Modifier)
                            .zIndex(if (selected != null) 1f else 0f)
                            .then(reveal)
                    ) {
                        Text(
                            text,
                            style = if (heading) headingStyles[minOf(level, HEADING_SIZES.size) - 1] else bodyStyle,
                            color = colors.label,
                            onTextLayout = { layout = it },
                            modifier = tap.then(select).then(swallowRelease)
                        )
                        val current = layout
                        if (selected != null && current != null) {
                            SelectionHandles(
                                layout = current,
                                selection = selected,
                                onChange = { selection = it },
                                onDragging = { dragging = it }
                            )
                            if (!dragging && !listState.isScrollInProgress) {
                                val (top, bottom, centerX) = selectionBounds(current, selected)
                                SelectionToolbar(top, bottom, centerX, minToolbarTop) {
                                    // Sin los espacios de los bordes, que se cuelan al ajustar con los tiradores.
                                    var start = selected.start.coerceIn(0, paragraph.length)
                                    var end = selected.end.coerceIn(start, paragraph.length)
                                    while (start < end && paragraph[start].isWhitespace()) start++
                                    while (end > start && paragraph[end - 1].isWhitespace()) end--
                                    selection = null
                                    if (start < end) {
                                        onCreateHighlight(PendingHighlight(ref.page, ref.paragraph, start until end))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Separación entre páginas: una línea fina con el número de página en medio. */
@Composable
private fun PageSeparator(page: Int) {
    val colors = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = PAGE_GAP, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        HorizontalDivider(Modifier.weight(1f), thickness = 0.5.dp, color = colors.separator)
        Text(page.toString(), style = AppTheme.typography.caption, color = colors.tertiaryLabel)
        HorizontalDivider(Modifier.weight(1f), thickness = 0.5.dp, color = colors.separator)
    }
}

/**
 * Párrafo con la frase [phrase] resaltada (si no es `null`), los nombres de personajes de [names]
 * subrayados y tocables, los [savedHighlights] ya guardados (tocables para editarlos o quitarlos),
 * mientras se arrastra, el rango [liveSelection] en curso y, subrayado, el [passage] de una fuente del chat.
 */
@Suppress("LongParameterList") // Es la única función que pinta el párrafo: no gana nada partiéndola.
private fun annotated(
    paragraph: String,
    phrase: Int?,
    highlight: Color,
    names: NameIndex,
    nameColor: Color,
    onTapCharacter: (Long) -> Unit,
    savedHighlights: List<Highlight>,
    savedHighlightColor: Color,
    onTapHighlight: (Highlight) -> Unit,
    liveSelection: IntRange?,
    liveSelectionColor: Color,
    passage: IntRange?,
    passageColor: Color
): AnnotatedString {
    val range = phrase?.let { phraseRange(paragraph, it) }
    val hits = if (names.isEmpty) emptyList() else names.find(paragraph)
    if (range == null && hits.isEmpty() && savedHighlights.isEmpty() && liveSelection == null && passage == null) {
        return AnnotatedString(paragraph)
    }
    return buildAnnotatedString {
        append(paragraph)
        styleRange(SpanStyle(background = passageColor, textDecoration = TextDecoration.Underline), passage)
        range?.let { addStyle(SpanStyle(background = highlight), it.first, it.last + 1) }
        savedHighlights.forEach { saved ->
            val start = saved.startOffset.coerceIn(0, paragraph.length)
            val end = saved.endOffset.coerceIn(start, paragraph.length)
            if (start < end) {
                addStyle(SpanStyle(background = savedHighlightColor), start, end)
                addLink(LinkAnnotation.Clickable("highlight-${saved.id}") { onTapHighlight(saved) }, start, end)
            }
        }
        styleRange(SpanStyle(background = liveSelectionColor), liveSelection)
        // El fondo del enlace taparía el de la selección en curso o el del pasaje: con ellos, se quita.
        val covered = liveSelection != null || passage != null
        val nameBackground = if (covered) Color.Unspecified else nameColor.copy(alpha = 0.08f)
        val style = TextLinkStyles(
            SpanStyle(
                textDecoration = TextDecoration.Underline,
                color = Color.Unspecified,
                background = nameBackground
            )
        )
        hits.forEach { hit ->
            addLink(
                LinkAnnotation.Clickable("character-${hit.characterId}", style) {
                    onTapCharacter(hit.characterId)
                },
                hit.start,
                hit.end
            )
        }
    }
}

/** [style] sobre [range] (con su último carácter), recortado al texto ya añadido; nada si es `null` o queda vacío. */
private fun AnnotatedString.Builder.styleRange(style: SpanStyle, range: IntRange?) {
    range ?: return
    val start = range.first.coerceIn(0, length)
    val end = (range.last + 1).coerceIn(start, length)
    if (start < end) addStyle(style, start, end)
}

/** Posición de cada frase de [PhraseSplitter] dentro del párrafo (`null` si no se encuentra tal cual). */
private fun phraseRanges(paragraph: String): List<IntRange?> {
    var from = 0
    return PhraseSplitter.split(paragraph).map { phrase ->
        val start = paragraph.indexOf(phrase, from)
        if (start < 0) {
            null
        } else {
            from = start + phrase.length
            start until start + phrase.length
        }
    }
}

private fun phraseRange(paragraph: String, phrase: Int): IntRange? = phraseRanges(paragraph).getOrNull(phrase)

/** Índice de la frase que contiene el carácter [offset] (la última que empieza antes, si cae entre dos). */
internal fun phraseAt(paragraph: String, offset: Int): Int =
    phraseRanges(paragraph).indexOfLast { it != null && it.first <= offset }.coerceAtLeast(0)

private const val SAVED_HIGHLIGHT_ALPHA = 0.25f
private const val PASSAGE_ALPHA = 0.18f

/** Texto que se deja ver por encima del pasaje al bajar hasta él. */
private val PASSAGE_MARGIN = 48.dp
private const val LIVE_SELECTION_ALPHA = 0.35f
private const val BODY_SIZE = 18

/** Tamaño de los títulos por nivel (1 es el mayor); los niveles más profundos usan el último. */
private val HEADING_SIZES = listOf(26, 22, 20, 18)

/** Nivel de los títulos reconocidos solo por el texto («Capítulo 3»), sin tamaño de letra del PDF. */
private const val GUESSED_LEVEL = 2
private const val HEADING_LINE_HEIGHT = 1.25
private val PAGE_GAP = 28.dp
private const val LINE_HEIGHT = 1.5
