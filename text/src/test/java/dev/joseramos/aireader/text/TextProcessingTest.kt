package dev.joseramos.aireader.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanerTest {
    @Test
    fun removesRepeatedHeadersFootersAndPageNumbers() {
        val bodies = listOf(
            "El viento soplaba\nsobre el puerto.",
            "Nadie esperaba\nla tormenta.",
            "Los barcos volvieron\nantes del anochecer.",
            "La ciudad quedó\nen silencio.",
            "Al día siguiente\nlucía el sol.",
            "Todo parecía\nhaber cambiado."
        )
        val pages = bodies.mapIndexed { i, body -> "EL LIBRO DE LAS PRUEBAS\n$body\n${i + 1}" }
        val layout = TextCleaner.detectLayout(pages.map { it.lines() })

        val paragraphs = TextCleaner.pageToParagraphs(pages[2], layout)

        assertEquals(listOf("Los barcos volvieron antes del anochecer."), paragraphs)
    }

    @Test
    fun joinsWordsSplitByLineBreakHyphen() {
        val paragraphs = TextCleaner.pageToParagraphs("La economía inter-\nnacional creció mucho.", DocumentLayout())
        assertEquals(listOf("La economía internacional creció mucho."), paragraphs)
    }

    @Test
    fun startsNewParagraphAfterSentenceEndAndCapital() {
        val raw = "Primera frase del párrafo.\nSegundo párrafo que empieza aquí\ny sigue."
        assertEquals(
            listOf("Primera frase del párrafo.", "Segundo párrafo que empieza aquí y sigue."),
            TextCleaner.pageToParagraphs(raw, DocumentLayout())
        )
    }

    @Test
    fun mergesWordSplitAcrossPages() {
        val (left, right) = TextCleaner.mergeAcrossPages(
            listOf("El tratado inter-"),
            listOf("nacional se firmó. Después", "Otro")
        )
        assertEquals(listOf("El tratado internacional"), left)
        assertEquals(listOf("se firmó. Después", "Otro"), right)
    }

    @Test
    fun normalizesLigatures() {
        assertEquals("La figura", TextCleaner.normalizeCharacters("La ﬁgura"))
    }
}

class PhraseSplitterTest {
    @Test
    fun splitsSentencesButNotAbbreviationsOrInitials() {
        val phrases = PhraseSplitter.split(
            "El Sr. Gómez llegó a la pág. 12. Leyó a J. R. R. Tolkien. ¿Y después? Nada."
        )
        assertEquals(
            listOf("El Sr. Gómez llegó a la pág. 12.", "Leyó a J. R. R. Tolkien.", "¿Y después?", "Nada."),
            phrases
        )
    }

    @Test
    fun longSentencesAreCutAtCommas() {
        val long = (1..30).joinToString(", ") { "elemento número $it" } + "."
        val phrases = PhraseSplitter.split(long, maxChars = 120)
        assertTrue(phrases.size > 1)
        assertTrue(phrases.all { it.length <= 120 })
        assertEquals(long, phrases.joinToString(" "))
    }
}

class SpeechNormalizerTest {
    @Test
    fun expandsAbbreviationsAndRomanNumeralsInContext() {
        assertEquals(
            "Véase la página 4 del capítulo 4, en el siglo 18 por ejemplo.",
            SpeechNormalizer.normalize("Véase la pág. 4 del capítulo IV, en el siglo XVIII p. ej.")
        )
    }

    @Test
    fun romanToIntRejectsInvalidNumerals() {
        assertEquals(14, SpeechNormalizer.romanToInt("XIV"))
        assertNull(SpeechNormalizer.romanToInt("IIII"))
        assertNull(SpeechNormalizer.romanToInt("ABC"))
    }
}

class ChapterHeuristicsTest {
    @Test
    fun detectsChapterHeadingsAndCombinesTitle() {
        val pages = listOf(
            listOf("Prólogo", "Hace mucho tiempo…"),
            listOf("texto corriente de la página"),
            listOf("Capítulo 1", "El viaje", "Era una mañana fría"),
            listOf("texto corriente"),
            listOf("CAPÍTULO II", "La vuelta", "Volvieron al pueblo")
        )
        val chapters = ChapterHeuristics.detect(pages)
        assertEquals(listOf(1, 3, 5), chapters.map { it.startPage })
        assertEquals("Capítulo 1. El viaje", chapters[1].title)
    }

    @Test
    fun fallsBackToPageBlocks() {
        val blocks = ChapterHeuristics.blocks(45, pagesPerBlock = 20)
        assertEquals(listOf(1, 21, 41), blocks.map { it.startPage })
        assertEquals("Páginas 41–45", blocks.last().title)
    }
}
