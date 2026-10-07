package dev.joseramos.aireader.text

/** Trozo de un párrafo del texto limpio: página (base 1), índice del párrafo y caracteres [start, end). */
data class ParagraphSpan(val page: Int, val paragraph: Int, val start: Int, val end: Int)

/**
 * Trozo de una línea del PDF: página (base 1), índice de la línea en ella y la parte de su texto que
 * ocupa, como fracción de su longitud en [[from], [to]).
 */
data class LineSpan(val page: Int, val line: Int, val from: Float, val to: Float)

/**
 * Encuentra en el libro un pasaje enviado al chat (un fragmento del RAG) para resaltarlo en el lector:
 * en los párrafos del texto limpio (modo texto) o en las líneas del PDF (modo PDF).
 *
 * Se compara solo por letras y números, en minúsculas: así no importan los espacios, los saltos de
 * renglón ni los guiones de partición que el PDF tiene y el texto limpio no (o al revés). El pasaje se
 * busca párrafo a párrafo, a continuación de lo ya encontrado; si un párrafo no aparece seguido (por
 * ejemplo, partido entre dos páginas con la cabecera en medio), se marca el trozo más largo que sí
 * aparece y se sigue con el resto.
 */
object PassageLocator {
    /** Trozo seguido más corto (en letras y números) que se da por encontrado si no es un párrafo entero. */
    private const val MIN_RUN_CHARS = 10

    private val paragraphBreak = Regex("\n\\s*\n")
    private val whitespace = Regex("\\s+")

    /** [pages]: los párrafos de cada página (base 1), en orden de lectura. */
    fun inParagraphs(passage: String, pages: List<Pair<Int, List<String>>>): List<ParagraphSpan> {
        val segments = pages.flatMap { (page, paragraphs) ->
            paragraphs.mapIndexed { i, text -> Segment(page, i, text) }
        }
        return locate(passage, segments).map { (segment, range) ->
            ParagraphSpan(segment.page, segment.index, range.first, range.last + 1)
        }
    }

    /** [pages]: las líneas de cada página (base 1), en el orden en que se extrajeron. */
    fun inLines(passage: String, pages: List<Pair<Int, List<TextLine>>>): List<LineSpan> {
        val segments = pages.flatMap { (page, lines) -> lines.mapIndexed { i, line -> Segment(page, i, line.text) } }
        return locate(passage, segments).map { (segment, range) ->
            val length = segment.text.length.toFloat()
            LineSpan(segment.page, segment.index, range.first / length, (range.last + 1) / length)
        }
    }

    /** Párrafo o línea: página, posición en ella y texto. */
    private class Segment(val page: Int, val index: Int, val text: String)

    /**
     * Las letras y números de todos los segmentos seguidos, en minúsculas, con el segmento ([segment])
     * y la posición en su texto ([offset]) de cada uno.
     */
    private class Haystack(segments: List<Segment>) {
        val text: String
        val segment: IntArray
        val offset: IntArray

        init {
            val chars = StringBuilder()
            val owners = ArrayList<Int>()
            val positions = ArrayList<Int>()
            segments.forEachIndexed { s, item ->
                item.text.forEachIndexed { i, c ->
                    if (c.isLetterOrDigit()) {
                        chars.append(c.lowercaseChar())
                        owners += s
                        positions += i
                    }
                }
            }
            text = chars.toString()
            segment = owners.toIntArray()
            offset = positions.toIntArray()
        }
    }

    /** Lo que ocupa el pasaje en cada segmento, en orden, ampliado a la puntuación de los extremos. */
    private fun locate(passage: String, segments: List<Segment>): List<Pair<Segment, IntRange>> {
        if (segments.isEmpty()) return emptyList()
        val haystack = Haystack(segments)
        val covered = BooleanArray(haystack.text.length)
        var cursor = 0
        for (piece in passage.split(paragraphBreak)) {
            val words = piece.split(whitespace).map(::normalize).filter { it.isNotEmpty() }
            val needle = words.joinToString("")
            val wordStarts = words.runningFold(0) { at, word -> at + word.length }
            var pos = 0
            while (pos < needle.length) {
                val (at, length) = longestRun(haystack.text, needle, pos, cursor)
                // Un trozo corto solo cuenta si es el párrafo entero (un título): suelto casaría en cualquier parte.
                if (length >= MIN_RUN_CHARS || (pos == 0 && length == needle.length)) {
                    covered.fill(true, at, at + length)
                    cursor = at + length
                    pos += length
                } else {
                    // Lo que no aparece (una palabra mal extraída, una nota) se salta hasta la palabra siguiente.
                    pos = wordStarts.firstOrNull { it > pos } ?: needle.length
                }
            }
        }

        // Por segmento, del primer al último carácter cubierto.
        val ranges = linkedMapOf<Int, IntRange>()
        covered.forEachIndexed { i, hit ->
            if (hit) {
                val s = haystack.segment[i]
                val at = haystack.offset[i]
                ranges[s] = ranges[s]?.let { minOf(it.first, at)..maxOf(it.last, at) } ?: at..at
            }
        }
        return ranges.map { (s, range) -> segments[s] to widen(segments[s].text, range) }
    }

    /**
     * El trozo más largo de [needle] desde [pos] que aparece en [text] a partir de [from]: dónde y cuánto
     * mide. Si un trozo aparece, también todos sus principios, así que se busca la longitud por bisección.
     */
    private fun longestRun(text: String, needle: String, pos: Int, from: Int): Pair<Int, Int> {
        var best = -1 to 0
        var low = 1
        var high = needle.length - pos
        while (low <= high) {
            val length = (low + high) / 2
            val at = text.indexOf(needle.substring(pos, pos + length), from)
            if (at >= 0) {
                best = at to length
                low = length + 1
            } else {
                high = length - 1
            }
        }
        return best
    }

    /** Incluye la puntuación pegada a los extremos («—Sí.», «(p. ej.)»), que no cuenta al comparar. */
    private fun widen(text: String, range: IntRange): IntRange {
        var start = range.first
        var end = range.last + 1
        while (start > 0 && !text[start - 1].isWhitespace() && !text[start - 1].isLetterOrDigit()) start--
        while (end < text.length && !text[end].isWhitespace() && !text[end].isLetterOrDigit()) end++
        return start until end
    }

    private fun normalize(text: String): String = buildString {
        text.forEach { if (it.isLetterOrDigit()) append(it.lowercaseChar()) }
    }
}
