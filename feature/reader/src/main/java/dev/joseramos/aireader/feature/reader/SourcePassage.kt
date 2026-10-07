package dev.joseramos.aireader.feature.reader

import androidx.compose.ui.geometry.Rect
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.ChatSource
import dev.joseramos.aireader.text.ParagraphSpan
import dev.joseramos.aireader.text.PassageLocator
import dev.joseramos.aireader.text.TextLine
import kotlinx.serialization.json.Json

/**
 * Pasaje de una fuente del chat en el libro: los trozos de párrafo que ocupa ([spans], modo texto) y,
 * por página, sus rectángulos sobre el PDF en puntos con el origen arriba a la izquierda ([rects], modo
 * PDF; vacío si esas páginas no tienen geometría).
 */
data class SourcePassage(val spans: List<ParagraphSpan>, val rects: Map<Int, List<Rect>>)

/** Busca en el libro el pasaje de una fuente del chat, para resaltarlo al abrirla. */
class SourcePassageLocator(private val content: BookContentRepository) {
    /** `null` si el pasaje no se encuentra (por ejemplo, el texto del libro cambió desde la respuesta). */
    suspend fun locate(bookId: String, source: ChatSource): SourcePassage? {
        // Una página más por cada lado: los párrafos partidos entre páginas pueden estar en la vecina.
        val from = maxOf(1, source.startPage - 1)
        val to = source.endPage + 1
        val pages = content.pagesFrom(bookId, from, to - from + 1).map { it.page to it.paragraphs }
        val spans = PassageLocator.inParagraphs(source.text, pages)
        if (spans.isEmpty()) return null

        val lines = content.lineLayouts(bookId, from, to).mapValues { (_, linesJson) ->
            runCatching { json.decodeFromString<List<TextLine>>(linesJson) }.getOrDefault(emptyList())
        }
        val rects = PassageLocator.inLines(source.text, lines.toList()).groupBy({ it.page }) { span ->
            val line = lines.getValue(span.page)[span.line]
            val width = line.right - line.left
            Rect(
                left = line.left + width * span.from,
                top = line.baseline - line.size * ASCENT,
                right = line.left + width * span.to,
                bottom = line.baseline + line.size * DESCENT
            )
        }
        return SourcePassage(spans, rects)
    }

    private companion object {
        /** Alto de las letras sobre y bajo la línea base, en proporción al tamaño de letra. */
        const val ASCENT = 0.85f
        const val DESCENT = 0.25f
        val json = Json { ignoreUnknownKeys = true }
    }
}
