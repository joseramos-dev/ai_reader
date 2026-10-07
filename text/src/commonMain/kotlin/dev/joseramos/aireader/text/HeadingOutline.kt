package dev.joseramos.aireader.text

/** Título encontrado en el texto: página (base 1), nivel tipográfico (1 es el mayor) y texto. */
data class PageHeading(val page: Int, val level: Int, val text: String)

/**
 * Índice con temas y subtemas a partir de los títulos del texto (los que [LayoutAnalyzer] reconoce
 * por su tamaño de letra o su negrita), para los PDF sin índice propio:
 * - Los capítulos son el nivel de título más grande que aparece en al menos dos páginas (así el
 *   título de la portada, que solo sale una vez, no cuenta).
 * - Los dos niveles siguientes son los apartados y subapartados.
 * - Una etiqueta suelta («Tema 3», «Capítulo IV», «12») seguida de su título en la misma página
 *   se une a él: «Tema 3. La célula».
 */
object HeadingOutline {
    private const val MIN_CHAPTERS = 2
    private const val MIN_MAX_CHAPTERS = 40
    private const val MAX_DEPTH = 3
    private const val MAX_ENTRIES = 600
    private const val MAX_TITLE = 120
    private const val MAX_LABEL = 25

    /** Direcciones web y de correo (la editorial en la portada), que no son títulos. */
    private val web = Regex("(https?://|www\\.|@|^\\S+\\.(com|org|net|es|ar|mx|edu|info)\\b)", RegexOption.IGNORE_CASE)

    private val label = Regex(
        "^((cap[ií]tulo|tema|parte|libro|unidad|lecci[oó]n|bloque|m[oó]dulo|secci[oó]n|" +
            "chapter|part|book|unit|lesson|section)\\s+[\\p{L}\\d.]+|[IVXLCDM]+|\\d{1,3})\\.?$",
        RegexOption.IGNORE_CASE
    )

    fun build(headings: List<PageHeading>, pageCount: Int): List<DetectedChapter> {
        val usable = combineLabels(
            headings.filter { heading ->
                heading.text.any { it.isLetterOrDigit() } && !web.containsMatchIn(heading.text)
            }
        )
        val pagesByLevel = usable.groupBy { it.level }.mapValues { (_, list) -> list.map { it.page }.toSet().size }
        val countByLevel = usable.groupingBy { it.level }.eachCount()
        val maxChapters = maxOf(MIN_MAX_CHAPTERS, pageCount / 2)
        val top = pagesByLevel.keys.sorted().firstOrNull { level ->
            pagesByLevel.getValue(level) >= MIN_CHAPTERS && countByLevel.getValue(level) <= maxChapters
        } ?: return emptyList()

        var levels = (listOf(top) + pagesByLevel.keys.filter { it > top }.sorted()).take(MAX_DEPTH)
        // Con demasiadas entradas, el menú deja de ser útil: se quitan los niveles más finos.
        while (levels.size > 1 && levels.sumOf { countByLevel.getValue(it) } > MAX_ENTRIES) levels = levels.dropLast(1)
        val depthOf = levels.withIndex().associate { (depth, level) -> level to depth }

        var seenChapter = false
        return usable.mapNotNull { heading ->
            val depth = depthOf[heading.level] ?: return@mapNotNull null
            if (depth == 0) seenChapter = true
            // Lo que va antes del primer capítulo (prólogo, introducción) cuelga de la raíz.
            DetectedChapter(title(heading.text), heading.page, if (seenChapter) depth else 0)
        }
    }

    /** El texto de un título como entrada del índice: en una línea y sin pasarse de largo. */
    fun title(text: String): String {
        val parts = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val joined = StringBuilder()
        parts.forEachIndexed { i, part ->
            if (i > 0) joined.append(if (isLabel(parts[i - 1])) ". " else " ")
            joined.append(part)
        }
        return joined.toString()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\.\\.\\s"), ". ")
            .take(MAX_TITLE)
            .trim()
            .trimEnd(':', ';', ',')
    }

    private fun combineLabels(headings: List<PageHeading>): List<PageHeading> {
        val result = mutableListOf<PageHeading>()
        var i = 0
        while (i < headings.size) {
            val heading = headings[i]
            val next = headings.getOrNull(i + 1)
            if (next != null && isLabel(heading.text) && next.page == heading.page && next.level >= heading.level) {
                result += heading.copy(text = "${heading.text}\n${next.text}")
                i += 2
            } else {
                result += heading
                i++
            }
        }
        return result
    }

    private fun isLabel(text: String) = text.length <= MAX_LABEL && label.matches(text.trim())
}
