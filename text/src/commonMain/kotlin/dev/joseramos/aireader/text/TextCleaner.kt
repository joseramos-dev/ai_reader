package dev.joseramos.aireader.text

import java.text.Normalizer

/** Líneas repetidas en muchas páginas (cabeceras y pies), detectadas sobre todo el documento. */
data class DocumentLayout(val headerLines: Set<String> = emptySet(), val footerLines: Set<String> = emptySet())

/**
 * Limpieza del texto extraído de cada página (portado de `page_text_cleaner.py` del prototipo):
 * quita cabeceras, pies y números de página, une palabras cortadas por guion y líneas partidas,
 * y devuelve párrafos de lectura. Cualquier cambio de reglas debe subir [VERSION] para que los
 * libros ya procesados se vuelvan a limpiar.
 *
 * - v2: párrafos, saltos de renglón y títulos según la geometría de cada línea ([LayoutAnalyzer]).
 * - v3: ancho de la primera palabra bien medido cuando el PDF trae espacios (saltos de renglón).
 */
object TextCleaner {
    const val VERSION = 3

    private const val HYPHENS = "-‐‑–—"

    // Letra o número de cualquier idioma: `\w` solo es Unicode en Android; en la JVM de escritorio no incluye «á».
    private const val WORD = "[\\p{L}\\p{M}\\p{N}_]"
    private val hyphenBreak = Regex("($WORD)[$HYPHENS]\\s*\\n\\s*($WORD)")
    private val hyphenBreakSpaced = Regex("($WORD)[$HYPHENS]\\s+(\\p{Ll})")
    private val lineHyphenSuffix = Regex("($WORD+)[$HYPHENS]\\s*$")
    private val trailingHyphenWord = Regex("^(.+?\\s)?($WORD+)[$HYPHENS]\\s*$", RegexOption.DOT_MATCHES_ALL)
    private val pageNumber = Regex(
        "^\\s*(?:(?:p[áa]g(?:\\.|ina)?\\.?\\s*)?\\d+\\s*(?:de\\s+\\d+)?|[-–—]\\s*\\d+\\s*[-–—]|\\d+\\s*/\\s*\\d+)\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val sentenceEnd = Regex("(?:\\.{3}|…|[!?]|(?<!\\.)\\.(?!\\.))[»\"'”)\\]]*\\s*$")
    private val whitespace = Regex("\\s+")
    private val digits = Regex("\\d+")

    private const val MARGIN_LINES = 4
    private const val REPEAT_RATIO = 0.35
    private const val MAX_MARGIN_LINE = 160
    private const val MAX_HYPHEN_PASSES = 20

    /** Detecta cabeceras y pies que se repiten en al menos un 35 % de las páginas. */
    fun detectLayout(linesPerPage: List<List<String>>): DocumentLayout {
        if (linesPerPage.isEmpty()) return DocumentLayout()
        val top = mutableMapOf<String, Int>()
        val bottom = mutableMapOf<String, Int>()
        for (lines in linesPerPage) {
            val nonEmpty = lines.filter { it.isNotBlank() }
            nonEmpty.take(MARGIN_LINES).forEach { count(top, it) }
            nonEmpty.takeLast(MARGIN_LINES).forEach { count(bottom, it) }
        }
        val threshold = maxOf(2, (linesPerPage.size * REPEAT_RATIO).toInt())
        fun frequent(counter: Map<String, Int>) =
            counter.filter { (line, n) -> n >= threshold && line.length in 2..MAX_MARGIN_LINE }.keys
        return DocumentLayout(frequent(top), frequent(bottom))
    }

    /** Convierte el texto bruto de una página en párrafos limpios. */
    fun pageToParagraphs(raw: String, layout: DocumentLayout): List<String> {
        if (raw.isBlank()) return emptyList()
        val lines = stripMargins(normalizeCharacters(raw).lines(), layout) { it }
        val paragraphs = mutableListOf<String>()
        val buffer = mutableListOf<String>()
        fun flush() {
            joinWrappedLines(buffer).takeIf { it.isNotEmpty() }?.let(paragraphs::add)
            buffer.clear()
        }
        for (line in lines) {
            when {
                line.isBlank() -> flush()
                buffer.isNotEmpty() && startsNewParagraph(buffer.last(), line) -> {
                    flush()
                    buffer += line.trim()
                }
                else -> buffer += line.trim()
            }
        }
        flush()
        return paragraphs.map(::fixHyphens).filter { it.isNotBlank() }
    }

    /**
     * Une una palabra cortada entre el final de una página y el principio de la siguiente
     * («inter-» | «nacional» → «internacional»). Devuelve las dos páginas corregidas.
     */
    fun mergeAcrossPages(left: List<String>, right: List<String>): Pair<List<String>, List<String>> {
        if (left.isEmpty() || right.isEmpty()) return left to right
        val match = trailingHyphenWord.find(left.last()) ?: return left to right
        val first = right.first().trim()
        val word = first.takeWhile { !it.isWhitespace() }
        if (word.isEmpty() || !word.first().isLowerCase()) return left to right
        val prefix = match.groupValues[1]
        val stem = match.groupValues[2]
        val remainder = first.removePrefix(word).trimStart(' ')
        val newLeft = left.dropLast(1) + "$prefix$stem$word".trimEnd()
        val newRight = if (remainder.isEmpty()) right.drop(1) else listOf(remainder) + right.drop(1)
        return newLeft to newRight
    }

    /** Como [mergeAcrossPages], pero con párrafos con nivel: solo se unen dos párrafos de texto normal. */
    fun mergeBlocksAcrossPages(left: List<TextBlock>, right: List<TextBlock>): Pair<List<TextBlock>, List<TextBlock>> {
        val last = left.lastOrNull() ?: return left to right
        val first = right.firstOrNull() ?: return left to right
        if (last.level != 0 || first.level != 0) return left to right
        val (l, r) = mergeAcrossPages(listOf(last.text), listOf(first.text))
        return left.dropLast(1) + TextBlock(l.single()) to r.map { TextBlock(it) } + right.drop(1)
    }

    /** Quita las cabeceras, pies y números de página de las líneas (con geometría) de una página. */
    fun stripMargins(lines: List<TextLine>, layout: DocumentLayout): List<TextLine> =
        stripMargins(lines, layout) { it.text }

    /** Ligaduras (ﬁ → fi), comillas tipográficas simples y espacios raros. */
    fun normalizeCharacters(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace(' ', ' ')
        .replace("­", "")
        .replace('‘', '\'')
        .replace('’', '\'')

    private fun count(counter: MutableMap<String, Int>, line: String) {
        if (isPageNumber(line)) return
        val norm = normalizeLine(line)
        if (norm.isEmpty()) return
        counter[norm] = (counter[norm] ?: 0) + 1
        val fuzzy = fuzzyKey(line)
        if (fuzzy != norm) counter[fuzzy] = (counter[fuzzy] ?: 0) + 1
    }

    private fun normalizeLine(line: String) = line.trim().replace(whitespace, " ").lowercase()

    private fun fuzzyKey(line: String) = normalizeLine(line).replace(digits, "#")

    private fun isPageNumber(line: String): Boolean {
        val stripped = line.trim()
        return stripped.isNotEmpty() && pageNumber.matches(stripped)
    }

    private fun isRepeated(line: String, set: Set<String>) = normalizeLine(line) in set || fuzzyKey(line) in set

    private fun <T> stripMargins(lines: List<T>, layout: DocumentLayout, text: (T) -> String): List<T> {
        val result = lines.toMutableList()
        fun dropTop() = result.firstOrNull { text(it).isNotBlank() }
            ?.let { isRepeated(text(it), layout.headerLines) || isPageNumber(text(it)) } == true
        fun dropBottom() = result.lastOrNull { text(it).isNotBlank() }
            ?.let { isRepeated(text(it), layout.footerLines) || isPageNumber(text(it)) } == true
        while (result.isNotEmpty() && (text(result.first()).isBlank() || dropTop())) result.removeAt(0)
        while (result.isNotEmpty() && (text(result.last()).isBlank() || dropBottom())) result.removeAt(result.lastIndex)
        return result
    }

    private fun startsNewParagraph(previous: String, raw: String): Boolean {
        val next = raw.trim()
        if (Regex("^\\s{2,}\\S").containsMatchIn(raw)) return true
        val endsSentence = sentenceEnd.containsMatchIn(previous.trim())
        return endsSentence && (next.first().isUpperCase() || next.first().isDigit() || next.first() in "¿¡«\"—")
    }

    private fun joinWrappedLines(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        val text = StringBuilder(lines.first().trim())
        for (line in lines.drop(1)) {
            val part = line.trim()
            if (part.isEmpty()) continue
            val match = lineHyphenSuffix.find(text)
            if (match != null && part.first().isLowerCase()) {
                // Leer el grupo antes de modificar el StringBuilder sobre el que se buscó.
                val stem = match.groupValues[1]
                text.setLength(match.range.first)
                text.append(stem).append(part)
            } else {
                text.append(' ').append(part)
            }
        }
        return fixHyphens(text.toString().replace(whitespace, " ").trim())
    }

    /** Une las palabras partidas por guion que hayan quedado dentro de un párrafo. */
    fun fixHyphens(input: String): String {
        var text = input
        repeat(MAX_HYPHEN_PASSES) {
            val updated = text.replace(hyphenBreak, "$1$2").replace(hyphenBreakSpaced, "$1$2")
            if (updated == text) return text
            text = updated
        }
        return text
    }
}
