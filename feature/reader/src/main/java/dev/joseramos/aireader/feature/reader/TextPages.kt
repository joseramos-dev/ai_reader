package dev.joseramos.aireader.feature.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import dev.joseramos.aireader.ai.characters.NameIndex
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.PageText
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.text.HeadingDetector
import dev.joseramos.aireader.text.PhraseSplitter

/** Frase que está sonando: página (base 1), párrafo y frase dentro del párrafo. */
data class PhraseLocation(val page: Int, val paragraph: Int, val phrase: Int)

/**
 * Modo lectura de texto: el texto limpio de cada página, con tipografía propia y tamaño ajustable.
 * Un elemento de la lista por página, para que el número de página coincida con el modo PDF; las
 * páginas se separan con una línea y su número, y los títulos se destacan en grande y en negrita.
 * En las novelas, los nombres de los personajes ya desbloqueados se subrayan y abren su ficha.
 * Con [onTapPhrase] (mientras suena la voz), tocar una frase sigue la lectura desde ella.
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
    onTapCharacter: (Long) -> Unit = {},
    onTapPhrase: ((PhraseLocation) -> Unit)? = null,
    chapters: List<Chapter> = emptyList()
) {
    val byPage = remember(pages) { pages.associateBy { it.page } }
    val titlesByPage = remember(chapters) { chapters.groupBy({ it.startPage }, { it.title }) }
    val colors = AppTheme.colors
    val bodyStyle = AppTheme.typography.body.copy(
        fontSize = (BODY_SIZE * textScale).sp,
        lineHeight = LINE_HEIGHT.em,
        textAlign = TextAlign.Start
    )
    val headingStyle = bodyStyle.copy(
        fontSize = (HEADING_SIZE * textScale).sp,
        fontWeight = FontWeight.Bold,
        lineHeight = HEADING_LINE_HEIGHT.em
    )
    SelectionContainer(modifier) {
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            items(pageCount, key = { it }) { index ->
                val page = byPage[index + 1]
                val titles = titlesByPage[index + 1].orEmpty()
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(bottom = PAGE_GAP),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    PageSeparator(index + 1)
                    page?.paragraphs?.forEachIndexed { p, paragraph ->
                        val heading = remember(paragraph, titles) { HeadingDetector.isHeading(paragraph, titles) }
                        val phrase = highlight?.takeIf { it.page == index + 1 && it.paragraph == p }?.phrase
                        val text = remember(paragraph, phrase, names, colors, onTapCharacter) {
                            annotated(paragraph, phrase, colors.highlight, names, colors.accent, onTapCharacter)
                        }
                        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                        val tap = if (onTapPhrase == null) {
                            Modifier
                        } else {
                            Modifier.pointerInput(paragraph, onTapPhrase) {
                                detectTapGestures { position ->
                                    val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                                    onTapPhrase(PhraseLocation(index + 1, p, phraseAt(paragraph, offset)))
                                }
                            }
                        }
                        Text(
                            text,
                            style = if (heading) headingStyle else bodyStyle,
                            color = colors.label,
                            onTextLayout = { layout = it },
                            modifier = (
                                if (heading) {
                                    Modifier.padding(
                                        top = Spacing.m,
                                        bottom = Spacing.xxs
                                    )
                                } else {
                                    Modifier
                                }
                                )
                                .then(tap)
                        )
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
 * Párrafo con la frase [phrase] resaltada (si no es `null`) y los nombres de personajes de [names]
 * subrayados y tocables.
 */
private fun annotated(
    paragraph: String,
    phrase: Int?,
    highlight: Color,
    names: NameIndex,
    nameColor: Color,
    onTapCharacter: (Long) -> Unit
): AnnotatedString {
    val range = phrase?.let { phraseRange(paragraph, it) }
    val hits = if (names.isEmpty) emptyList() else names.find(paragraph)
    if (range == null && hits.isEmpty()) return AnnotatedString(paragraph)
    return buildAnnotatedString {
        append(paragraph)
        range?.let { addStyle(SpanStyle(background = highlight), it.first, it.last + 1) }
        val style = TextLinkStyles(
            SpanStyle(
                textDecoration = TextDecoration.Underline,
                color = Color.Unspecified,
                background = nameColor.copy(alpha = 0.08f)
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

private const val BODY_SIZE = 18
private const val HEADING_SIZE = 24
private const val HEADING_LINE_HEIGHT = 1.25
private val PAGE_GAP = 28.dp
private const val LINE_HEIGHT = 1.5
