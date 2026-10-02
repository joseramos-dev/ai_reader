package dev.joseramos.aireader.tts

import dev.joseramos.aireader.core.data.book.PageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageNavigationTest {
    private fun text(page: Int, vararg paragraphs: String) = PageText(page, paragraphs.toList(), isScanned = false)

    private fun scanned(page: Int) = PageText(page, emptyList(), isScanned = true)

    private val book = listOf(
        text(1, "Primera página. Con dos frases."),
        text(2),
        scanned(3),
        text(4, "   ", "\n"),
        text(5, "Por fin hay texto otra vez."),
        text(6, "Última página.")
    )

    @Test
    fun pageWithPhrasesIsReadable() {
        assertTrue(PageNavigation.isReadable(text(1, "Hola.")))
    }

    @Test
    fun emptyBlankAndScannedPagesAreNotReadable() {
        assertFalse(PageNavigation.isReadable(text(2)))
        assertFalse(PageNavigation.isReadable(text(4, "   ", "\n")))
        assertFalse(PageNavigation.isReadable(scanned(3)))
        assertFalse(PageNavigation.isReadable(PageText(7, listOf("Pie de imagen"), isScanned = true)))
    }

    @Test
    fun nextSkipsPagesWithoutText() {
        assertEquals(5, PageNavigation.nextReadable(book, current = 1))
        assertEquals(6, PageNavigation.nextReadable(book, current = 5))
    }

    @Test
    fun nextAtTheLastPageWithTextStaysPut() {
        assertNull(PageNavigation.nextReadable(book, current = 6))
        assertNull(PageNavigation.nextReadable(emptyList(), current = 1))
    }

    @Test
    fun previousSkipsPagesWithoutText() {
        assertEquals(1, PageNavigation.previousReadable(book, current = 5))
        assertEquals(5, PageNavigation.previousReadable(book, current = 6))
    }

    @Test
    fun previousAtTheFirstPageWithTextHasNoTarget() {
        assertNull(PageNavigation.previousReadable(book, current = 1))
    }

    @Test
    fun worksWithMissingAndUnorderedPages() {
        val pages = listOf(text(9, "Nueve."), text(3, "Tres."), text(12, "Doce."))
        assertEquals(9, PageNavigation.nextReadable(pages, current = 4))
        assertEquals(3, PageNavigation.previousReadable(pages, current = 9))
        assertEquals(9, PageNavigation.previousReadable(pages, current = 11))
    }
}
