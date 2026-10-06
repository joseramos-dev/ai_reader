package dev.joseramos.aireader.ai.characters

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.joseramos.aireader.ai.llm.LlmClient
import dev.joseramos.aireader.ai.llm.LlmEvent
import dev.joseramos.aireader.ai.llm.LlmRequest
import dev.joseramos.aireader.ai.llm.LlmResponse
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.db.AppDatabase
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.ChapterEntity
import dev.joseramos.aireader.core.data.db.ChapterSource
import dev.joseramos.aireader.core.data.db.CharacterScanEntity
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.core.data.db.PageTextEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** La estimación del análisis cuenta solo los capítulos pendientes, por los mismos bloques que el análisis. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CharacterEstimateTest {
    private lateinit var db: AppDatabase
    private lateinit var extractor: CharacterExtractor
    private var firstChapterId = 0L

    /** El cliente no debe llamarse: estimar no gasta peticiones. */
    private val llm = object : LlmClient {
        override fun streamChat(request: LlmRequest): Flow<LlmEvent> = error("No debe llamar a la API")
        override suspend fun complete(request: LlmRequest): LlmResponse = error("No debe llamar a la API")
        override suspend fun hasApiKey() = true
    }

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.bookDao().upsert(
            BookEntity("b", "Libro", null, "b.pdf", "/b.pdf", PAGES, null, 1, null, IndexStatus.READY, 1f, 1)
        )
        firstChapterId = db.chapterDao().insertAll(
            listOf(
                ChapterEntity(
                    bookId = "b",
                    number = 1,
                    title = "I",
                    startPage = 1,
                    endPage = 10,
                    source = ChapterSource.OUTLINE
                ),
                ChapterEntity(
                    bookId = "b",
                    number = 2,
                    title = "II",
                    startPage = 11,
                    endPage = PAGES,
                    source = ChapterSource.OUTLINE
                )
            )
        ).first()
        val text = "palabra ".repeat(PAGE_CHARS / "palabra ".length)
        for (page in 1..PAGES) {
            db.pageTextDao().upsert(PageTextEntity("b", page, text, text, "[\"$text\"]", false, 1))
        }
        extractor = CharacterExtractor(
            context,
            llm,
            BookContentRepository(db.chapterDao(), db.pageTextDao(), db.pageLayoutDao()),
            db.characterDao(),
            CharacterMerger(db, db.characterDao())
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun estimatesPendingChaptersByBlocks() = runTest {
        val all = extractor.estimate("b")
        // 10 páginas de 3 000 caracteres caben en un bloque (80 000); las 30 del capítulo II, en dos.
        assertEquals(3 * OUTPUT_PER_BLOCK, all.output)
        assertTrue("Al menos el texto de las 40 páginas", all.input >= PAGES * PAGE_CHARS / CHARS_PER_TOKEN)

        db.characterDao().upsertScan(CharacterScanEntity("b", firstChapterId, "modelo", 0))
        val pending = extractor.estimate("b")
        assertEquals(2 * OUTPUT_PER_BLOCK, pending.output)
        assertTrue(pending.input < all.input)
    }

    private companion object {
        const val PAGES = 40
        const val PAGE_CHARS = 3_000
        const val CHARS_PER_TOKEN = 4
        const val OUTPUT_PER_BLOCK = 1_500L
    }
}
