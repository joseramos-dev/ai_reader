package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.ai.embeddings.Embedder
import dev.joseramos.aireader.ai.embeddings.VectorCodec
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.db.ChunkDao
import dev.joseramos.aireader.core.data.db.ChunkEmbeddingDao
import dev.joseramos.aireader.core.data.db.ChunkEmbeddingEntity
import dev.joseramos.aireader.core.data.db.ChunkEntity
import dev.joseramos.aireader.indexing.EmbeddingStage
import dev.joseramos.aireader.text.Chunker
import dev.joseramos.aireader.text.PageParagraph
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Etapa de embeddings de la indexación: trocea cada capítulo en fragmentos (que Room añade al
 * índice de texto completo) y calcula su vector. Los fragmentos se crean aparte y sin el modelo,
 * para que el chat pueda buscar por palabras aunque los embeddings no estén. Es reanudable: solo
 * procesa los fragmentos que aún no tienen vector del modelo actual, y descarta los de modelos anteriores.
 */
class BookEmbeddingIndexer @Inject constructor(
    private val content: BookContentRepository,
    private val chunkDao: ChunkDao,
    private val embeddingDao: ChunkEmbeddingDao,
    private val embedder: Embedder
) : EmbeddingStage {

    override suspend fun prepareChunks(bookId: String) {
        if (chunkDao.countByBook(bookId) == 0) chunkDao.insertAll(buildChunks(bookId))
    }

    override suspend fun run(bookId: String, onProgress: suspend (Float) -> Unit): Boolean {
        if (!embedder.isAvailable()) return false
        prepareChunks(bookId)
        val chunks = chunkDao.getByBook(bookId)
        embeddingDao.deleteOtherModels(bookId, embedder.modelId)
        val done = embeddingDao.embeddedChunkIds(bookId, embedder.modelId).toSet()
        val pending = chunks.filter { it.id !in done }
        try {
            pending.chunked(BATCH).forEachIndexed { i, batch ->
                coroutineContext.ensureActive()
                val vectors = embedder.embedDocuments(batch.map { it.text })
                embeddingDao.insertAll(
                    batch.zip(vectors) { chunk, vector ->
                        ChunkEmbeddingEntity(chunk.id, embedder.modelId, vector.size, VectorCodec.encode(vector))
                    }
                )
                onProgress((done.size + (i + 1) * BATCH).coerceAtMost(chunks.size).toFloat() / chunks.size)
            }
        } finally {
            embedder.release()
        }
        return true
    }

    private suspend fun buildChunks(bookId: String): List<ChunkEntity> {
        val pages = content.pages(bookId)
        val chapters = content.chapters(bookId)
        // Sin capítulos, todo el libro es una única sección.
        val sections = chapters.ifEmpty { listOf(null) }
        var ordinal = 0
        return sections.flatMap { chapter ->
            val range = chapter?.let { it.startPage..it.endPage } ?: (1..Int.MAX_VALUE)
            val paragraphs = pages.filter { it.page in range }.flatMap { page ->
                page.paragraphs.map { PageParagraph(page.page, it) }
            }
            Chunker.chunk(paragraphs).map { chunk ->
                ChunkEntity(
                    bookId = bookId,
                    chapterId = chapter?.id,
                    ordinal = ordinal++,
                    text = chunk.text,
                    startPage = chunk.startPage,
                    endPage = chunk.endPage,
                    charStart = chunk.charStart,
                    charEnd = chunk.charEnd,
                    tokenCount = chunk.tokenCount
                )
            }
        }
    }

    private companion object {
        const val BATCH = 16
    }
}
