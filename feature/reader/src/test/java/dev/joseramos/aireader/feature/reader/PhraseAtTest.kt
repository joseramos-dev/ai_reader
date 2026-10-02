package dev.joseramos.aireader.feature.reader

import dev.joseramos.aireader.text.PhraseSplitter
import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseAtTest {
    private val paragraph = "Salió a la calle. Hacía un calor sofocante. Nadie le miró."

    @Test
    fun findsThePhraseUnderTheFinger() {
        val phrases = PhraseSplitter.split(paragraph)
        phrases.forEachIndexed { i, phrase ->
            val middle = paragraph.indexOf(phrase) + phrase.length / 2
            assertEquals(i, phraseAt(paragraph, middle))
        }
    }

    @Test
    fun tapBetweenPhrasesBelongsToThePreviousOne() {
        val gap = paragraph.indexOf(". Hacía") + 1
        assertEquals(0, phraseAt(paragraph, gap))
        assertEquals(0, phraseAt(paragraph, 0))
    }
}
