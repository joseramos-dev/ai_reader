package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.ChapterKeyPoints
import dev.joseramos.aireader.core.data.book.KeyPoint
import dev.joseramos.aireader.core.data.db.KeyPointsStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El recorrido completo: reglas, juez (falso) y páginas. */
class BookLocatorTest {
    private val chapters = listOf(
        Chapter(1, 1, "Capítulo 1", 1, 10),
        Chapter(2, 2, "Capítulo 2", 11, 20),
        Chapter(3, 3, "Capítulo 3", 21, 30),
        Chapter(4, 4, "Capítulo 4", 31, 40)
    )

    private fun ready(chapterId: Long, vararg points: Pair<String, Int>) =
        chapterId to ChapterKeyPoints(chapterId, KeyPointsStatus.READY, points.map { KeyPoint(it.first, it.second) })

    private val events = mapOf(
        ready(1, "Raskólnikov visita a la vieja usurera." to 5),
        ready(2, "Raskólnikov mata a la usurera y a su hermana con un hacha." to 15),
        ready(3, "Raskólnikov cae enfermo." to 22, "Lo visita Razumijin." to 28),
        4L to ChapterKeyPoints(4, KeyPointsStatus.REFUSED, emptyList())
    )

    private class FakeJudge(private val bookPart: Boolean? = null, private val anchor: EventAnchor? = null) :
        LocationJudge {
        val expressions = mutableListOf<String>()
        var eventChapters: List<ChapterEvents>? = null

        override suspend fun refersToBook(question: String, expression: String): Boolean? {
            expressions += expression
            return bookPart
        }

        override suspend fun locateEvent(question: String, expression: String, chapters: List<ChapterEvents>) =
            anchor.also {
                expressions += expression
                eventChapters = chapters
            }
    }

    private fun context(spoilerLimit: Int? = null, literature: Boolean = true) =
        LocationContext(chapters, 40, literature, ReadingContext(currentPage = 15, spoilerLimit = spoilerLimit))

    private fun locator(judge: LocationJudge, saved: Map<Long, ChapterKeyPoints> = events) =
        BookLocator(judge) { saved }

    @Test
    fun clearLocationsDoNotAskTheJudge() = runTest {
        val judge = FakeJudge(bookPart = false)

        val located = locator(judge).locate("b", "¿Qué hace Raskólnikov al final del libro?", context())

        assertEquals(BookLocation.Book(Stretch.END), located?.location)
        assertEquals(listOf(31..40), located?.pages)
        assertEquals("¿Qué hace Raskólnikov?", located?.query)
        assertTrue(judge.expressions.isEmpty())
    }

    @Test
    fun doubtfulLocationsAreDecidedByTheJudge() = runTest {
        val yes = FakeJudge(bookPart = true)
        val located = locator(yes).locate("b", "¿Qué hace Raskólnikov al final?", context())
        assertEquals(listOf(31..40), located?.pages)
        assertEquals("¿Qué hace Raskólnikov?", located?.query)
        assertEquals(listOf("al final"), yes.expressions)

        val no = FakeJudge(bookPart = false)
        assertNull(locator(no).locate("b", "¿Qué tiene Raskólnikov al principio de su mano?", context()))
        assertEquals(listOf("al principio de su mano"), no.expressions)

        // Si el juez no puede decidir (sin clave, sin red), la pregunta no se sitúa.
        assertNull(locator(FakeJudge(bookPart = null)).locate("b", "¿Qué hace al final?", context()))
    }

    @Test
    fun eventsAreLookedUpAmongTheSavedFacts() = runTest {
        val judge = FakeJudge(anchor = EventAnchor(2, EventRelation.AFTER))

        val located = locator(judge).locate("b", "¿Qué hace Raskólnikov después del crimen?", context())

        assertEquals(BookLocation.AroundEvent(2, EventRelation.AFTER), located?.location)
        assertEquals(listOf(11..40), located?.pages)
        assertEquals("¿Qué hace Raskólnikov?", located?.query)
        // El capítulo cuyos hechos clave rechazó el modelo no se manda.
        assertEquals(listOf(1L, 2L, 3L), judge.eventChapters?.map { it.chapter.id })
        assertEquals(listOf("Raskólnikov cae enfermo.", "Lo visita Razumijin."), judge.eventChapters?.last()?.events)
    }

    @Test
    fun withoutFactsOrInOtherBooksEventsAreNotLookedUp() = runTest {
        val judge = FakeJudge(anchor = EventAnchor(2, EventRelation.AFTER))

        assertNull(locator(judge, saved = emptyMap()).locate("b", "¿Qué hace después del crimen?", context()))
        assertNull(locator(judge).locate("b", "¿Qué hace después del crimen?", context(literature = false)))
        assertTrue(judge.expressions.isEmpty())
    }

    @Test
    fun withAntiSpoilersOnlyWhatHasBeenRead() = runTest {
        val judge = FakeJudge(anchor = EventAnchor(1, EventRelation.AFTER))

        val after = locator(judge).locate("b", "¿Qué hace después de visitar a la usurera?", context(spoilerLimit = 25))

        // A la IA solo le llegan los hechos que ocurren hasta la página leída (del capítulo 3, el primero).
        assertEquals(listOf(1L, 2L, 3L), judge.eventChapters?.map { it.chapter.id })
        assertEquals(listOf("Raskólnikov cae enfermo."), judge.eventChapters?.last()?.events)
        assertEquals(listOf(1..25), after?.pages)

        // El final del libro aún no se ha leído: se sabe de qué parte habla, pero no hay páginas.
        val end = locator(judge).locate("b", "¿Qué pasa al final del libro?", context(spoilerLimit = 25))
        assertEquals(BookLocation.Book(Stretch.END), end?.location)
        assertTrue(end?.pages.orEmpty().isEmpty())
    }

    @Test
    fun keyPointsOfEveryChapterUpToTheReaderArePreparedFirst() = runTest {
        val judge = FakeJudge(anchor = EventAnchor(3, EventRelation.AFTER))
        val saved = events.filterKeys { it == 1L }.toMutableMap()
        val prepared = mutableListOf<List<Long>>()
        val locator = BookLocator(judge) { saved }
        val prepare: suspend (List<Chapter>) -> Unit = { chapters ->
            prepared += chapters.map { it.id }
            chapters.forEach { chapter -> events[chapter.id]?.let { saved[chapter.id] = it } }
        }

        // Con anti-spoilers, hasta la página más avanzada leída (el capítulo 3, a medias, incluido).
        val located = locator.locate("b", "¿Qué hace después de caer enfermo?", context(spoilerLimit = 25), prepare)
        assertEquals(listOf(1L, 2L, 3L), prepared.single())
        assertEquals(listOf(1L, 2L, 3L), judge.eventChapters?.map { it.chapter.id })
        assertEquals(listOf(21..25), located?.pages)

        // Sin anti-spoilers, hasta el capítulo que se está leyendo (página 15).
        locator.locate("b", "¿Qué hace después de caer enfermo?", context(), prepare)
        assertEquals(listOf(1L, 2L), prepared.last())

        // Las preguntas que no hablan de un hecho no preparan nada.
        locator.locate("b", "¿Qué pasa en el capítulo 2?", context(), prepare)
        assertEquals(2, prepared.size)
    }

    @Test
    fun locationsThatDoNotExistInTheBookAreIgnored() = runTest {
        assertNull(locator(FakeJudge()).locate("b", "¿Qué pasa en el capítulo 9?", context()))
        assertNull(locator(FakeJudge()).locate("b", "¿Qué pasa en la segunda parte?", context()))
        assertNull(locator(FakeJudge()).locate("b", "¿Quién mató a la usurera?", context()))
    }
}
