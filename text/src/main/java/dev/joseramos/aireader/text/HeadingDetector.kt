package dev.joseramos.aireader.text

import java.text.Normalizer

/**
 * Reconoce los párrafos que son títulos (capítulos, partes, secciones) para destacarlos en el modo
 * texto. Un título es corto, no termina como una frase y además coincide con el título de un
 * capítulo detectado, empieza por una palabra típica de encabezado («Capítulo», «Chapter»,
 * «Tema»…), es un número (romano o arábigo) o una sección numerada, o está todo en mayúsculas.
 */
object HeadingDetector {
    private const val MAX_CHARS = 90
    private const val MAX_WORDS = 12
    private const val MIN_TITLE_MATCH = 3

    private val keyword = Regex(
        "^(cap[ií]tulo|parte|libro|tomo|tema|unidad|lecci[oó]n|secci[oó]n|ap[eé]ndice|anexo|pr[oó]logo|" +
            "ep[ií]logo|introducci[oó]n|conclusi[oó]n|conclusiones|[ií]ndice|bibliograf[ií]a|referencias|" +
            "chapter|part|book|section|appendix|prologue|epilogue|introduction|conclusion|contents|references)\\b",
        RegexOption.IGNORE_CASE
    )
    private val numberOnly = Regex("^([IVXLCDM]+|\\d{1,3})\\.?$")
    private val numberedSection = Regex("^\\d{1,2}(\\.\\d{1,2})*\\.?\\s+\\p{Lu}")
    private val sentenceEnd = Regex("[.,;:]$")

    /** [chapterTitles]: títulos de los capítulos que empiezan en la página del párrafo. */
    fun isHeading(paragraph: String, chapterTitles: List<String> = emptyList()): Boolean {
        val text = paragraph.trim()
        if (text.isEmpty() || text.length > MAX_CHARS || text.split(Regex("\\s+")).size > MAX_WORDS) return false
        if (text.first() in "—–-«\"“") return false
        if (numberOnly.matches(text)) return true
        if (sentenceEnd.containsMatchIn(text)) return false
        val key = normalize(text)
        return chapterTitles.any { title -> matchesTitle(key, normalize(title)) } ||
            keyword.containsMatchIn(text) ||
            numberedSection.containsMatchIn(text) ||
            isAllCaps(text)
    }

    private fun matchesTitle(text: String, title: String): Boolean = text.length >= MIN_TITLE_MATCH &&
        title.length >= MIN_TITLE_MATCH &&
        (title.contains(text) || text.contains(title))

    private fun isAllCaps(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.length >= 2 && letters.all { it.isUpperCase() }
    }

    private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
}
