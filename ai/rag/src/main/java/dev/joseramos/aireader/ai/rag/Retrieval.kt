package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.db.ChunkEntity
import dev.joseramos.aireader.text.SpeechNormalizer

/**
 * Vectores de un libro en memoria, en un único `FloatArray` contiguo. Para un libro (unos miles
 * de fragmentos) la búsqueda exacta por producto escalar tarda milisegundos: no hace falta un
 * índice aproximado. Los vectores están normalizados, así que el producto escalar es el coseno.
 */
class VectorIndex(private val ids: LongArray, private val vectors: FloatArray, private val dimensions: Int) {
    val size: Int get() = ids.size

    /** Los [k] fragmentos más parecidos a [query], de más a menos; solo entre [allowed] si se indica. */
    fun search(query: FloatArray, k: Int, allowed: Set<Long>? = null): List<Long> =
        searchScored(query, k, allowed).map { it.first }

    /** Como [search], con la similitud (coseno) de cada fragmento. */
    fun searchScored(query: FloatArray, k: Int, allowed: Set<Long>? = null): List<Pair<Long, Float>> {
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
            .map { ids[it] to scores[it] }
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

/** Distancia al mejor fragmento por significado a partir de la cual uno se considera poco relevante. */
private const val SEMANTIC_MARGIN = 0.08f

/** Primeros resultados de la búsqueda por palabras que siempre se consideran relevantes. */
private const val STRONG_TEXT_HITS = 3

/** Fragmentos que se mandan como mínimo, aunque los demás parezcan poco relevantes. */
private const val MIN_FRAGMENTS = 4

/**
 * Quita de [fused] los fragmentos poco relevantes, que gastan tokens sin ayudar a responder: los
 * que la búsqueda por significado deja lejos del mejor y la de palabras no pone entre los primeros.
 * Sin búsqueda por significado no se quita nada (no hay con qué medir). Si quedan menos de
 * [MIN_FRAGMENTS], se completan con los siguientes de [fused].
 */
fun dropWeakMatches(fused: List<Long>, candidates: Candidates): List<Long> {
    val best = candidates.vectorScores.firstOrNull() ?: return fused
    val close = candidates.vector.filterIndexed { i, _ -> candidates.vectorScores[i] >= best - SEMANTIC_MARGIN }
    val strong = (close + candidates.text.take(STRONG_TEXT_HITS)).toSet()
    val kept = fused.filter { it in strong }
    return if (kept.size >=
        MIN_FRAGMENTS
    ) {
        kept
    } else {
        kept + fused.filterNot { it in strong }.take(MIN_FRAGMENTS - kept.size)
    }
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
            // `chunks_fts` usa `unicode61` con `remove_diacritics`: con o sin tilde, la palabra casa igual.
            .map { if (it.length >= PREFIX_LENGTH) "$it*" else it }
        return terms.takeIf { it.isNotEmpty() }?.joinToString(" OR ")
    }
}

/** Tipo de pregunta: sobre el libro en conjunto (se responde con resúmenes) o sobre algo concreto. */
enum class QueryScope { GLOBAL, SPECIFIC }

object QueryRouter {
    private val globalPatterns = listOf(
        "de qué trata", "de que trata", "de qué va", "de que va", "resume", "resumen", "en general",
        "idea principal", "ideas principales", "tema principal", "temas principales", "tesis", "argumento del libro",
        "conclusión del libro", "conclusiones", "mensaje del libro", "estructura del libro"
    )

    private val summaryWords = listOf(
        "resum", "resúm", "de qué trata", "de que trata", "de qué va", "de que va", "qué pasa", "que pasa", "qué pasó",
        "que pasó", "que paso", "qué ocurr", "que ocurr", "qué sucede", "que sucede", "qué sucedió", "que sucedio",
        "cuéntame", "cuentame"
    )
    private val currentChapter = Regex(
        "\\b(este|esta|el actual|actual)\\s*(cap[ií]tulo|tema|parte)|(cap[ií]tulo|tema) actual|" +
            "cap[ií]tulo (en el )?que (estoy|leo)"
    )
    private val previousChapter =
        Regex("(cap[ií]tulo|tema)\\s+(anterior|pasado|previo)|anterior\\s+(cap[ií]tulo|tema)")
    private val nextChapter =
        Regex("(cap[ií]tulo|tema)\\s+(siguiente|pr[oó]ximo)|(siguiente|pr[oó]ximo)\\s+(cap[ií]tulo|tema)")
    private val numberedChapter = Regex("\\b(?:cap[ií]tulo|tema)\\s+(\\d{1,3}|[ivxlc]{1,7})\\b")

    /** Heurística barata: ante la duda, la pregunta se trata como concreta. */
    fun route(question: String): QueryScope {
        val q = question.lowercase()
        return if (globalPatterns.any { it in q }) QueryScope.GLOBAL else QueryScope.SPECIFIC
    }

    /** Palabras que remiten a algo dicho antes en la conversación. */
    private val referenceWords = setOf(
        "él", "ella", "ellos", "ellas", "eso", "esto", "ese", "esa", "esos", "esas", "este", "esta",
        "aquel", "aquella", "aquello", "su", "sus", "entonces", "luego", "después", "despues", "antes",
        "mismo", "misma", "anterior", "otro", "otra", "ahí", "allí", "allá"
    )
    private val word = Regex("[\\p{L}\\p{N}]+")

    /**
     * Si la pregunta depende de la conversación anterior («¿y después?», «¿por qué lo hizo él?») y
     * hay que reformularla antes de buscar. Si no, se busca con ella tal cual y se ahorra una llamada
     * a la API. Ante la duda (preguntas muy cortas), se reformula.
     */
    fun needsContext(question: String): Boolean {
        val words = word.findAll(question.lowercase()).map { it.value }.toList()
        return words.size < MIN_STANDALONE_WORDS || words.first() == "y" || words.any { it in referenceWords }
    }

    /**
     * Si la pregunta pide resumir un capítulo concreto («resume este capítulo», «¿qué pasó en el
     * capítulo anterior?», «resumen del tema 3»).
     */
    fun chapterSummary(question: String): ChapterRef? {
        val q = question.lowercase()
        if (summaryWords.none { it in q }) return null
        if (previousChapter.containsMatchIn(q)) return ChapterRef.Previous
        if (nextChapter.containsMatchIn(q)) return ChapterRef.Next
        if (currentChapter.containsMatchIn(q)) return ChapterRef.Current
        val number = numberedChapter.find(q)?.groupValues?.get(1) ?: return null
        return chapterNumber(number)?.let { ChapterRef.Number(it) }
    }

    internal fun chapterNumber(text: String): Int? = text.toIntOrNull() ?: SpeechNormalizer.romanToInt(text.uppercase())
}

/**
 * Encuentra en el índice el capítulo al que se refiere una pregunta, según la página que se está
 * leyendo. Los libros divididos en partes reinician la numeración en cada una («Parte 2. Capítulo 1»):
 * «el capítulo 3» es entonces el 3 de la parte que se lee, no el tercero del índice.
 */
object ChapterResolver {
    private val partHeading = Regex("^\\s*(parte|libro|part|book)\\b", RegexOption.IGNORE_CASE)
    private val bareHeading = Regex("^\\s*(parte|libro|part|book)\\s+\\S+\\s*$", RegexOption.IGNORE_CASE)
    private val titledNumber =
        Regex("\\b(?:cap[ií]tulo|tema|chapter)\\s+(\\d{1,3}|[ivxlc]{1,7})\\b", RegexOption.IGNORE_CASE)

    /** Índice del capítulo que contiene [page] (el último que empieza antes, si cae entre dos). */
    fun currentIndex(chapters: List<Chapter>, page: Int): Int = chapters.indexOfLast { page >= it.startPage }

    fun resolve(ref: ChapterRef, chapters: List<Chapter>, page: Int): Chapter? {
        val current = currentIndex(chapters, page)
        return when (ref) {
            ChapterRef.Current -> chapters.getOrNull(current)
            ChapterRef.Previous -> if (current < 0) null else neighbour(chapters, current, -1)
            ChapterRef.Next -> neighbour(chapters, current, 1)
            is ChapterRef.Number -> byNumber(chapters, ref.number, current)
        }
    }

    /** Parte a la que pertenece [chapter] («Parte 6»), si el libro está dividido en partes. */
    fun partOf(chapters: List<Chapter>, chapter: Chapter): String? {
        val index = chapters.indexOf(chapter)
        if (index < 0) return null
        val part = (index downTo 0).firstOrNull { partHeading.containsMatchIn(chapters[it].title) } ?: return null
        return chapters[part].title.substringBefore('.').trim().takeIf { it.isNotEmpty() }
    }

    /** El capítulo de al lado, saltando las páginas sueltas que solo anuncian una parte («Parte 3»). */
    private fun neighbour(chapters: List<Chapter>, from: Int, step: Int): Chapter? {
        var i = from + step
        while (i in chapters.indices && bareHeading.matches(chapters[i].title)) i += step
        return chapters.getOrNull(i)
    }

    private fun byNumber(chapters: List<Chapter>, number: Int, current: Int): Chapter? {
        val titled = chapters.indices.filter { i ->
            titledNumber.find(chapters[i].title)?.groupValues?.get(1)?.let(QueryRouter::chapterNumber) == number
        }
        if (titled.isEmpty()) return chapters.firstOrNull { it.number == number }
        val part = partStart(chapters, current)
        val samePart = titled.firstOrNull { partStart(chapters, it) == part }
        return chapters[samePart ?: titled.first()]
    }

    private fun partStart(chapters: List<Chapter>, index: Int): Int =
        (index downTo 0).firstOrNull { it in chapters.indices && partHeading.containsMatchIn(chapters[it].title) } ?: -1
}

private const val MIN_STANDALONE_WORDS = 4

/** Varios fragmentos seguidos del libro unidos en uno. */
data class MergedChunk(val text: String, val startPage: Int, val endPage: Int, val chapterId: Long?)

/**
 * Une los fragmentos consecutivos del mismo capítulo, en orden de lectura, quitando la frase que el
 * troceado repite al principio de cada uno (el solapamiento de `Chunker`): menos tokens repetidos
 * y un contexto más seguido para el modelo.
 */
object ChunkMerger {
    private const val MIN_OVERLAP_CHARS = 10
    private const val MAX_OVERLAP_CHARS = 1_500

    fun merge(chunks: List<ChunkEntity>): List<MergedChunk> {
        val merged = mutableListOf<MergedChunk>()
        var lastOrdinal = Int.MIN_VALUE
        for (chunk in chunks.sortedBy { it.ordinal }) {
            val previous = merged.lastOrNull()
            if (previous != null && chunk.ordinal == lastOrdinal + 1 && chunk.chapterId == previous.chapterId) {
                merged[merged.lastIndex] = previous.copy(
                    text = join(previous.text, chunk.text),
                    endPage = maxOf(previous.endPage, chunk.endPage)
                )
            } else {
                merged += MergedChunk(chunk.text, chunk.startPage, chunk.endPage, chunk.chapterId)
            }
            lastOrdinal = chunk.ordinal
        }
        return merged
    }

    /** [b] a continuación de [a], sin repetir el final de [a] con el que empieza [b]. */
    internal fun join(a: String, b: String): String {
        for (length in minOf(a.length, b.length, MAX_OVERLAP_CHARS) downTo MIN_OVERLAP_CHARS) {
            if (a.regionMatches(a.length - length, b, 0, length)) return a + b.substring(length)
        }
        return a + "\n\n" + b
    }
}

/** Capítulo al que se refiere una pregunta: el que se está leyendo, el anterior, el siguiente o uno por su número. */
sealed interface ChapterRef {
    data object Current : ChapterRef

    data object Previous : ChapterRef

    data object Next : ChapterRef

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
