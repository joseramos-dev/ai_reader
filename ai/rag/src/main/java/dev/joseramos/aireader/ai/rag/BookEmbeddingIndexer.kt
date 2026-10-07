package dev.joseramos.aireader.ai.rag

import android.util.Log
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
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.ensureActive

/**
 * Etapa de embeddings de la indexación: trocea cada capítulo en fragmentos (que Room añade al
 * índice de texto completo) y calcula su vector. Los fragmentos se crean aparte y sin el modelo,
 * para que el chat pueda buscar por palabras aunque los embeddings no estén. Es reanudable: solo
 * procesa los fragmentos que aún no tienen vector del modelo actual, y descarta los de modelos anteriores.
 */
class BookEmbeddingIndexer(
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
        val start = TimeSource.Monotonic.markNow()
        try {
            pending.chunked(GROUP).forEachIndexed { i, group ->
                coroutineContext.ensureActive()
                val vectors = embedder.embedDocuments(group.map { it.text })
                embeddingDao.insertAll(
                    group.zip(vectors) { chunk, vector ->
                        ChunkEmbeddingEntity(chunk.id, embedder.modelId, vector.size, VectorCodec.encode(vector))
                    }
                )
                onProgress((done.size + (i + 1) * GROUP).coerceAtMost(chunks.size).toFloat() / chunks.size)
            }
        } finally {
            embedder.release()
        }
        if (pending.isNotEmpty()) {
            val elapsed = start.elapsedNow().inWholeMilliseconds
            Log.i(TAG, "Vectores de ${pending.size} fragmentos de $bookId en $elapsed ms")
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
        const val TAG = "BookEmbeddingIndexer"

        /**
         * Fragmentos por llamada al modelo y por guardado. El modelo los reparte entre los núcleos y, al
         * final de cada grupo, espera al más lento: con grupos de 16 se perdía un 15 % de velocidad en un
         * Helio G99, y con 64, un 1 %.
         */
        const val GROUP = 64
    }
}
