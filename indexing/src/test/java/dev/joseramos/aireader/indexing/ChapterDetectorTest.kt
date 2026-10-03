package dev.joseramos.aireader.indexing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.joseramos.aireader.core.data.db.AppDatabase
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.ChapterEntity
import dev.joseramos.aireader.core.data.db.ChapterSource
import dev.joseramos.aireader.core.data.db.ChunkEntity
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.core.data.db.PageTextEntity
import dev.joseramos.aireader.text.DetectedChapter
import java.util.Optional
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Un libro indexado sin clave de API se queda con capítulos en bloques de páginas; al repasarlo con
 * clave ([ChapterDetector.run] con `retryWithAi`), el índice se rehace con la IA.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChapterDetectorTest {
    private lateinit var db: AppDatabase
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** El libro no tiene índice en el PDF (el fichero no existe) ni títulos reconocibles en el texto. */
    private val book = BookEntity(
        id = "b",
        title = "Libro",
        author = null,
        fileName = "b.pdf",
        filePath = "/no/existe/b.pdf",
        pageCount = PAGES,
        coverPath = null,
        importedAt = 0,
        lastOpenedAt = null,
        indexStatus = IndexStatus.READY,
        indexProgress = 1f,
        cleanerVersion = 1
    )

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.bookDao().upsert(book)
        for (page in 1..PAGES) {
            val text = "texto corriente de la página $page sin ningún encabezado"
            db.pageTextDao().upsert(PageTextEntity(book.id, page, text, text, "[\"$text\"]", false, 1))
        }
        val blocks = db.chapterDao().insertAll(
            listOf(
                ChapterEntity(
                    bookId = book.id,
                    number = 1,
                    title = "Páginas 1–4",
                    startPage = 1,
                    endPage = PAGES,
                    source = ChapterSource.BLOCKS
                )
            )
        )
        db.chunkDao().insertAll(
            listOf(
                ChunkEntity(
                    bookId = book.id,
                    chapterId = blocks.single(),
                    ordinal = 0,
                    text = "fragmento",
                    startPage = 3,
                    endPage = 3,
                    charStart = 0,
                    charEnd = 9,
                    tokenCount = 2
                )
            )
        )
    }

    @After
    fun tearDown() = db.close()

    private fun detector(fromLlm: List<DetectedChapter>) = ChapterDetector(
        context,
        db.chapterDao(),
        db.pageTextDao(),
        Optional.of(
            object : LlmChapterDetection {
                override suspend fun detect(pageHeads: List<Pair<Int, String>>, pageCount: Int) = fromLlm
            }
        )
    )

    private val aiChapters = listOf(DetectedChapter("Uno", 1), DetectedChapter("Dos", 3))

    @Test
    fun retryWithAiReplacesBlocksAndRemapsChunks() = runTest {
        detector(aiChapters).run(book, retryWithAi = true)

        val chapters = db.chapterDao().getContents(book.id)
        assertEquals(listOf("Uno", "Dos"), chapters.map { it.title })
        assertEquals(setOf(ChapterSource.LLM), chapters.map { it.source }.toSet())
        // El fragmento de la página 3 pasa al capítulo «Dos».
        val chunk = db.chunkDao().getByBook(book.id).single()
        assertEquals(chapters.single { it.title == "Dos" }.id, chunk.chapterId)
    }

    @Test
    fun withoutRetryBlocksStay() = runTest {
        detector(aiChapters).run(book)

        assertEquals(listOf(ChapterSource.BLOCKS), db.chapterDao().getContents(book.id).map { it.source })
    }

    @Test
    fun retryKeepsBlocksWhenAiFindsNothing() = runTest {
        detector(emptyList()).run(book, retryWithAi = true)

        assertEquals(listOf("Páginas 1–4"), db.chapterDao().getContents(book.id).map { it.title })
    }

    private companion object {
        const val PAGES = 4
    }
}
