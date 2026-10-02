package dev.joseramos.aireader.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppDatabaseTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun book(id: String, importedAt: Long, lastOpenedAt: Long? = null) = BookEntity(
        id = id,
        title = "Libro $id",
        author = null,
        fileName = "$id.pdf",
        filePath = "/books/$id.pdf",
        pageCount = 10,
        coverPath = null,
        importedAt = importedAt,
        lastOpenedAt = lastOpenedAt,
        indexStatus = IndexStatus.PENDING,
        indexProgress = 0f,
        cleanerVersion = 1
    )

    private fun chunk(bookId: String, ordinal: Int, text: String) = ChunkEntity(
        bookId = bookId,
        chapterId = null,
        ordinal = ordinal,
        text = text,
        startPage = ordinal + 1,
        endPage = ordinal + 1,
        charStart = 0,
        charEnd = text.length,
        tokenCount = text.length / 4
    )

    @Test
    fun booksAreOrderedByLastOpenedThenImported() = runTest {
        db.bookDao().upsert(book("a", importedAt = 100))
        db.bookDao().upsert(book("b", importedAt = 200))
        db.bookDao().upsert(book("c", importedAt = 50, lastOpenedAt = 300))

        assertEquals(listOf("c", "b", "a"), db.bookDao().observeAll().first().map { it.book.id })
    }

    @Test
    fun chapterQueriesSeparateChaptersFromSections() = runTest {
        db.bookDao().upsert(book("a", importedAt = 1))
        fun entry(number: Int, title: String, page: Int, level: Int) = ChapterEntity(
            bookId = "a",
            number = number,
            title = title,
            startPage = page,
            endPage = page,
            source = ChapterSource.OUTLINE,
            level = level
        )
        db.chapterDao().insertAll(
            listOf(entry(2, "Tema 2", 10, 0), entry(1, "Tema 1", 1, 0), entry(1, "1.1", 3, 1), entry(1, "1.1.1", 3, 2))
        )

        assertEquals(listOf("Tema 1", "Tema 2"), db.chapterDao().getByBook("a").map { it.title })
        assertEquals(listOf("Tema 1", "1.1", "1.1.1", "Tema 2"), db.chapterDao().getContents("a").map { it.title })
        assertEquals(0, db.chapterDao().countDependents("a"))
    }

    @Test
    fun deletingBookCascadesToEverything() = runTest {
        db.bookDao().upsert(book("a", importedAt = 1))
        val chapterId = db.chapterDao().insertAll(
            listOf(
                ChapterEntity(
                    bookId = "a",
                    number = 1,
                    title = "Uno",
                    startPage = 1,
                    endPage = 5,
                    source = ChapterSource.OUTLINE
                )
            )
        ).single()
        val chunkId = db.chunkDao().insertAll(listOf(chunk("a", 0, "texto"))).single()
        db.chunkEmbeddingDao().insertAll(listOf(ChunkEmbeddingEntity(chunkId, "e5", 2, ByteArray(8))))
        db.pageTextDao().upsert(PageTextEntity("a", 1, "raw", "clean", "[]", false, 1))
        db.readingPositionDao().upsert(ReadingPositionEntity("a", 3, 0, 0, 1))
        db.summaryDao().upsert(
            SummaryEntity(
                bookId = "a",
                chapterId = chapterId,
                kind = SummaryKind.CHAPTER_SHORT,
                text = "r",
                model = "m",
                createdAt = 1
            )
        )
        val threadId = db.chatDao().insertThread(ChatThreadEntity(bookId = "a", createdAt = 1))
        db.chatDao().insertMessage(
            ChatMessageEntity(
                threadId = threadId,
                role = ChatRole.USER,
                text = "?",
                citationsJson = "[]",
                createdAt = 1
            )
        )

        db.bookDao().delete("a")

        assertTrue(db.chapterDao().getByBook("a").isEmpty())
        assertTrue(db.chunkDao().getByBook("a").isEmpty())
        assertTrue(db.chunkEmbeddingDao().getVectors("a", "e5").isEmpty())
        assertNull(db.pageTextDao().get("a", 1))
        assertNull(db.readingPositionDao().get("a"))
        assertTrue(db.summaryDao().observeByBook("a").first().isEmpty())
        assertNull(db.chatDao().latestThread("a"))
    }

    @Test
    fun maxPageNeverGoesDown() = runTest {
        db.bookDao().upsert(book("a", importedAt = 1))
        val positions = dev.joseramos.aireader.core.data.book.ReadingPositionRepository(db.readingPositionDao())

        positions.save("a", dev.joseramos.aireader.core.data.book.ReadingPosition(40), markRead = true)
        positions.save("a", dev.joseramos.aireader.core.data.book.ReadingPosition(12))
        positions.markRead("a", 30)

        assertEquals(12, positions.get("a")?.page)
        assertEquals(40, positions.observeMaxPage("a").first())
    }

    @Test
    fun libraryShowsTheMostRecentBookmark() = runTest {
        db.bookDao().upsert(book("a", importedAt = 1))
        db.bookmarkDao().upsert(BookmarkEntity(bookId = "a", page = 80, note = null, createdAt = 1))
        db.bookmarkDao().upsert(BookmarkEntity(bookId = "a", page = 12, note = "ojo", createdAt = 2))

        assertEquals(12, db.bookDao().observeAll().first().single().lastBookmarkPage)
    }

    @Test
    fun deletingCharacterCascadesToNamesAndRelations() = runTest {
        db.bookDao().upsert(book("a", importedAt = 1))
        val dao = db.characterDao()
        val rodion = dao.insertCharacter(CharacterEntity(bookId = "a", firstPage = 1))
        val dunia = dao.insertCharacter(CharacterEntity(bookId = "a", firstPage = 30))
        dao.insertName(
            CharacterNameEntity(characterId = rodion, name = "Rodia", kind = NameKind.NICKNAME, firstPage = 5)
        )
        dao.insertRelation(
            RelationEntity(
                bookId = "a",
                fromId = dunia,
                toId = rodion,
                type = RelationType.FAMILY,
                label = "hermana de",
                page = 30
            )
        )

        dao.deleteCharacter(rodion)

        assertTrue(dao.getNames("a").isEmpty())
        assertTrue(dao.getRelations("a").isEmpty())
        assertEquals(listOf(dunia), dao.getCharacters("a").map { it.id })
    }

    @Test
    fun fullTextSearchIsScopedToTheBook() = runTest {
        db.bookDao().upsert(book("a", importedAt = 1))
        db.bookDao().upsert(book("b", importedAt = 2))
        db.chunkDao().insertAll(
            listOf(
                chunk("a", 0, "El hipocampo consolida los recuerdos durante el sueño."),
                chunk("a", 1, "La inflación reduce el poder adquisitivo del ahorro.")
            )
        )
        db.chunkDao().insertAll(listOf(chunk("b", 0, "Otro libro que también habla del hipocampo.")))

        val matches = db.chunkDao().searchText("a", "hipocampo", limit = 10)

        assertEquals(1, matches.size)
        assertEquals(
            "El hipocampo consolida los recuerdos durante el sueño.",
            db.chunkDao().getByIds(
                matches.map {
                    it.chunkId
                }
            ).single().text
        )
    }

    @Test
    fun vectorsAreLoadedPerBookAndModel() = runTest {
        db.bookDao().upsert(book("a", importedAt = 1))
        val ids = db.chunkDao().insertAll(listOf(chunk("a", 0, "uno"), chunk("a", 1, "dos")))
        db.chunkEmbeddingDao().insertAll(
            listOf(
                ChunkEmbeddingEntity(ids[0], "e5", 2, ByteArray(8) { 1 }),
                ChunkEmbeddingEntity(ids[1], "e5", 2, ByteArray(8) { 2 })
            )
        )

        val vectors = db.chunkEmbeddingDao().getVectors("a", "e5")
        assertEquals(ids.toSet(), vectors.map { it.chunkId }.toSet())
        assertTrue(vectors.single { it.chunkId == ids[1] }.vector.all { it == 2.toByte() })
        assertTrue(db.chunkEmbeddingDao().getVectors("a", "gemma").isEmpty())
    }
}
