package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.ai.embeddings.Embedder
import dev.joseramos.aireader.ai.embeddings.VectorCodec
import dev.joseramos.aireader.core.data.book.Chapter
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

    /** La indexación sigue con el chat abierto: los vectores nuevos cuentan desde la siguiente pregunta. */
    @Test
    fun vectorsArrivingWhileTheChatIsOpenAreUsed() = runTest {
        val vectors = mutableListOf(BookVector(2, VectorCodec.encode(floatArrayOf(0f, 1f))))
        val retriever =
            HybridRetriever(FakeChunkDao(chunks, textHits = emptyList()), FakeEmbeddingDao(vectors), FakeEmbedder())

        assertEquals(2L, retriever.retrieve("libro", "Sonia y el hacha").first().id)

        vectors += BookVector(9, VectorCodec.encode(floatArrayOf(1f, 0f)))
        assertEquals(9L, retriever.retrieve("libro", "Sonia y el hacha").first().id)
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

        assertEquals(
            setOf(2L, 3L),
            retriever.retrieve("libro", "Sonia y el hacha", pages = listOf(1..4)).map { it.id }.toSet()
        )

        val fallback = HybridRetriever(FakeChunkDao(chunks, textHits = emptyList()), FakeEmbeddingDao(), FakeEmbedder())
        assertTrue(fallback.retrieve("libro", "¿De qué trata?", k = 5, pages = listOf(1..3)).all { it.endPage <= 3 })
    }

    /** «Al final del libro», «los capítulos 2 y 7»: solo se busca en esas páginas, aunque sean dos tramos. */
    @Test
    fun withPageRangesSearchesOnlyInsideThem() = runTest {
        val vectors = FakeEmbeddingDao(
            listOf(
                BookVector(1, VectorCodec.encode(floatArrayOf(1f, 0f))),
                BookVector(5, VectorCodec.encode(floatArrayOf(0.9f, 0.1f))),
                BookVector(9, VectorCodec.encode(floatArrayOf(0.8f, 0.2f)))
            )
        )
        val retriever = HybridRetriever(FakeChunkDao(chunks, textHits = listOf(2, 6, 10)), vectors, FakeEmbedder())

        val ids = retriever.retrieve("libro", "Sonia y el hacha", pages = listOf(5..6, 9..10)).map { it.id }

        assertEquals(setOf(5L, 6L, 9L, 10L), ids.toSet())

        val fallback = HybridRetriever(FakeChunkDao(chunks, textHits = emptyList()), FakeEmbeddingDao(), FakeEmbedder())
        val spread = fallback.retrieve("libro", "¿De qué trata?", k = 3, pages = listOf(5..6, 9..10))
        assertTrue(spread.isNotEmpty() && spread.all { it.startPage in 5..6 || it.startPage in 9..10 })
        assertTrue(fallback.retrieve("libro", "¿De qué trata?", pages = emptyList()).isEmpty())
    }

    @Test
    fun spreadPicksFragmentsAcrossAChapter() = runTest {
        val retriever =
            HybridRetriever(FakeChunkDao(chunks, textHits = emptyList()), FakeEmbeddingDao(), FakeEmbedder())

        assertEquals(listOf(3L, 5L), retriever.spread("libro", fromPage = 3, toPage = 6, k = 2).map { it.id })
        assertTrue(retriever.spread("libro", fromPage = 6, toPage = 3, k = 2).isEmpty())
        assertEquals(listOf(2L, 9L), retriever.spread("libro", listOf(2..3, 9..10), k = 2).map { it.id })
    }

    private val partedBook = listOf(
        Chapter(1, 1, "PARTE 1. CAPÍTULO 1", 2, 8),
        Chapter(2, 2, "CAPÍTULO 2", 9, 23),
        Chapter(3, 3, "CAPÍTULO 3", 24, 34),
        Chapter(4, 4, "PARTE 2", 35, 35),
        Chapter(5, 5, "CAPÍTULO 1", 36, 50),
        Chapter(6, 6, "CAPÍTULO 2", 51, 60),
        Chapter(7, 7, "CAPÍTULO 3", 61, 70)
    )

    @Test
    fun chaptersAreResolvedFromTheReadingPosition() {
        assertEquals(6L, ChapterResolver.resolve(ChapterRef.Current, partedBook, 55)?.id)
        assertEquals(5L, ChapterResolver.resolve(ChapterRef.Previous, partedBook, 55)?.id)
        assertEquals(7L, ChapterResolver.resolve(ChapterRef.Next, partedBook, 55)?.id)
        // Se salta la página suelta que solo anuncia la parte.
        assertEquals(3L, ChapterResolver.resolve(ChapterRef.Previous, partedBook, 40)?.id)
        assertEquals(null, ChapterResolver.resolve(ChapterRef.Previous, partedBook, 5))
    }

    @Test
    fun numberedChaptersAreLookedUpInTheCurrentPart() {
        assertEquals(7L, ChapterResolver.resolve(ChapterRef.Number(3), partedBook, 55)?.id)
        assertEquals(3L, ChapterResolver.resolve(ChapterRef.Number(3), partedBook, 10)?.id)
        assertEquals(1L, ChapterResolver.resolve(ChapterRef.Number(1), partedBook, 10)?.id)
        assertEquals("PARTE 2", ChapterResolver.partOf(partedBook, partedBook[5]))
        assertEquals(null, ChapterResolver.partOf(listOf(Chapter(1, 1, "Uno", 1, 5)), Chapter(1, 1, "Uno", 1, 5)))
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

        override suspend fun searchTextInPages(bookId: String, query: String, fromPage: Int, toPage: Int, limit: Int) =
            textHits.filter { id ->
                val chunk = chunks.first { it.id == id }
                chunk.startPage >= fromPage && chunk.endPage <= toPage
            }.map { ChunkMatch(it, 0.0) }

        override suspend fun idsInPages(bookId: String, fromPage: Int, toPage: Int) =
            chunks.filter { it.startPage >= fromPage && it.endPage <= toPage }.map { it.id }

        override suspend fun deleteByBook(bookId: String) = Unit
    }

    private class FakeEmbeddingDao(private val vectors: List<BookVector> = emptyList()) : ChunkEmbeddingDao {
        override suspend fun insertAll(embeddings: List<ChunkEmbeddingEntity>) = Unit

        override suspend fun getVectors(bookId: String, modelId: String) = vectors.toList()

        override suspend fun countVectors(bookId: String, modelId: String) = vectors.size

        override suspend fun embeddedChunkIds(bookId: String, modelId: String) = vectors.map { it.chunkId }

        override suspend fun deleteOtherModels(bookId: String, modelId: String) = Unit
    }
}
