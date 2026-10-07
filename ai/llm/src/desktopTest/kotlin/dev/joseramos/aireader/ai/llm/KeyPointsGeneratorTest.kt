package dev.joseramos.aireader.ai.llm

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.joseramos.aireader.core.data.book.BookContentRepository
import dev.joseramos.aireader.core.data.book.BookRepository
import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.ChapterKeyPoints
import dev.joseramos.aireader.core.data.book.KeyPoint
import dev.joseramos.aireader.core.data.book.KeyPointsRepository
import dev.joseramos.aireader.core.data.db.AppDatabase
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.ChapterEntity
import dev.joseramos.aireader.core.data.db.ChapterSource
import dev.joseramos.aireader.core.data.db.DocumentType
import dev.joseramos.aireader.core.data.db.IndexStatus
import dev.joseramos.aireader.core.data.db.KeyPointsStatus
import dev.joseramos.aireader.core.data.db.PageTextEntity
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeyPointsGeneratorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder<AppDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    private val keyPoints = KeyPointsRepository(db.keyPointsDao())
    private val prompts = ConcurrentLinkedQueue<String>()

    /** Un hecho por cada marca de página del texto; se niega con el capítulo «Prohibido». */
    private val llm = object : LlmClient {
        override fun streamChat(request: LlmRequest): Flow<LlmEvent> = error("El repaso no usa el chat")

        override suspend fun complete(request: LlmRequest): LlmResponse {
            val prompt = request.messages.single().text
            prompts += prompt
            if (request.jsonSchema == null) return LlmResponse("Hasta ahora…", LlmUsage())
            if ("«Prohibido»" in prompt) throw LlmException.Refused()
            val pages = Regex("""\[p\. (\d+)]""").findAll(prompt).map { it.groupValues[1] }.toList()
            val points = pages.joinToString(",") { """{"texto": "Pasa algo en la página $it", "pagina": $it}""" }
            return LlmResponse("""{"puntos": [$points]}""", LlmUsage())
        }

        override suspend fun hasApiKey() = true
    }

    @After
    fun tearDown() = db.close()

    private suspend fun TestScope.generator(vararg titles: String): Pair<KeyPointsGenerator, List<Chapter>> {
        db.bookDao().upsert(
            BookEntity(
                "b", "Libro", null, "b.pdf", "/b.pdf", titles.size * PAGES_PER_CHAPTER, null, 1, null,
                IndexStatus.READY, 1f, 1, documentType = DocumentType.LITERATURE
            )
        )
        val ids = db.chapterDao().insertAll(
            titles.mapIndexed { i, title ->
                ChapterEntity(
                    bookId = "b",
                    number = i + 1,
                    title = title,
                    startPage = i * PAGES_PER_CHAPTER + 1,
                    endPage = (i + 1) * PAGES_PER_CHAPTER,
                    source = ChapterSource.OUTLINE
                )
            }
        )
        for (page in 1..titles.size * PAGES_PER_CHAPTER) {
            db.pageTextDao().upsert(
                PageTextEntity("b", page, "Texto $page", "Texto $page", "[\"Texto $page\"]", false, 1)
            )
        }
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "settings.preferences_pb") }
        )
        val content = BookContentRepository(db.chapterDao(), db.pageTextDao(), db.pageLayoutDao())
        val generator = KeyPointsGenerator(
            llm,
            Prompts(),
            content,
            BookRepository(db.bookDao(), UnconfinedTestDispatcher(testScheduler)),
            keyPoints,
            settings,
            backgroundScope
        )
        val chapters = content.chapters("b")
        assertEquals(ids, chapters.map { it.id })
        return generator to chapters
    }

    @Test
    fun ensureGeneratesOnlyTheMissingChaptersWithTheirPages() = runTest {
        val (generator, chapters) = generator("Uno", "Dos", "Tres")
        keyPoints.save("b", chapters[0].id, KeyPointsStatus.READY, listOf(KeyPoint("Ya estaba", 2)), "modelo")
        val progress = mutableListOf<Pair<Int, Int>>()

        val saved = generator.ensure("b", chapters) { done, total -> progress += done to total }

        assertEquals(2, prompts.size)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
        assertEquals(listOf("Ya estaba"), saved.getValue(chapters[0].id).points.map { it.text })
        assertEquals((5..8).toList(), saved.getValue(chapters[1].id).points.map { it.page })
        assertTrue(prompts.all { "[p. " in it && "Libro" in it })
        assertEquals(TokenEstimate(), generator.estimate("b", chapters))
    }

    @Test
    fun refusedChaptersAreRememberedAndNotAskedAgain() = runTest {
        val (generator, chapters) = generator("Uno", "Prohibido")

        val saved = generator.ensure("b", chapters)
        generator.ensure("b", chapters)

        assertEquals(KeyPointsStatus.REFUSED, saved.getValue(chapters[1].id).status)
        assertTrue(saved.getValue(chapters[1].id).points.isEmpty())
        assertEquals(2, prompts.size)
    }

    @Test
    fun recapUsesOnlyTheKeyPointsUntilThePage() = runTest {
        val (generator, chapters) = generator("Uno", "Dos", "Tres")
        assertTrue(generator.estimateRecap("b", untilPage = 6).total > 0)
        assertTrue(prompts.isEmpty())

        generator.recap("b", untilPage = 6)
        val recap = generator.recaps.first { "b" in it }.getValue("b")

        assertEquals(Recap("Hasta ahora…", 6), recap)
        // El tercer capítulo empieza después de la página 6: no se generan sus hechos clave.
        assertNull(keyPoints.get(chapters[2].id))
        val recapPrompt = prompts.last()
        assertTrue("(p. 6)" in recapPrompt)
        assertFalse("(p. 7)" in recapPrompt)
    }

    @Test
    fun pageBlocksKeepWholePagesAndMarkThem() {
        val pages = listOf(1 to "a".repeat(60), 2 to "b".repeat(60), 3 to "c".repeat(200))

        val blocks = KeyPointsGenerator.pageBlocks(pages, maxChars = 130)

        assertEquals(listOf(listOf(1, 2), listOf(3)), blocks.map { block -> block.map { it.first } })
        assertEquals("[p. 1]\n${"a".repeat(60)}\n\n[p. 2]\n${"b".repeat(60)}", KeyPointsGenerator.blockText(blocks[0]))
    }

    @Test
    fun answersAreCleanedAndPagesKeptInsideTheBlock() {
        val answer = """{"puntos": [{"texto": " Huye ", "pagina": 3}, {"texto": "", "pagina": 4},
            {"texto": "Vuelve", "pagina": 99}]}"""

        assertEquals(
            listOf(KeyPoint("Huye", 3), KeyPoint("Vuelve", 10)),
            KeyPointsGenerator.parseKeyPoints(answer, 1..10)
        )
        assertNull(KeyPointsGenerator.parseKeyPoints("""{"puntos": [{"texto": "Corta""", 1..10))
    }

    @Test
    fun morePagesAskForMorePointsUpToALimit() {
        assertEquals(1 to 3, KeyPointsGenerator.pointsRange(1))
        assertEquals(2 to 5, KeyPointsGenerator.pointsRange(10))
        assertEquals(2 to 15, KeyPointsGenerator.pointsRange(80))
    }

    @Test
    fun recapPointsSkipChaptersWithoutPoints() {
        val one = Chapter(1, 1, "Uno", 1, 4)
        val two = Chapter(2, 2, "Dos", 5, 8)
        val saved = mapOf(
            1L to ChapterKeyPoints(1, KeyPointsStatus.READY, listOf(KeyPoint("Empieza", 2), KeyPoint("Sigue", 4))),
            2L to ChapterKeyPoints(2, KeyPointsStatus.READY, listOf(KeyPoint("Después", 7)))
        )

        assertEquals("Uno\n- Empieza (p. 2)", KeyPointsGenerator.recapPoints(listOf(one, two), saved, untilPage = 3))
    }

    private companion object {
        const val PAGES_PER_CHAPTER = 4
    }
}
