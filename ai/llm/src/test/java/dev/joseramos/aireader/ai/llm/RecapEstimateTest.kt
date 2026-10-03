package dev.joseramos.aireader.ai.llm

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.SummaryRepository
import dev.joseramos.aireader.core.data.db.AppDatabase
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.ChapterEntity
import dev.joseramos.aireader.core.data.db.ChapterSource
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.core.data.db.PageTextEntity
import dev.joseramos.aireader.core.data.db.SummaryKind
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** La estimación del repaso reutiliza los resúmenes ya hechos y no llama a la API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecapEstimateTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val summaries = SummaryRepository(db, db.summaryDao())

    private val llm = object : LlmClient {
        override fun streamChat(request: LlmRequest): Flow<LlmEvent> = error("No debe llamar a la API")
        override suspend fun complete(request: LlmRequest): LlmResponse = error("No debe llamar a la API")
        override suspend fun hasApiKey() = true
    }

    @After
    fun tearDown() = db.close()

    private suspend fun TestScope.generator(): Pair<SummaryGenerator, List<Long>> {
        db.bookDao().upsert(
            BookEntity("b", "Libro", null, "b.pdf", "/b.pdf", PAGES, null, 1, null, IndexStatus.READY, 1f, 1)
        )
        val ids = db.chapterDao().insertAll(
            (0 until CHAPTERS).map { i ->
                ChapterEntity(
                    bookId = "b",
                    number = i + 1,
                    title = "Capítulo ${i + 1}",
                    startPage = i * PAGES_PER_CHAPTER + 1,
                    endPage = (i + 1) * PAGES_PER_CHAPTER,
                    source = ChapterSource.OUTLINE
                )
            }
        )
        val text = "palabra ".repeat(PAGE_CHARS / "palabra ".length)
        for (page in 1..PAGES) db.pageTextDao().upsert(PageTextEntity("b", page, text, text, "[\"$text\"]", false, 1))
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "settings.preferences_pb") }
        )
        val generator = SummaryGenerator(
            llm,
            Prompts(context),
            BookContentRepository(db.chapterDao(), db.pageTextDao()),
            BookRepository(db.bookDao(), dispatcher),
            summaries,
            settings,
            backgroundScope
        )
        return generator to ids
    }

    @Test
    fun countsOnlyMissingChapterSummaries() = runTest {
        val (generator, chapters) = generator()
        summaries.save("b", chapters[0], SummaryKind.CHAPTER_SHORT, "Resumen del capítulo 1.", "modelo")

        // En el capítulo 3: falta el resumen breve del 2 (una llamada) y queda la llamada final.
        val missingOne = generator.estimateRecap("b", untilPage = 25)
        assertEquals(2 * SUMMARY_TOKENS, missingOne.output)
        assertTrue("Incluye el texto del capítulo 2", missingOne.input >= PAGES_PER_CHAPTER * PAGE_CHARS / 4)

        summaries.save("b", chapters[1], SummaryKind.CHAPTER_SHORT, "Resumen del capítulo 2.", "modelo")
        val nothingMissing = generator.estimateRecap("b", untilPage = 25)
        assertEquals(SUMMARY_TOKENS, nothingMissing.output)
        assertTrue(nothingMissing.input < missingOne.input)
    }

    @Test
    fun updatesThePreviousRecapWithWhatWasReadSince() = runTest {
        val (generator, _) = generator()
        // Sin resúmenes de capítulo: el repaso completo tendría que generar los de los capítulos 1 y 2.
        val full = generator.estimateRecap("b", untilPage = 25)
        assertEquals(3 * SUMMARY_TOKENS, full.output)

        // Con un repaso hasta la página 22 se parte de él: solo se añaden las páginas 23-25, en una llamada.
        summaries.save("b", null, SummaryKind.RECAP, "Hasta ahora…", "modelo", untilPage = 22)
        val update = generator.estimateRecap("b", untilPage = 25)
        assertEquals(SUMMARY_TOKENS, update.output)
        assertTrue("Solo 3 páginas nuevas", update.input < 4 * PAGE_CHARS / 4 + 2_000)

        // Pedirlo de nuevo en la misma página lo rehace entero.
        val again = generator.estimateRecap("b", untilPage = 22)
        assertEquals(full.output, again.output)
    }

    private companion object {
        const val CHAPTERS = 3
        const val PAGES_PER_CHAPTER = 10
        const val PAGES = CHAPTERS * PAGES_PER_CHAPTER
        const val PAGE_CHARS = 2_000
        const val SUMMARY_TOKENS = 500L
    }
}
