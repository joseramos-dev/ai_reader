package dev.joseramos.aireader.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Libro con la página en la que se quedó la lectura (si se ha abierto alguna vez) y la del
 * marcapáginas más reciente.
 */
data class BookWithPosition(@Embedded val book: BookEntity, val currentPage: Int?, val lastBookmarkPage: Int?)

@Dao
interface BookDao {
    @Query(
        """
        SELECT books.*, rp.page AS currentPage,
            (SELECT b.page FROM bookmarks b WHERE b.bookId = books.id ORDER BY b.createdAt DESC LIMIT 1)
                AS lastBookmarkPage
        FROM books
        LEFT JOIN reading_positions rp ON rp.bookId = books.id
        ORDER BY COALESCE(books.lastOpenedAt, books.importedAt) DESC
        """
    )
    fun observeAll(): Flow<List<BookWithPosition>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Query("SELECT id FROM books WHERE indexStatus = :status")
    suspend fun idsWithStatus(status: IndexStatus): List<String>

    @Query("UPDATE books SET indexStatus = :status, indexProgress = :progress WHERE id = :id")
    suspend fun updateIndexState(id: String, status: IndexStatus, progress: Float)

    @Query("UPDATE books SET title = :title, author = :author WHERE id = :id")
    suspend fun updateMetadata(id: String, title: String, author: String?)

    @Query("UPDATE books SET lastOpenedAt = :timestamp WHERE id = :id")
    suspend fun markOpened(id: String, timestamp: Long)

    @Query("UPDATE books SET documentType = :type, documentTypeSource = :source WHERE id = :id")
    suspend fun updateDocumentType(id: String, type: DocumentType, source: DocumentTypeSource)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY number")
    fun observeByBook(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY number")
    suspend fun getByBook(bookId: String): List<ChapterEntity>

    @Insert
    suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}

@Dao
interface PageTextDao {
    @Query("SELECT * FROM page_texts WHERE bookId = :bookId AND page = :page")
    suspend fun get(bookId: String, page: Int): PageTextEntity?

    @Query("SELECT * FROM page_texts WHERE bookId = :bookId ORDER BY page")
    suspend fun getByBook(bookId: String): List<PageTextEntity>

    @Query("SELECT * FROM page_texts WHERE bookId = :bookId ORDER BY page")
    fun observeByBook(bookId: String): Flow<List<PageTextEntity>>

    @Query("SELECT * FROM page_texts WHERE bookId = :bookId AND page >= :fromPage ORDER BY page LIMIT :limit")
    suspend fun getFrom(bookId: String, fromPage: Int, limit: Int): List<PageTextEntity>

    @Query("SELECT COUNT(*) FROM page_texts WHERE bookId = :bookId AND cleanerVersion = :cleanerVersion")
    suspend fun countUpToDate(bookId: String, cleanerVersion: Int): Int

    @Upsert
    suspend fun upsert(page: PageTextEntity)
}

/** Fragmento encontrado por la búsqueda léxica, con su puntuación (menor es mejor). */
data class ChunkMatch(val chunkId: Long, val score: Double)

@Dao
interface ChunkDao {
    @Insert
    suspend fun insertAll(chunks: List<ChunkEntity>): List<Long>

    @Query("SELECT * FROM chunks WHERE bookId = :bookId ORDER BY ordinal")
    suspend fun getByBook(bookId: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ChunkEntity>

    /**
     * Búsqueda de texto completo dentro de un libro. FTS4 no tiene BM25, así que se ordena
     * por el número de coincidencias aproximado; la fusión con la búsqueda vectorial (RRF)
     * solo usa el orden, no la puntuación.
     */
    @Query(
        """
        SELECT c.id AS chunkId, -length(offsets(chunks_fts)) AS score
        FROM chunks_fts JOIN chunks c ON c.id = chunks_fts.rowid
        WHERE chunks_fts MATCH :query AND c.bookId = :bookId
        ORDER BY score
        LIMIT :limit
        """
    )
    suspend fun searchText(bookId: String, query: String, limit: Int): List<ChunkMatch>

    @Query("DELETE FROM chunks WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}

/** Vector de un fragmento para cargar el índice vectorial de un libro en memoria. */
data class BookVector(val chunkId: Long, val vector: ByteArray) {
    override fun equals(other: Any?) =
        other is BookVector && chunkId == other.chunkId && vector.contentEquals(other.vector)

    override fun hashCode() = 31 * chunkId.hashCode() + vector.contentHashCode()
}

@Dao
interface ChunkEmbeddingDao {
    @Insert
    suspend fun insertAll(embeddings: List<ChunkEmbeddingEntity>)

    @Query(
        """
        SELECT e.chunkId AS chunkId, e.vector AS vector
        FROM chunk_embeddings e JOIN chunks c ON c.id = e.chunkId
        WHERE c.bookId = :bookId AND e.modelId = :modelId
        """
    )
    suspend fun getVectors(bookId: String, modelId: String): List<BookVector>

    @Query(
        """
        SELECT e.chunkId FROM chunk_embeddings e JOIN chunks c ON c.id = e.chunkId
        WHERE c.bookId = :bookId AND e.modelId = :modelId
        """
    )
    suspend fun embeddedChunkIds(bookId: String, modelId: String): List<Long>

    /** Borra los vectores de otros modelos: no son comparables con los del modelo actual. */
    @Query(
        """
        DELETE FROM chunk_embeddings WHERE modelId != :modelId
        AND chunkId IN (SELECT id FROM chunks WHERE bookId = :bookId)
        """
    )
    suspend fun deleteOtherModels(bookId: String, modelId: String)
}

@Dao
interface SummaryDao {
    @Query("SELECT * FROM summaries WHERE bookId = :bookId ORDER BY chapterId")
    fun observeByBook(bookId: String): Flow<List<SummaryEntity>>

    @Query("SELECT * FROM summaries WHERE bookId = :bookId ORDER BY chapterId")
    suspend fun getByBook(bookId: String): List<SummaryEntity>

    /** `IS` compara bien con `NULL` (resumen del libro, sin capítulo). */
    @Query("SELECT * FROM summaries WHERE bookId = :bookId AND chapterId IS :chapterId AND kind = :kind LIMIT 1")
    suspend fun get(bookId: String, chapterId: Long?, kind: SummaryKind): SummaryEntity?

    @Query("DELETE FROM summaries WHERE bookId = :bookId AND chapterId IS :chapterId AND kind = :kind")
    suspend fun delete(bookId: String, chapterId: Long?, kind: SummaryKind)

    @Upsert
    suspend fun upsert(summary: SummaryEntity): Long
}

@Dao
interface ReadingPositionDao {
    @Query("SELECT * FROM reading_positions WHERE bookId = :bookId")
    suspend fun get(bookId: String): ReadingPositionEntity?

    @Query("SELECT maxPage FROM reading_positions WHERE bookId = :bookId")
    fun observeMaxPage(bookId: String): Flow<Int?>

    @Upsert
    suspend fun upsert(position: ReadingPositionEntity)

    /** Sube [ReadingPositionEntity.maxPage] hasta [page] si es mayor; nunca la baja. */
    @Query("UPDATE reading_positions SET maxPage = MAX(maxPage, :page) WHERE bookId = :bookId")
    suspend fun raiseMaxPage(bookId: String, page: Int)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY page")
    fun observeByBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND page = :page LIMIT 1")
    suspend fun get(bookId: String, page: Int): BookmarkEntity?

    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Todas las consultas de las tablas de personajes: el análisis las usa juntas en una transacción. */
@Suppress("TooManyFunctions")
@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters WHERE bookId = :bookId ORDER BY firstPage, id")
    fun observeCharacters(bookId: String): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE bookId = :bookId ORDER BY firstPage, id")
    suspend fun getCharacters(bookId: String): List<CharacterEntity>

    @Query(
        """
        SELECT n.* FROM character_names n JOIN characters c ON c.id = n.characterId
        WHERE c.bookId = :bookId ORDER BY n.firstPage, n.id
        """
    )
    fun observeNames(bookId: String): Flow<List<CharacterNameEntity>>

    @Query(
        """
        SELECT n.* FROM character_names n JOIN characters c ON c.id = n.characterId
        WHERE c.bookId = :bookId ORDER BY n.firstPage, n.id
        """
    )
    suspend fun getNames(bookId: String): List<CharacterNameEntity>

    @Query(
        """
        SELECT f.* FROM character_facts f JOIN characters c ON c.id = f.characterId
        WHERE c.bookId = :bookId ORDER BY f.page, f.id
        """
    )
    fun observeFacts(bookId: String): Flow<List<CharacterFactEntity>>

    @Query("SELECT * FROM relations WHERE bookId = :bookId ORDER BY page, id")
    fun observeRelations(bookId: String): Flow<List<RelationEntity>>

    @Query("SELECT * FROM relations WHERE bookId = :bookId ORDER BY page, id")
    suspend fun getRelations(bookId: String): List<RelationEntity>

    @Insert
    suspend fun insertCharacter(character: CharacterEntity): Long

    @Query("UPDATE characters SET firstPage = MIN(firstPage, :page) WHERE id = :id")
    suspend fun lowerFirstPage(id: Long, page: Int)

    @Insert
    suspend fun insertName(name: CharacterNameEntity): Long

    @Query("UPDATE character_names SET isPrimaryFrom = :page WHERE id = :id")
    suspend fun setPrimaryFrom(id: Long, page: Int)

    @Query("SELECT * FROM character_facts WHERE characterId = :characterId")
    suspend fun getFacts(characterId: Long): List<CharacterFactEntity>

    @Insert
    suspend fun insertFact(fact: CharacterFactEntity): Long

    @Insert
    suspend fun insertRelation(relation: RelationEntity): Long

    @Query("UPDATE relations SET endsAtPage = :page WHERE id = :id")
    suspend fun endRelation(id: Long, page: Int)

    /** Pasa nombres, datos y relaciones de [fromId] a [toId] y borra [fromId] (son el mismo personaje). */
    @Query("UPDATE character_names SET characterId = :toId WHERE characterId = :fromId")
    suspend fun moveNames(fromId: Long, toId: Long)

    @Query("UPDATE character_facts SET characterId = :toId WHERE characterId = :fromId")
    suspend fun moveFacts(fromId: Long, toId: Long)

    @Query("UPDATE relations SET fromId = :toId WHERE fromId = :fromId")
    suspend fun moveRelationsFrom(fromId: Long, toId: Long)

    @Query("UPDATE relations SET toId = :toId WHERE toId = :fromId")
    suspend fun moveRelationsTo(fromId: Long, toId: Long)

    /** Tras fusionar, una relación de un personaje consigo mismo no tiene sentido. */
    @Query("DELETE FROM relations WHERE fromId = toId")
    suspend fun deleteSelfRelations()

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun deleteCharacter(id: Long)

    @Query("SELECT * FROM character_scans WHERE bookId = :bookId")
    fun observeScans(bookId: String): Flow<List<CharacterScanEntity>>

    @Query("SELECT chapterId FROM character_scans WHERE bookId = :bookId")
    suspend fun scannedChapterIds(bookId: String): List<Long>

    @Upsert
    suspend fun upsertScan(scan: CharacterScanEntity)
}

@Dao
interface ChatDao {
    @Insert
    suspend fun insertThread(thread: ChatThreadEntity): Long

    @Query("SELECT * FROM chat_threads WHERE bookId = :bookId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestThread(bookId: String): ChatThreadEntity?

    @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY createdAt, id")
    fun observeMessages(threadId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY createdAt, id")
    suspend fun getMessages(threadId: Long): List<ChatMessageEntity>

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long
}

@Dao
interface DownloadedModelDao {
    @Query("SELECT * FROM downloaded_models")
    fun observeAll(): Flow<List<DownloadedModelEntity>>

    @Query("SELECT * FROM downloaded_models WHERE id = :id")
    suspend fun get(id: String): DownloadedModelEntity?

    @Upsert
    suspend fun upsert(model: DownloadedModelEntity)

    @Query("DELETE FROM downloaded_models WHERE id = :id")
    suspend fun delete(id: String)
}
