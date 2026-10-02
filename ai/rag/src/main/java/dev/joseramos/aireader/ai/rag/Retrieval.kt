package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.text.SpeechNormalizer
import java.text.Normalizer

/**
 * Vectores de un libro en memoria, en un único `FloatArray` contiguo. Para un libro (unos miles
 * de fragmentos) la búsqueda exacta por producto escalar tarda milisegundos: no hace falta un
 * índice aproximado. Los vectores están normalizados, así que el producto escalar es el coseno.
 */
class VectorIndex(private val ids: LongArray, private val vectors: FloatArray, private val dimensions: Int) {
    val size: Int get() = ids.size

    /** Los [k] fragmentos más parecidos a [query], de más a menos; solo entre [allowed] si se indica. */
    fun search(query: FloatArray, k: Int, allowed: Set<Long>? = null): List<Long> {
        require(query.size == dimensions) { "Dimensión ${query.size}, se esperaba $dimensions" }
        val scores = FloatArray(ids.size) { row ->
            var dot = 0f
            val base = row * dimensions
            for (d in 0 until dimensions) dot += vectors[base + d] * query[d]
            dot
        }
        return scores.indices
            .filter { allowed == null || ids[it] in allowed }
            .sortedByDescending { scores[it] }
            .take(k)
            .map { ids[it] }
    }
}

/**
 * Reciprocal Rank Fusion: combina listas ordenadas sumando `1 / (k + posición)`. Solo usa el
 * orden de cada lista, así que no hace falta calibrar puntuaciones de búsquedas distintas.
 */
fun reciprocalRankFusion(rankings: List<List<Long>>, k: Int = 60): List<Long> {
    val scores = mutableMapOf<Long, Double>()
    for (ranking in rankings) {
        ranking.forEachIndexed { position, id -> scores[id] = (scores[id] ?: 0.0) + 1.0 / (k + position + 1) }
    }
    return scores.entries.sortedByDescending { it.value }.map { it.key }
}

/**
 * [k] elementos repartidos por igual a lo largo de [ids] (el primero incluido). Si ninguna búsqueda
 * encuentra nada (por ejemplo, «¿de qué trata?» sin vectores ni resúmenes), da una muestra de todo el libro.
 */
fun <T> evenlySpaced(ids: List<T>, k: Int): List<T> {
    if (k <= 0 || ids.isEmpty()) return emptyList()
    if (ids.size <= k) return ids
    return List(k) { i -> ids[(i.toLong() * ids.size / k).toInt()] }
}

/**
 * Convierte una pregunta en una consulta FTS4 segura: palabras significativas unidas con OR (sin
 * operadores ni comillas del usuario), con prefijo `*` en las largas para cubrir plurales.
 */
object FtsQuery {
    private val stopWords = setOf(
        "que", "qué", "como", "cómo", "cual", "cuál", "cuales", "cuáles", "donde", "dónde", "cuando", "cuándo",
        "quien", "quién", "por", "para", "con", "sin", "sobre", "entre", "desde", "hasta", "los", "las", "una",
        "unos", "unas", "del", "al", "el", "la", "lo", "le", "les", "se", "su", "sus", "es", "son", "fue", "ser",
        "hay", "dice", "autor", "libro", "capítulo", "esto", "esta", "este", "eso", "esa", "ese", "más", "muy",
        "pero", "porque", "según", "también", "the", "and"
    )
    private const val MIN_LENGTH = 3
    private const val PREFIX_LENGTH = 5
    private const val MAX_TERMS = 12

    fun from(question: String): String? {
        val terms = question.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= MIN_LENGTH && it !in stopWords }
            .distinct()
            .take(MAX_TERMS)
            .flatMap { word ->
                // El tokenizador de FTS4 no ignora tildes: se busca la palabra tal cual y sin tildes.
                listOf(word, stripAccents(word)).distinct().map { if (it.length >= PREFIX_LENGTH) "$it*" else it }
            }
        return terms.takeIf { it.isNotEmpty() }?.joinToString(" OR ")
    }

    private fun stripAccents(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}

/** Tipo de pregunta: sobre el libro en conjunto (se responde con resúmenes) o sobre algo concreto. */
enum class QueryScope { GLOBAL, SPECIFIC }

object QueryRouter {
    private val globalPatterns = listOf(
        "de qué trata", "de que trata", "de qué va", "de que va", "resume", "resumen", "en general",
        "idea principal", "ideas principales", "tema principal", "temas principales", "tesis", "argumento del libro",
        "conclusión del libro", "conclusiones", "mensaje del libro", "estructura del libro"
    )

    private val summaryWords = listOf("resum", "de qué trata", "de que trata", "de qué va", "de que va", "qué pasa en")
    private val currentChapter =
        Regex("\\b(este|esta|el actual|actual)\\s*(cap[ií]tulo|tema|parte)|(cap[ií]tulo|tema) actual")
    private val numberedChapter = Regex("\\b(?:cap[ií]tulo|tema)\\s+(\\d{1,3}|[ivxlc]{1,7})\\b")

    /** Heurística barata: ante la duda, la pregunta se trata como concreta. */
    fun route(question: String): QueryScope {
        val q = question.lowercase()
        return if (globalPatterns.any { it in q }) QueryScope.GLOBAL else QueryScope.SPECIFIC
    }

    /** Si la pregunta pide resumir un capítulo concreto («resume este capítulo», «resumen del tema 3»). */
    fun chapterSummary(question: String): ChapterRef? {
        val q = question.lowercase()
        if (summaryWords.none { it in q }) return null
        if (currentChapter.containsMatchIn(q)) return ChapterRef.Current
        val number = numberedChapter.find(q)?.groupValues?.get(1) ?: return null
        return (number.toIntOrNull() ?: SpeechNormalizer.romanToInt(number.uppercase()))?.let { ChapterRef.Number(it) }
    }
}

/** Capítulo al que se refiere una pregunta: el que se está leyendo o uno por su número. */
sealed interface ChapterRef {
    data object Current : ChapterRef

    data class Number(val number: Int) : ChapterRef
}

/** Fragmento enviado al modelo, numerado, con sus páginas. */
data class Fragment(val number: Int, val text: String, val startPage: Int, val endPage: Int, val chapter: String?)

/** Respuesta con las citas `[p. N]` comprobadas contra los fragmentos enviados. */
data class CitedAnswer(val text: String, val pages: List<Int>)

object CitationParser {
    private val citation = Regex("\\[p(?:ág)?\\.\\s*(\\d+)(?:\\s*[–-]\\s*(\\d+))?]")

    /**
     * Quita las citas a páginas que no estaban en ningún fragmento (serían inventadas) y devuelve
     * las páginas válidas, en orden de aparición y sin repetir.
     */
    fun validate(answer: String, fragments: List<Fragment>): CitedAnswer {
        val pages = mutableListOf<Int>()
        val cleaned = citation.replace(answer) { match ->
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toIntOrNull() ?: start
            val valid = fragments.any { start <= it.endPage && end >= it.startPage }
            if (valid) {
                if (start !in pages) pages += start
                match.value
            } else {
                ""
            }
        }
        return CitedAnswer(cleaned.replace(Regex(" +([.,;:])"), "$1").replace(Regex(" {2,}"), " ").trim(), pages)
    }
}
