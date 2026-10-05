package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.core.data.book.Chapter
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

    private val events = mapOf(
        1L to "- Raskólnikov visita a la vieja usurera.",
        2L to "- Raskólnikov mata a la usurera y a su hermana con un hacha.",
        3L to "- Raskólnikov cae enfermo.\n- Lo visita Razumijin.",
        4L to "El modelo no ha querido enumerar los hechos de este capítulo."
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

    private fun locator(judge: LocationJudge, saved: Map<Long, String> = events) = BookLocator(judge) { saved }

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
        // El capítulo sin viñetas (el aviso de que el modelo se negó) no se manda.
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

        // A la IA solo le llegan los hechos de los capítulos leídos enteros.
        assertEquals(listOf(1L, 2L), judge.eventChapters?.map { it.chapter.id })
        assertEquals(listOf(1..25), after?.pages)

        // El final del libro aún no se ha leído: se sabe de qué parte habla, pero no hay páginas.
        val end = locator(judge).locate("b", "¿Qué pasa al final del libro?", context(spoilerLimit = 25))
        assertEquals(BookLocation.Book(Stretch.END), end?.location)
        assertTrue(end?.pages.orEmpty().isEmpty())
    }

    @Test
    fun locationsThatDoNotExistInTheBookAreIgnored() = runTest {
        assertNull(locator(FakeJudge()).locate("b", "¿Qué pasa en el capítulo 9?", context()))
        assertNull(locator(FakeJudge()).locate("b", "¿Qué pasa en la segunda parte?", context()))
        assertNull(locator(FakeJudge()).locate("b", "¿Quién mató a la usurera?", context()))
    }
}
