package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.db.ChunkDao
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Evaluación de la búsqueda del RAG (F7): un fichero JSON con preguntas sobre libros de la
// biblioteca y las páginas donde está la respuesta. Para cada pregunta se mira en qué puesto aparece
// el primer fragmento que toca alguna de esas páginas, con cada búsqueda y con la fusión.

@Serializable
data class EvalQuestion(val question: String, val pages: List<Int>)

/** [title]: el libro se busca por título en la biblioteca (sin distinguir mayúsculas; vale parte del título). */
@Serializable
data class EvalBook(val title: String, val questions: List<EvalQuestion>)

@Serializable
data class EvalSet(val books: List<EvalBook>) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): EvalSet = json.decodeFromString(text)
    }
}

/** Proporción de preguntas resueltas entre los [k] primeros, para cada k, y rango recíproco medio. */
data class ModeScore(val recallAt: Map<Int, Double>, val mrr: Double)

/** Puesto (base 1) del primer fragmento relevante en cada búsqueda, o `null` si no aparece. */
data class QuestionResult(
    val book: String,
    val question: String,
    val pages: List<Int>,
    val vectorRank: Int?,
    val textRank: Int?,
    val hybridRank: Int?
)

data class EvalReport(
    val vector: ModeScore,
    val text: ModeScore,
    val hybrid: ModeScore,
    val results: List<QuestionResult>,
    /** Libros del fichero que no están en la biblioteca (sus preguntas no cuentan). */
    val missingBooks: List<String>
)

object RagScoring {
    val KS = listOf(1, 3, 8, 20)

    /** Puesto del primer fragmento cuyas páginas tocan alguna de [expected]. */
    fun firstRelevantRank(ranking: List<IntRange>, expected: Set<Int>): Int? =
        ranking.indexOfFirst { range -> expected.any { it in range } }.takeIf { it >= 0 }?.plus(1)

    fun score(ranks: List<Int?>, ks: List<Int> = KS): ModeScore {
        if (ranks.isEmpty()) return ModeScore(ks.associateWith { 0.0 }, 0.0)
        val recall = ks.associateWith { k -> ranks.count { it != null && it <= k }.toDouble() / ranks.size }
        val mrr = ranks.sumOf { rank -> rank?.let { 1.0 / it } ?: 0.0 } / ranks.size
        return ModeScore(recall, mrr)
    }
}

class RagEvaluator(
    private val books: BookRepository,
    private val retriever: HybridRetriever,
    private val chunkDao: ChunkDao
) {
    suspend fun run(set: EvalSet): EvalReport {
        val library = books.observeBooks().first()
        val results = mutableListOf<QuestionResult>()
        val missing = mutableListOf<String>()
        try {
            for (evalBook in set.books) {
                val book = library.firstOrNull { it.title.contains(evalBook.title.trim(), ignoreCase = true) }
                if (book == null) {
                    missing += evalBook.title
                    continue
                }
                for (q in evalBook.questions) {
                    val candidates = retriever.candidates(book.id, q.question)
                    val hybrid = reciprocalRankFusion(listOf(candidates.vector, candidates.text))
                    val ids = (candidates.vector + candidates.text + hybrid).distinct()
                    val pagesById = chunkDao.getByIds(ids).associate { it.id to (it.startPage..it.endPage) }
                    val expected = q.pages.toSet()
                    val rank = { ranking: List<Long> ->
                        RagScoring.firstRelevantRank(ranking.mapNotNull(pagesById::get), expected)
                    }
                    results += QuestionResult(
                        book = book.title,
                        question = q.question,
                        pages = q.pages,
                        vectorRank = rank(candidates.vector),
                        textRank = rank(candidates.text),
                        hybridRank = rank(hybrid)
                    )
                }
            }
        } finally {
            retriever.release()
        }
        return EvalReport(
            vector = RagScoring.score(results.map { it.vectorRank }),
            text = RagScoring.score(results.map { it.textRank }),
            hybrid = RagScoring.score(results.map { it.hybridRank }),
            results = results,
            missingBooks = missing
        )
    }
}
