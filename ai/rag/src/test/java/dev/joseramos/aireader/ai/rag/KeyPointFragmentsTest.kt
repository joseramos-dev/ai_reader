package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.ChapterKeyPoints
import dev.joseramos.aireader.core.data.book.KeyPoint
import dev.joseramos.aireader.core.data.db.KeyPointsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPointFragmentsTest {
    private val chapters = listOf(
        Chapter(1, 1, "Capítulo 1", 1, 10),
        Chapter(2, 2, "Capítulo 2", 11, 20),
        Chapter(3, 3, "Capítulo 3", 21, 30),
        Chapter(4, 4, "Capítulo 4", 31, 40),
        Chapter(5, 5, "Capítulo 5", 41, 50)
    )

    private val saved = mapOf(
        1L to ChapterKeyPoints(1, KeyPointsStatus.READY, listOf(KeyPoint("Visita a la usurera", 3))),
        2L to ChapterKeyPoints(2, KeyPointsStatus.READY, listOf(KeyPoint("El crimen", 12), KeyPoint("Huye", 18))),
        3L to ChapterKeyPoints(3, KeyPointsStatus.REFUSED, emptyList()),
        4L to ChapterKeyPoints(4, KeyPointsStatus.EMPTY, emptyList())
    )

    @Test
    fun oneFragmentPerChapterWithPagesAndTheRestForTheText() {
        val plan = KeyPointFragments.plan(chapters, saved, limit = null)

        assertEquals(listOf(1L, 2L), plan.fragments.map { it.first.id })
        // Rechazado o aún sin generar: se manda su texto. Sin texto: nada.
        assertEquals(listOf(3L, 5L), plan.withoutKeyPoints.map { it.id })
        val second = plan.fragments[1].second
        assertEquals("- El crimen [p. 12]\n- Huye [p. 18]", second.text)
        assertEquals(11 to 20, second.startPage to second.endPage)
        assertEquals("Capítulo 2", second.chapter)
        assertTrue(second.keyPoints)
    }

    @Test
    fun withAntiSpoilersOnlyWhatHappensUntilThePage() {
        val plan = KeyPointFragments.plan(chapters.take(2), saved, limit = 15)

        val second = plan.fragments.single { it.first.id == 2L }.second
        assertEquals("- El crimen [p. 12]", second.text)
        assertEquals(15, second.endPage)
    }

    @Test
    fun onlyThePointsOfThePagesOfTheQuestion() {
        val plan = KeyPointFragments.plan(chapters.take(2), saved, limit = null) { it.page in 15..20 }

        assertEquals(listOf("- Huye [p. 18]"), plan.fragments.map { it.second.text })
    }
}
