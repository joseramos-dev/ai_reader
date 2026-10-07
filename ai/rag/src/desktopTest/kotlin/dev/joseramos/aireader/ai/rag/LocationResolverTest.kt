package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.core.data.book.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationResolverTest {
    /** Un libro de 100 páginas en dos partes, con epílogo. */
    private val book = listOf(
        Chapter(1, 1, "PARTE 1. CAPÍTULO 1", 1, 10),
        Chapter(2, 2, "CAPÍTULO 2", 11, 20),
        Chapter(3, 3, "CAPÍTULO 3", 21, 30),
        Chapter(4, 4, "PARTE 2", 31, 31),
        Chapter(5, 5, "CAPÍTULO 1", 32, 50),
        Chapter(6, 6, "CAPÍTULO 2", 51, 70),
        Chapter(7, 7, "CAPÍTULO 3", 71, 90),
        Chapter(8, 8, "EPÍLOGO", 91, 100)
    )

    private fun pages(location: BookLocation, page: Int = 55) = LocationResolver.pages(location, book, 100, page)

    private fun chapters(vararg refs: ChapterRef, stretch: Stretch = Stretch.WHOLE) =
        BookLocation.Chapters(refs.toList(), stretch)

    @Test
    fun stretchesAreAQuarterOfTheBook() {
        assertEquals(listOf(1..25), pages(BookLocation.Book(Stretch.START)))
        assertEquals(listOf(38..62), pages(BookLocation.Book(Stretch.MIDDLE)))
        assertEquals(listOf(76..100), pages(BookLocation.Book(Stretch.END)))
        assertEquals(listOf(1..100), pages(BookLocation.Book(Stretch.WHOLE)))
        // Al menos una página.
        assertEquals(10..10, LocationResolver.stretch(10..13, Stretch.START))
        assertEquals(7..7, LocationResolver.stretch(7..7, Stretch.END))
        assertNull(LocationResolver.pages(BookLocation.Book(Stretch.END), emptyList(), 0, 1))
    }

    @Test
    fun chaptersAreFoundInThePartBeingRead() {
        assertEquals(listOf(71..90), pages(chapters(ChapterRef.Number(3))))
        assertEquals(listOf(21..30), pages(chapters(ChapterRef.Number(3)), page = 5))
        assertEquals(listOf(21..30), pages(chapters(ChapterRef.Number(3, part = 1))))
        assertEquals(listOf(71..90), pages(chapters(ChapterRef.Number(3, part = 2)), page = 5))
        assertNull(pages(chapters(ChapterRef.Number(3, part = 5))))
        assertNull(pages(chapters(ChapterRef.Number(9))))
    }

    @Test
    fun severalChaptersAndStretchesOfAChapter() {
        assertEquals(listOf(32..50, 71..90), pages(chapters(ChapterRef.Number(1), ChapterRef.Number(3))))
        // Los capítulos seguidos se unen en un solo tramo.
        assertEquals(listOf(51..90), pages(chapters(ChapterRef.Number(2), ChapterRef.Number(3))))
        assertEquals(listOf(86..90), pages(chapters(ChapterRef.Number(3), stretch = Stretch.END)))
    }

    @Test
    fun lastChapterEpilogueAndParts() {
        assertEquals(listOf(91..100), pages(chapters(ChapterRef.Last)))
        assertEquals(listOf(91..100), pages(chapters(ChapterRef.Titled("epilogo"))))
        assertNull(pages(chapters(ChapterRef.Titled("prologo"))))
        assertEquals(listOf(1..30), pages(BookLocation.Part(1)))
        assertEquals(listOf(31..100), pages(BookLocation.Part(2)))
        assertEquals(listOf(1..8), pages(BookLocation.Part(1, Stretch.START)))
        assertNull(pages(BookLocation.Part(3)))
    }

    /** El hecho ocurre en el capítulo 5 (p. 32–50). */
    @Test
    fun aroundAnEventAreTheChaptersNextToIt() {
        assertEquals(listOf(32..90), pages(BookLocation.AroundEvent(5, EventRelation.AFTER)))
        assertEquals(listOf(21..50), pages(BookLocation.AroundEvent(5, EventRelation.BEFORE)))
        assertEquals(listOf(32..50), pages(BookLocation.AroundEvent(5, EventRelation.DURING)))
        assertEquals(listOf(71..100), pages(BookLocation.AroundEvent(7, EventRelation.AFTER)))
        assertNull(pages(BookLocation.AroundEvent(42, EventRelation.AFTER)))
    }

    @Test
    fun antiSpoilersCutWhatHasNotBeenRead() {
        assertEquals(listOf(76..80), LocationResolver.limit(listOf(76..100), 80))
        assertEquals(listOf(1..10, 20..25), LocationResolver.limit(listOf(1..10, 20..30), 25))
        assertTrue(LocationResolver.limit(listOf(76..100), 50).isEmpty())
        assertEquals(listOf(76..100), LocationResolver.limit(listOf(76..100), null))
    }
}
