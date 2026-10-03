package dev.joseramos.aireader.ai.rag

import android.util.Log
import dev.joseramos.aireader.ai.embeddings.Embedder
import dev.joseramos.aireader.ai.embeddings.VectorCodec
import dev.joseramos.aireader.core.data.db.ChunkDao
import dev.joseramos.aireader.core.data.db.ChunkEmbeddingDao
import dev.joseramos.aireader.core.data.db.ChunkEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ids de fragmentos ordenados por la búsqueda vectorial y por la de texto completo, con la similitud
 * de cada uno de [vector] en [vectorScores].
 */
data class Candidates(val vector: List<Long>, val text: List<Long>, val vectorScores: List<Float> = emptyList())

/**
 * Búsqueda híbrida en un libro: los 20 fragmentos más parecidos por significado (embeddings) y
 * los 20 mejores por palabras exactas (texto completo), fusionados con RRF. Las palabras exactas
 * rescatan nombres propios, cifras y términos raros que los embeddings confunden.
 *
 * Si el libro no tiene vectores o el modelo no se puede cargar, busca solo por palabras; y si
 * tampoco encuentra nada, usa fragmentos repartidos por todo el libro. Nunca falla por los embeddings.
 *
 * Con `maxPage` (anti-spoilers) solo se usan fragmentos que acaban como muy tarde en esa página.
 */
@Singleton
class HybridRetriever @Inject constructor(
    private val chunkDao: ChunkDao,
    private val embeddingDao: ChunkEmbeddingDao,
    private val embedder: Embedder
) {
    private val mutex = Mutex()
    private var cached: Pair<String, VectorIndex>? = null

    /** El modelo no carga en este móvil (falta una biblioteca nativa): no se reintenta hasta reiniciar. */
    @Volatile private var semanticBroken = false

    /** Si el libro tiene fragmentos en los que buscar (aunque no tenga vectores). */
    fun observeSearchable(bookId: String): Flow<Boolean> = chunkDao.observeHasChunks(bookId)

    suspend fun retrieve(
        bookId: String,
        question: String,
        k: Int = DEFAULT_K,
        maxPage: Int? = null
    ): List<ChunkEntity> {
        val candidates = candidates(bookId, question, maxPage)
        val fused = reciprocalRankFusion(listOf(candidates.vector, candidates.text))
        val ids = dropWeakMatches(fused, candidates).take(k)
            .ifEmpty { evenlySpaced(allowedIds(bookId, maxPage), k) }
        return chunks(ids)
    }

    /** [k] fragmentos repartidos entre [fromPage] y [toPage], en orden de lectura (para resumir un capítulo). */
    suspend fun spread(bookId: String, fromPage: Int, toPage: Int, k: Int): List<ChunkEntity> =
        if (toPage < fromPage) emptyList() else chunks(evenlySpaced(chunkDao.idsInPages(bookId, fromPage, toPage), k))

    /** Resultados de cada búsqueda por separado, antes de fusionarlos (para evaluar y ajustar). */
    suspend fun candidates(bookId: String, question: String, maxPage: Int? = null): Candidates {
        val textHits = FtsQuery.from(question)
            ?.let { query ->
                runCatching {
                    if (maxPage == null) {
                        chunkDao.searchText(bookId, query, CANDIDATES)
                    } else {
                        chunkDao.searchTextUntil(bookId, query, maxPage, CANDIDATES)
                    }
                }.getOrDefault(emptyList())
            }
            ?.map { it.chunkId }
            .orEmpty()
        val semantic = semantic(bookId, question, maxPage)
        return Candidates(semantic.map { it.first }, textHits, semantic.map { it.second })
    }

    /** Libera el modelo de la memoria al salir del chat. */
    suspend fun release() {
        mutex.withLock { cached = null }
        embedder.release()
    }

    /** Búsqueda por significado, o nada si no hay vectores o el modelo falla. */
    @Suppress("TooGenericExceptionCaught") // Errores nativos (ONNX, tokenizador) o de memoria al cargar el modelo.
    private suspend fun semantic(bookId: String, question: String, maxPage: Int?): List<Pair<Long, Float>> {
        if (semanticBroken) return emptyList()
        return try {
            // Sin vectores no se carga el modelo: es lo que tarda y lo que puede fallar.
            val index = index(bookId).takeIf { it.size > 0 && embedder.isAvailable() } ?: return emptyList()
            val allowed = maxPage?.let { allowedIds(bookId, it).toSet() }
            index.searchScored(embedder.embedQuery(question), CANDIDATES, allowed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            Log.e(TAG, "El modelo de embeddings no carga en este dispositivo: se busca solo por palabras", e)
            semanticBroken = true
            emptyList()
        } catch (e: Throwable) {
            Log.w(TAG, "Fallo en la búsqueda por significado de $bookId: se busca solo por palabras", e)
            emptyList()
        }
    }

    private suspend fun allowedIds(bookId: String, maxPage: Int?): List<Long> =
        if (maxPage == null) chunkDao.idsByBook(bookId) else chunkDao.idsInPages(bookId, 1, maxPage)

    private suspend fun chunks(ids: List<Long>): List<ChunkEntity> {
        val byId = chunkDao.getByIds(ids).associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    private suspend fun index(bookId: String): VectorIndex = mutex.withLock {
        cached?.takeIf { it.first == bookId }?.let { return@withLock it.second }
        val rows = embeddingDao.getVectors(bookId, embedder.modelId)
        val dimensions = embedder.dimensions
        val vectors = FloatArray(rows.size * dimensions)
        rows.forEachIndexed { i, row -> VectorCodec.decodeInto(row.vector, vectors, i * dimensions) }
        // Un índice vacío no se guarda: los vectores pueden llegar mientras el chat está abierto.
        VectorIndex(LongArray(rows.size) { rows[it].chunkId }, vectors, dimensions)
            .also { if (it.size > 0) cached = bookId to it }
    }

    private companion object {
        const val TAG = "HybridRetriever"
        const val CANDIDATES = 20
        const val DEFAULT_K = 8
    }
}
