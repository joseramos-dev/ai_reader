package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.ai.embeddings.Embedder
import dev.joseramos.aireader.ai.embeddings.VectorCodec
import dev.joseramos.aireader.core.data.db.ChunkDao
import dev.joseramos.aireader.core.data.db.ChunkEmbeddingDao
import dev.joseramos.aireader.core.data.db.ChunkEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Búsqueda híbrida en un libro: los 20 fragmentos más parecidos por significado (embeddings) y
 * los 20 mejores por palabras exactas (texto completo), fusionados con RRF. Las palabras exactas
 * rescatan nombres propios, cifras y términos raros que los embeddings confunden.
 */
/** Ids de fragmentos ordenados por la búsqueda vectorial y por la de texto completo. */
data class Candidates(val vector: List<Long>, val text: List<Long>)

@Singleton
class HybridRetriever @Inject constructor(
    private val chunkDao: ChunkDao,
    private val embeddingDao: ChunkEmbeddingDao,
    private val embedder: Embedder
) {
    private val mutex = Mutex()
    private var cached: Pair<String, VectorIndex>? = null

    suspend fun retrieve(bookId: String, question: String, k: Int = DEFAULT_K): List<ChunkEntity> {
        val candidates = candidates(bookId, question)
        val ids = reciprocalRankFusion(listOf(candidates.vector, candidates.text)).take(k)
        val byId = chunkDao.getByIds(ids).associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    /** Resultados de cada búsqueda por separado, antes de fusionarlos (para evaluar y ajustar). */
    suspend fun candidates(bookId: String, question: String): Candidates {
        val vectorHits = index(bookId).search(embedder.embedQuery(question), CANDIDATES)
        val textHits = FtsQuery.from(question)
            ?.let { query -> runCatching { chunkDao.searchText(bookId, query, CANDIDATES) }.getOrDefault(emptyList()) }
            ?.map { it.chunkId }
            .orEmpty()
        return Candidates(vectorHits, textHits)
    }

    /** Libera el modelo de la memoria al salir del chat. */
    suspend fun release() {
        mutex.withLock { cached = null }
        embedder.release()
    }

    private suspend fun index(bookId: String): VectorIndex = mutex.withLock {
        cached?.takeIf { it.first == bookId }?.let { return@withLock it.second }
        val rows = embeddingDao.getVectors(bookId, embedder.modelId)
        val dimensions = embedder.dimensions
        val vectors = FloatArray(rows.size * dimensions)
        rows.forEachIndexed { i, row -> VectorCodec.decodeInto(row.vector, vectors, i * dimensions) }
        VectorIndex(LongArray(rows.size) { rows[it].chunkId }, vectors, dimensions).also { cached = bookId to it }
    }

    private companion object {
        const val CANDIDATES = 20
        const val DEFAULT_K = 8
    }
}
