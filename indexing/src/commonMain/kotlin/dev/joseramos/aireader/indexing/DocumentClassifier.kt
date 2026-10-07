package dev.joseramos.aireader.indexing

import dev.joseramos.aireader.core.data.db.DocumentType

/** Resultado de la heurística: el tipo más probable y si la señal es clara. */
data class Classification(val type: DocumentType, val conclusive: Boolean)

/**
 * Clasificación local (sin red) del tipo de documento a partir del texto limpio, con señales
 * sencillas de cada clase (docs/02-diseno-tecnico.md §6.4). Si no es concluyente, la indexación
 * puede pedir confirmación a la IA.
 */
object DocumentClassifier {
    private const val MIN_PAGES = 4
    private const val CONCLUSIVE_SCORE = 3.0
    private const val CONCLUSIVE_MARGIN = 1.5
    private const val DIALOGUE_RATIO_STRONG = 0.12
    private const val DIALOGUE_RATIO_WEAK = 0.04
    private const val CITATIONS_STRONG = 15
    private const val CITATIONS_WEAK = 4
    private const val QUESTIONS_STRONG = 8
    private const val WEAK = 1.0
    private const val MEDIUM = 2.0
    private const val STRONG = 3.0

    private val dialogueStart = Regex("^\\s*[—–―«“\"]")

    // Sin `(?i)` dentro del patrón: en la JVM de escritorio solo ignora mayúsculas en ASCII («METODOLOGÍA» no casaría).
    // Con RegexOption.IGNORE_CASE, Kotlin añade UNICODE_CASE.
    private val ignoreCaseLines = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    private val scientificSections = listOf(
        Regex("^\\s*(abstract|resumen)\\s*$", ignoreCaseLines),
        Regex("^\\s*(\\d+\\.?\\s*)?(introducci[oó]n|introduction)\\s*$", ignoreCaseLines),
        Regex("^\\s*(\\d+\\.?\\s*)?(m[eé]todos?|metodolog[ií]a|materiales y m[eé]todos|methods)\\s*$", ignoreCaseLines),
        Regex("^\\s*(\\d+\\.?\\s*)?(resultados|results)\\s*$", ignoreCaseLines),
        Regex("^\\s*(\\d+\\.?\\s*)?(discusi[oó]n|discussion)\\s*$", ignoreCaseLines),
        Regex("^\\s*(referencias|bibliograf[ií]a|references)\\s*$", ignoreCaseLines)
    )
    private val keywords = Regex("(palabras clave|keywords)\\s*:", RegexOption.IGNORE_CASE)
    private val doi = Regex("\\bdoi\\s*:?\\s*10\\.\\d{4,}", RegexOption.IGNORE_CASE)
    private val chapterHeading = Regex("^\\s*(cap[ií]tulo|parte|libro)\\b|^[IVXLC]+\\.?$", RegexOption.IGNORE_CASE)
    private val authorYear =
        Regex("\\(\\p{Lu}[\\p{L}'-]+(?: et al\\.| y \\p{Lu}[\\p{L}'-]+)?,? (?:19|20)\\d{2}[a-z]?\\)")
    private val numbered = Regex("\\[\\d{1,3}(?:[,–-]\\s*\\d{1,3})*]")
    private val educationalHeadings = Regex(
        "^\\s*(tema|unidad|lecci[oó]n|m[oó]dulo)\\s+\\d+|^\\s*(ejercicios|actividades|autoevaluaci[oó]n|" +
            "objetivos( de aprendizaje)?|soluciones|recuerda|para saber m[aá]s)\\b",
        ignoreCaseLines
    )
    private val numberedQuestion = Regex("(?m)^\\s*\\d{1,2}[.)]\\s+.{5,200}\\?\\s*$")

    /**
     * @param pages párrafos de una muestra de páginas (las primeras y algunas intermedias).
     * @param pageCount páginas del PDF completo.
     * @param chapterTitles títulos de los capítulos detectados.
     */
    fun classify(pages: List<List<String>>, pageCount: Int, chapterTitles: List<String>): Classification {
        val paragraphs = pages.flatten().filter { it.isNotBlank() }
        if (pageCount < MIN_PAGES ||
            paragraphs.isEmpty()
        ) {
            return Classification(DocumentType.GENERIC, conclusive = true)
        }
        val text = pages.joinToString("\n") { it.joinToString("\n") }

        val scores = mapOf(
            DocumentType.LITERATURE to literatureScore(paragraphs, chapterTitles),
            DocumentType.SCIENTIFIC to scientificScore(text),
            DocumentType.EDUCATIONAL to educationalScore(text, chapterTitles)
        )
        val ranked = scores.entries.sortedByDescending { it.value }
        val (best, bestScore) = ranked[0]
        val runnerUp = ranked[1].value
        return when {
            bestScore < WEAK -> Classification(DocumentType.GENERIC, conclusive = false)
            bestScore >= CONCLUSIVE_SCORE && bestScore - runnerUp >= CONCLUSIVE_MARGIN ->
                Classification(best, conclusive = true)
            else -> Classification(best, conclusive = false)
        }
    }

    private fun literatureScore(paragraphs: List<String>, chapterTitles: List<String>): Double {
        val dialogue = paragraphs.count { dialogueStart.containsMatchIn(it) }.toDouble() / paragraphs.size
        var score = when {
            dialogue >= DIALOGUE_RATIO_STRONG -> STRONG
            dialogue >= DIALOGUE_RATIO_WEAK -> MEDIUM
            else -> 0.0
        }
        if (chapterTitles.any { chapterHeading.containsMatchIn(it) }) {
            score += WEAK
        }
        return score
    }

    private fun scientificScore(text: String): Double {
        var score = scientificSections.count { it.containsMatchIn(text) } * WEAK
        if (keywords.containsMatchIn(text)) score += WEAK
        if (doi.containsMatchIn(text)) score += MEDIUM
        val citations = authorYear.findAll(text).count() + numbered.findAll(text).count()
        score += when {
            citations >= CITATIONS_STRONG -> STRONG
            citations >= CITATIONS_WEAK -> WEAK
            else -> 0.0
        }
        return score
    }

    private fun educationalScore(text: String, chapterTitles: List<String>): Double {
        val headings = educationalHeadings.findAll(text).count() +
            chapterTitles.count { educationalHeadings.containsMatchIn(it) }
        val questions = numberedQuestion.findAll(text).count()
        return headings.coerceAtMost(STRONG.toInt()) * WEAK + if (questions >= QUESTIONS_STRONG) MEDIUM else 0.0
    }
}
