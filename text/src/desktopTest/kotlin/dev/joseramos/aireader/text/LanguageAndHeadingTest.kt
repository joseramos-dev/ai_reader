package dev.joseramos.aireader.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageDetectorTest {
    @Test
    fun detectsEnglishAndSpanishBooks() {
        val english = listOf(
            "It was the best of times, it was the worst of times, it was the age of wisdom, it was the age " +
                "of foolishness. He had a long way to go and she was waiting for him at the door of the house."
        )
        val spanish = listOf(
            "En un lugar de la Mancha, de cuyo nombre no quiero acordarme, no ha mucho tiempo que vivía un " +
                "hidalgo de los de lanza en astillero, adarga antigua, rocín flaco y galgo corredor."
        )
        assertEquals(Language.ENGLISH, LanguageDetector.detect(english))
        assertEquals(Language.SPANISH, LanguageDetector.detect(spanish))
    }

    @Test
    fun littleTextDefaultsToSpanish() {
        assertEquals(Language.SPANISH, LanguageDetector.detect(listOf("The end.")))
    }

    @Test
    fun englishNormalizationExpandsAbbreviationsAndRomanChapters() {
        assertEquals(
            "Mister Smith read Chapter 4, for example.",
            SpeechNormalizer.normalize("Mr. Smith read Chapter IV, e.g.", Language.ENGLISH)
        )
        assertEquals(
            listOf("Mr. Smith arrived.", "He sat down."),
            PhraseSplitter.split("Mr. Smith arrived. He sat down.")
        )
    }
}

class HeadingDetectorTest {
    @Test
    fun recognisesTypicalHeadings() {
        assertTrue(HeadingDetector.isHeading("Capítulo IV"))
        assertTrue(HeadingDetector.isHeading("CHAPTER ONE"))
        assertTrue(HeadingDetector.isHeading("XII"))
        assertTrue(HeadingDetector.isHeading("2.3 Resultados del experimento"))
        assertTrue(
            HeadingDetector.isHeading("El sueño de Raskólnikov", chapterTitles = listOf("I. El sueño de Raskólnikov"))
        )
    }

    @Test
    fun ignoresNormalParagraphsAndDialogue() {
        assertFalse(HeadingDetector.isHeading("Salió a la calle sin decir nada."))
        assertFalse(HeadingDetector.isHeading("—Capítulo aparte —dijo él"))
        assertFalse(
            HeadingDetector.isHeading("Parte de la culpa fue suya, pensó mientras caminaba hacia la casa de su madre.")
        )
        assertFalse(HeadingDetector.isHeading("Hola"))
    }
}
