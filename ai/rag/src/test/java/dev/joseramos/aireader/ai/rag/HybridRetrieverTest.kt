package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.ai.embeddings.Embedder
import dev.joseramos.aireader.ai.embeddings.VectorCodec
import dev.joseramos.aireader.core.data.db.BookVector
import dev.joseramos.aireader.core.data.db.ChunkDao
import dev.joseramos.aireader.core.data.db.ChunkEmbeddingDao
import dev.joseramos.aireader.core.data.db.ChunkEmbeddingEntity
import dev.joseramos.aireader.core.data.db.ChunkEntity
import dev.joseramos.aireader.core.data.db.ChunkMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRetrieverTest {
    private val chunks = (0 until 10).map { i ->
        ChunkEntity(i + 1L, "libro", null, i, "fragmento $i", i + 1, i + 1, 0, 0, 0)
    }

    @Test
    fun withoutVectorsSearchesOnlyByWordsAndNeverLoadsTheModel() = runTest {
        val embedder = FakeEmbedder()
        val retriever = HybridRetriever(FakeChunkDao(chunks, textHits = listOf(4, 7)), FakeEmbeddingDao(), embedder)

        val found = retriever.retrieve("libro", "¿Qué hace Raskólnikov con el hacha?")

        assertEquals(listOf(4L, 7L), found.map { it.id })
        assertEquals(0, embedder.queries)
    }

    /** Lo que pasaba en el móvil: el tokenizador nativo no carga. La pregunta se responde igual. */
    @Test
    fun brokenNativeModelFallsBackToWordSearch() = runTest {
        val embedder = FakeEmbedder(failure = UnsatisfiedLinkError("libc++_shared.so not found"))
        val vectors = FakeEmbeddingDao(listOf(BookVector(1, VectorCodec.encode(floatArrayOf(1f, 0f)))))
        val retriever = HybridRetriever(FakeChunkDao(chunks, textHits = listOf(3)), vectors, embedder)

        assertEquals(listOf(3L), retriever.retrieve("libro", "Sonia y el hacha").map { it.id })
        // Tras un fallo nativo no se vuelve a intentar cargar el modelo en cada pregunta.
        retriever.retrieve("libro", "Sonia y el hacha")
        assertEquals(1, embedder.queries)
    }

    @Test
    fun withVectorsFusesBothSearches() = runTest {
        val embedder = FakeEmbedder()
        val vectors = FakeEmbeddingDao(
            listOf(
                BookVector(9, VectorCodec.encode(floatArrayOf(1f, 0f))),
                BookVector(2, VectorCodec.encode(floatArrayOf(0f, 1f)))
            )
        )
        val retriever = HybridRetriever(FakeChunkDao(chunks, textHits = listOf(5)), vectors, embedder)

        val ids = retriever.retrieve("libro", "Sonia y el hacha").map { it.id }

        assertEquals(setOf(9L, 2L, 5L), ids.toSet())
        assertEquals(9L, ids.first())
    }

    @Test
    fun whenNothingMatchesUsesFragmentsFromAcrossTheBook() = runTest {
        val retriever =
            HybridRetriever(FakeChunkDao(chunks, textHits = emptyList()), FakeEmbeddingDao(), FakeEmbedder())

        val ids = retriever.retrieve("libro", "¿De qué trata?", k = 5).map { it.id }

        assertEquals(listOf(1L, 3L, 5L, 7L, 9L), ids)
    }

    /** Anti-spoilers: ni por palabras, ni por significado, ni en la reserva sale nada posterior a la página. */
    @Test
    fun withPageLimitNeverReturnsLaterFragments() = runTest {
        val vectors = FakeEmbeddingDao(
            listOf(
                BookVector(9, VectorCodec.encode(floatArrayOf(1f, 0f))),
                BookVector(2, VectorCodec.encode(floatArrayOf(0.5f, 0.5f)))
            )
        )
        val retriever = HybridRetriever(FakeChunkDao(chunks, textHits = listOf(8, 3)), vectors, FakeEmbedder())

        assertEquals(setOf(2L, 3L), retriever.retrieve("libro", "Sonia y el hacha", maxPage = 4).map { it.id }.toSet())

        val fallback = HybridRetriever(FakeChunkDao(chunks, textHits = emptyList()), FakeEmbeddingDao(), FakeEmbedder())
        assertTrue(fallback.retrieve("libro", "¿De qué trata?", k = 5, maxPage = 3).all { it.endPage <= 3 })
    }

    @Test
    fun spreadPicksFragmentsAcrossAChapter() = runTest {
        val retriever =
            HybridRetriever(FakeChunkDao(chunks, textHits = emptyList()), FakeEmbeddingDao(), FakeEmbedder())

        assertEquals(listOf(3L, 5L), retriever.spread("libro", fromPage = 3, toPage = 6, k = 2).map { it.id })
        assertTrue(retriever.spread("libro", fromPage = 6, toPage = 3, k = 2).isEmpty())
    }

    @Test
    fun chapterSummaryRequestsAreRecognised() {
        assertEquals(ChapterRef.Current, QueryRouter.chapterSummary("Resume este capítulo"))
        assertEquals(ChapterRef.Current, QueryRouter.chapterSummary("¿De qué trata el tema actual?"))
        assertEquals(ChapterRef.Number(3), QueryRouter.chapterSummary("Hazme un resumen del capítulo 3"))
        assertEquals(ChapterRef.Number(4), QueryRouter.chapterSummary("resumen del capítulo IV"))
        assertEquals(null, QueryRouter.chapterSummary("¿Quién mata al prestamista en el capítulo 3?"))
        assertEquals(null, QueryRouter.chapterSummary("Resume el libro"))
    }

    @Test
    fun evenlySpacedCoversTheWholeList() {
        assertEquals(listOf(0, 25, 50, 75), evenlySpaced((0 until 100).toList(), 4))
        assertEquals(listOf(1, 2), evenlySpaced(listOf(1, 2), 8))
        assertTrue(evenlySpaced(listOf(1, 2), 0).isEmpty())
    }

    private class FakeEmbedder(private val failure: Throwable? = null) : Embedder {
        var queries = 0
        override val modelId = "e5"
        override val dimensions = 2

        override suspend fun isAvailable() = true

        override suspend fun embedDocuments(texts: List<String>) = texts.map { embedQuery(it) }

        override suspend fun embedQuery(text: String): FloatArray {
            queries++
            failure?.let { throw it }
            return floatArrayOf(1f, 0f)
        }

        override suspend fun release() = Unit
    }

    private class FakeChunkDao(private val chunks: List<ChunkEntity>, private val textHits: List<Long>) : ChunkDao {
        override suspend fun insertAll(chunks: List<ChunkEntity>) = emptyList<Long>()

        override suspend fun getByBook(bookId: String) = chunks

        override suspend fun getByIds(ids: List<Long>) = chunks.filter { it.id in ids }

        override suspend fun countByBook(bookId: String) = chunks.size

        override fun observeHasChunks(bookId: String): Flow<Boolean> = flowOf(chunks.isNotEmpty())

        override suspend fun idsByBook(bookId: String) = chunks.map { it.id }

        override suspend fun searchText(bookId: String, query: String, limit: Int) =
            textHits.map { ChunkMatch(it, 0.0) }

        override suspend fun searchTextUntil(bookId: String, query: String, maxPage: Int, limit: Int) =
            textHits.filter { id -> chunks.first { it.id == id }.endPage <= maxPage }.map { ChunkMatch(it, 0.0) }

        override suspend fun idsInPages(bookId: String, fromPage: Int, toPage: Int) =
            chunks.filter { it.startPage >= fromPage && it.endPage <= toPage }.map { it.id }

        override suspend fun deleteByBook(bookId: String) = Unit
    }

    private class FakeEmbeddingDao(private val vectors: List<BookVector> = emptyList()) : ChunkEmbeddingDao {
        override suspend fun insertAll(embeddings: List<ChunkEmbeddingEntity>) = Unit

        override suspend fun getVectors(bookId: String, modelId: String) = vectors

        override suspend fun embeddedChunkIds(bookId: String, modelId: String) = vectors.map { it.chunkId }

        override suspend fun deleteOtherModels(bookId: String, modelId: String) = Unit
    }
}
