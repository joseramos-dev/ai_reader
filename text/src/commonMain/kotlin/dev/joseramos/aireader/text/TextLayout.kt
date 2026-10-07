package dev.joseramos.aireader.text

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Línea de una página del PDF con su geometría, en puntos y con el origen arriba a la izquierda:
 * dónde empieza y acaba, su línea base, el tamaño de letra dominante, si es negrita y el ancho de su
 * primera palabra. Se guarda como JSON con nombres cortos.
 */
@Serializable
data class TextLine(
    @SerialName("t") val text: String,
    @SerialName("x") val left: Float,
    @SerialName("r") val right: Float,
    @SerialName("y") val baseline: Float,
    @SerialName("s") val size: Float,
    @SerialName("b") val bold: Boolean = false,
    @SerialName("w") val firstWordWidth: Float = 0f
)

/**
 * Párrafo de lectura. [level] 0 es texto normal; 1…[MAX_LEVEL] son títulos, de mayor a menor. Los
 * saltos de renglón que el PDF hace a propósito (versos, listas, direcciones) se conservan como `\n`.
 */
data class TextBlock(val text: String, val level: Int = 0) {
    companion object {
        const val MAX_LEVEL = 4
    }
}

/** Medidas tipográficas de todo el libro, calculadas con [LayoutAnalyzer.typography]. */
data class BookTypography(
    val bodySize: Float,
    /** Tamaño mínimo de cada nivel de título, de mayor a menor. */
    val headingSizes: List<Float> = emptyList(),
    /** Ancho habitual de una línea completa de texto normal (0 si no se sabe). */
    val textWidth: Float = 0f,
    /** Distancia habitual entre las líneas base del texto normal. */
    val lineGap: Float = bodySize * DEFAULT_LEADING,
    /** El texto normal ya es negrita: entonces la negrita no indica un título. */
    val boldBody: Boolean = false
) {
    /** Nivel de título que corresponde a un tamaño de letra (0 si es texto normal). */
    fun levelOf(size: Float): Int {
        if (size < bodySize * HEADING_RATIO) return 0
        val index = headingSizes.indexOfFirst { size >= it - SIZE_TOLERANCE }
        return if (index < 0) 0 else minOf(index + 1, TextBlock.MAX_LEVEL)
    }

    /** Los títulos en negrita del tamaño del texto quedan justo por debajo de los demás. */
    val boldLevel: Int get() = if (boldBody) 0 else minOf(headingSizes.size + 1, TextBlock.MAX_LEVEL)

    internal companion object {
        const val DEFAULT_LEADING = 1.2f
        const val HEADING_RATIO = 1.12f
        const val SIZE_TOLERANCE = 0.01f
    }
}

/**
 * Reconstruye los párrafos de una página a partir de la geometría de sus líneas, en lugar de
 * adivinarlos solo por la puntuación:
 * - Un salto de renglón es intencionado si la línea acaba antes del margen derecho dejando sitio
 *   para la primera palabra de la siguiente. Si además cierra una frase empieza otro párrafo; si no,
 *   se conserva como salto de renglón dentro del párrafo (versos, listas, direcciones).
 * - Un espacio vertical mayor de lo normal, la sangría de primera línea o una viñeta empiezan otro
 *   párrafo.
 * - El tamaño de letra da el nivel de título (el mayor tamaño del libro es el nivel 1), y una línea
 *   suelta y corta en negrita es un título menor.
 * Tiene en cuenta las páginas a dos columnas, las citas sangradas y las letras capitulares.
 */
object LayoutAnalyzer {
    private const val DEFAULT_BODY = 11f
    private const val SIZE_STEP = 2f
    private const val MAX_HEADING_SHARE = 0.5f
    private const val CLUSTER = 0.75f
    private const val MIN_EDGE_LINES = 5
    private const val MIN_GAP = 0.8f
    private const val MAX_GAP = 3f
    private const val PARAGRAPH_GAP = 1.45f
    private const val HEADING_GAP = 2.2f
    private const val SAME_LINE = 0.3f
    private const val INDENT = 0.8f
    private const val LIST_CONTINUATION = 0.3f
    private const val WORD_SLACK = 0.6f
    private const val FALLBACK_WORD = 2.5f
    private const val QUOTE_INDENT = 1.5f
    private const val QUOTE_WINDOW = 4
    private const val MIN_QUOTE_LINES = 3
    private const val COLUMN_FROM = 0.35f
    private const val COLUMN_TO = 0.75f
    private const val MIN_COLUMN_LINES = 5
    private const val DROP_CAP_RATIO = 1.5f
    private const val MAX_HEADING_CHARS = 200
    private const val MAX_HEADING_LINES = 4
    private const val MAX_BOLD_HEADING_CHARS = 90
    private const val MAX_BOLD_HEADING_WORDS = 14

    private val listItem = Regex("^([•·▪●○◦■□►▸‣⁃✓✔\\-–—*](\\s+|$)|\\(?\\d{1,2}[.)]\\s+|\\(?[a-z]\\)\\s+)")
    private val sentenceEnd = Regex("[.!?…:][»\"'”’)\\]]*$")
    private val hyphenEnd = Regex("\\p{L}[-‐‑]$")
    private val spaces = Regex("\\s+")

    /** Tamaño del texto normal, niveles de título, ancho de línea e interlineado de todo el libro. */
    fun typography(pages: List<List<TextLine>>): BookTypography {
        val chars = charsBySize(pages)
        var boldBodyChars = 0
        val body = chars.maxByOrNull { it.value }?.key ?: return BookTypography(DEFAULT_BODY)
        val bodyChars = chars.getValue(body)
        val candidates = chars
            .filter { (size, n) -> size >= body * BookTypography.HEADING_RATIO && n < bodyChars * MAX_HEADING_SHARE }
            .keys
            .sortedDescending()
        val levels = mutableListOf<Float>()
        for (size in candidates) {
            if (levels.isEmpty() || levels.last() - size > CLUSTER) levels += size else levels[levels.lastIndex] = size
        }

        val gaps = mutableListOf<Float>()
        val widths = mutableListOf<Float>()
        pages.forEach { page ->
            val bodyLines = page.filter { bucket(it.size) == body && it.text.isNotBlank() }
            boldBodyChars += bodyLines.filter { it.bold }.sumOf { it.text.length }
            bodyLines.zipWithNext { a, b -> b.baseline - a.baseline }
                .filter { it in body * MIN_GAP..body * MAX_GAP }
                .let(gaps::addAll)
            if (bodyLines.size >= MIN_EDGE_LINES) widths += bodyLines.maxOf { it.right } - bodyLines.minOf { it.left }
        }
        return BookTypography(
            bodySize = body,
            headingSizes = levels,
            textWidth = widths.median() ?: 0f,
            lineGap = gaps.median() ?: (body * BookTypography.DEFAULT_LEADING),
            boldBody = boldBodyChars * 2 > bodyChars
        )
    }

    /** Párrafos y títulos de una página, con sus líneas ya sin cabeceras ni pies. */
    fun blocks(lines: List<TextLine>, typography: BookTypography): List<TextBlock> {
        val clean = mergeDropCaps(
            lines.filter { it.text.isNotBlank() }.map { it.copy(text = it.text.replace(spaces, " ").trim()) },
            typography
        )
        if (clean.isEmpty()) return emptyList()
        val frame = PageFrame.of(clean, typography)
        val groups = mutableListOf<Group>()
        clean.forEachIndexed { i, line ->
            val level = typography.levelOf(line.size)
            val group = groups.lastOrNull()
            val kind = if (group == null) Break.BLOCK else breakBefore(i, clean, level, group, frame, typography)
            when (kind) {
                Break.BLOCK -> groups += Group(level, line, isListItem(line.text))
                Break.LINE -> group!!.add(line, soft = true)
                Break.WRAP -> group!!.add(line, soft = false)
            }
        }
        return groups.map { it.toBlock(typography) }.filter { it.text.isNotBlank() }
    }

    /** La línea empieza con una viñeta o una numeración de lista. */
    fun isListItem(text: String): Boolean = listItem.containsMatchIn(text)

    private enum class Break { BLOCK, LINE, WRAP }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun breakBefore(
        i: Int,
        lines: List<TextLine>,
        level: Int,
        group: Group,
        frame: PageFrame,
        typography: BookTypography
    ): Break {
        val prev = lines[i - 1]
        val line = lines[i]
        if (level != group.level) return Break.BLOCK
        val gap = line.baseline - prev.baseline
        // Trozos de la misma línea visual (una viñeta suelta, un cambio de letra).
        if (abs(gap) < line.size * SAME_LINE) return Break.WRAP
        // Vuelve hacia arriba: otra columna o un recuadro.
        if (gap < 0) return Break.BLOCK
        if (level > 0) return if (gap > line.size * HEADING_GAP) Break.BLOCK else lineOrWrap(i, lines, frame)
        val expected = typography.lineGap * (line.size / typography.bodySize)
        if (gap > expected * PARAGRAPH_GAP) return Break.BLOCK
        if (isListItem(line.text)) return Break.BLOCK
        val listContinuation = group.isList && line.left > group.startLeft + line.size * LIST_CONTINUATION
        if (!listContinuation && line.left > prev.left + line.size * INDENT) return Break.BLOCK
        return lineOrWrap(i, lines, frame)
    }

    /** Si la línea anterior acaba antes de tiempo, el salto es intencionado. */
    private fun lineOrWrap(i: Int, lines: List<TextLine>, frame: PageFrame): Break {
        val prev = lines[i - 1]
        val line = lines[i]
        if (hyphenEnd.containsMatchIn(prev.text) && line.text.first().isLowerCase()) return Break.WRAP
        val room = frame.rightEdge(i - 1, lines) - prev.right
        val word = line.firstWordWidth.takeIf { it > 0f } ?: (line.size * FALLBACK_WORD)
        val short = room > word + line.size * WORD_SLACK
        return when {
            !short -> Break.WRAP
            sentenceEnd.containsMatchIn(prev.text) || prev.bold != line.bold -> Break.BLOCK
            else -> Break.LINE
        }
    }

    /** Una letra capitular (una o dos letras grandes) se une a la línea que sigue. */
    private fun mergeDropCaps(lines: List<TextLine>, typography: BookTypography): List<TextLine> {
        val result = mutableListOf<TextLine>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val next = lines.getOrNull(i + 1)
            val dropCap = line.text.length <= 2 &&
                line.text.all { it.isLetter() } &&
                line.size >= typography.bodySize * DROP_CAP_RATIO &&
                next != null &&
                next.text.first().isLowerCase()
            if (dropCap) {
                result += next!!.copy(text = line.text + next.text, left = minOf(line.left, next.left))
                i += 2
            } else {
                result += line
                i++
            }
        }
        return result
    }

    private class Group(val level: Int, first: TextLine, val isList: Boolean) {
        val startLeft = first.left
        private val lines = mutableListOf(first)
        private val soft = mutableListOf(false)

        fun add(line: TextLine, soft: Boolean) {
            lines += line
            this.soft += soft
        }

        fun toBlock(typography: BookTypography): TextBlock {
            val text = StringBuilder(lines.first().text)
            for (i in 1 until lines.size) {
                val part = lines[i].text
                when {
                    soft[i] -> text.append('\n').append(part)
                    hyphenEnd.containsMatchIn(text) && part.first().isLowerCase() -> {
                        text.setLength(text.length - 1)
                        text.append(part)
                    }
                    else -> text.append(' ').append(part)
                }
            }
            val joined = text.toString()
            val level = when {
                level > 0 && (joined.length > MAX_HEADING_CHARS || lines.size > MAX_HEADING_LINES) -> 0
                level == 0 && lines.all { it.bold } && isBoldHeading(joined) -> typography.boldLevel
                else -> level
            }
            return TextBlock(joined, level)
        }

        private fun isBoldHeading(text: String): Boolean = text.length <= MAX_BOLD_HEADING_CHARS &&
            text.split(Regex("\\s+")).size <= MAX_BOLD_HEADING_WORDS &&
            text.any { it.isLetter() } &&
            text.last() !in ",;"
    }

    /** Columnas de la página: dónde empieza y acaba cada una. */
    private class PageFrame(private val columns: List<ClosedFloatingPointRange<Float>>) {
        fun rightEdge(index: Int, lines: List<TextLine>): Float {
            val line = lines[index]
            val column = columns.lastOrNull { line.left >= it.start - line.size } ?: columns.first()
            // Cita sangrada: su margen derecho es el de sus propias líneas.
            if (line.left - column.start > line.size * QUOTE_INDENT) {
                val window = lines.subList(maxOf(0, index - QUOTE_WINDOW), minOf(lines.size, index + QUOTE_WINDOW))
                    .filter { it.left - column.start > it.size * QUOTE_INDENT }
                if (window.size >= MIN_QUOTE_LINES) return window.maxOf { it.right }
            }
            return column.endInclusive
        }

        companion object {
            fun of(lines: List<TextLine>, typography: BookTypography): PageFrame {
                val body = lines.filter { typography.levelOf(it.size) == 0 }.ifEmpty { lines }
                val left = body.minOf { it.left }
                // El ancho habitual del libro: en una página de versos o con pocas líneas, la línea
                // más larga no llega al margen.
                val right = maxOf(body.maxOf { it.right }, left + typography.textWidth)
                val width = right - left
                val second = body.filter { it.left in left + width * COLUMN_FROM..left + width * COLUMN_TO }
                if (second.size >= MIN_COLUMN_LINES) {
                    val start = second.groupingBy { it.left.roundToInt() }.eachCount().maxBy { it.value }.key.toFloat()
                    val firstColumn = body.filter { it.right <= start }
                    if (firstColumn.size >= MIN_COLUMN_LINES) {
                        return PageFrame(listOf(left..firstColumn.maxOf { it.right }, start..right))
                    }
                }
                return PageFrame(listOf(left..right))
            }
        }
    }

    /** Letras del libro por tamaño de letra (redondeado a medio punto). */
    private fun charsBySize(pages: List<List<TextLine>>): Map<Float, Int> {
        val chars = mutableMapOf<Float, Int>()
        pages.forEach { page ->
            page.forEach { line ->
                val n = line.text.count { !it.isWhitespace() }
                if (n > 0 && line.size > 0f) chars.merge(bucket(line.size), n, Int::plus)
            }
        }
        return chars
    }

    private fun bucket(size: Float) = (size * SIZE_STEP).roundToInt() / SIZE_STEP

    private fun List<Float>.median(): Float? = if (isEmpty()) null else sorted()[size / 2]
}
