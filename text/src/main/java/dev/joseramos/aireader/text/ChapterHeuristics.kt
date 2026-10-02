package dev.joseramos.aireader.text

/** Capítulo detectado: título y página de inicio (base 1). */
data class DetectedChapter(val title: String, val startPage: Int)

/**
 * Detección de capítulos sin índice: busca, en las primeras líneas de cada página, encabezados
 * cortos del tipo «Capítulo 3», «CAPÍTULO IV», «Parte primera», «III» o «12. El regreso».
 */
object ChapterHeuristics {
    private const val MAX_HEADING_LENGTH = 70
    private const val LINES_TO_CHECK = 3
    private const val MIN_CHAPTERS = 2

    private val keyword = Regex(
        "^(cap[ií]tulo|parte|libro|secci[oó]n|pr[oó]logo|ep[ií]logo|introducci[oó]n|conclusi[oó]n|ap[eé]ndice)\\b.*",
        RegexOption.IGNORE_CASE
    )
    private val romanOnly = Regex("^[IVXLC]{1,7}\\.?$")
    private val numbered = Regex("^\\d{1,3}\\.?\\s+\\p{Lu}.{0,60}$")

    /** [firstLines] contiene, por página (en orden), sus primeras líneas no vacías. */
    fun detect(firstLines: List<List<String>>): List<DetectedChapter> {
        val chapters = firstLines.mapIndexedNotNull { index, lines ->
            lines.take(LINES_TO_CHECK)
                .map { it.trim() }
                .firstOrNull { isHeading(it) }
                ?.let { DetectedChapter(title(it, lines), index + 1) }
        }
        return if (chapters.size >= MIN_CHAPTERS) chapters else emptyList()
    }

    private fun isHeading(line: String): Boolean {
        if (line.isEmpty() || line.length > MAX_HEADING_LENGTH) return false
        return keyword.matches(line) ||
            (romanOnly.matches(line) && SpeechNormalizer.romanToInt(line.trimEnd('.')) != null) ||
            numbered.matches(line)
    }

    /** «Capítulo 3» suele ir seguido del título real en la línea siguiente: se combinan. */
    private fun title(heading: String, lines: List<String>): String {
        val next = lines.map { it.trim() }.dropWhile { it != heading }.drop(1).firstOrNull()
        val short = heading.length < 16 && next != null && next.length in 2..MAX_HEADING_LENGTH && !isHeading(next)
        return if (short) "$heading. $next" else heading
    }

    /** Respaldo cuando no hay ni índice ni encabezados: bloques de [pagesPerBlock] páginas. */
    fun blocks(pageCount: Int, pagesPerBlock: Int = 20): List<DetectedChapter> =
        (1..pageCount step pagesPerBlock).map { start ->
            DetectedChapter("Páginas $start–${minOf(pageCount, start + pagesPerBlock - 1)}", start)
        }
}
