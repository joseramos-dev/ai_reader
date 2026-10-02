package dev.joseramos.aireader.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatcherTest {
    private fun NameMatcher.words(text: String) = find(text).map { text.substring(it.start, it.end) }

    @Test
    fun findsEveryNameInOnePass() {
        val matcher = NameMatcher(listOf("Raskólnikov", "Rodia", "Dunia", "Razumijin"))
        val text = "Rodia miró a Dunia. Raskólnikov no contestó, y Razumijin se rio."
        assertEquals(listOf("Rodia", "Dunia", "Raskólnikov", "Razumijin"), matcher.words(text))
    }

    @Test
    fun ignoresAccentsButRequiresInitialCapital() {
        val matcher = NameMatcher(listOf("Rodión", "Rosa"))
        assertEquals(listOf("Rodion"), matcher.words("Entonces Rodion se levantó."))
        assertTrue(matcher.find("Le regaló una rosa roja.").isEmpty())
    }

    @Test
    fun onlyMatchesWholeWords() {
        val matcher = NameMatcher(listOf("Ana"))
        assertTrue(matcher.find("Anabel y Mariana llegaron.").isEmpty())
        assertEquals(listOf("Ana"), matcher.words("Llegó Ana, cansada."))
    }

    @Test
    fun prefersTheLongestNameWhenTheyOverlap() {
        val matcher = NameMatcher(listOf("Rodión", "Rodión Románovich", "Románovich"))
        val matches = matcher.find("—Rodión Románovich, ¿es usted?")
        assertEquals(1, matches.size)
        assertEquals(1, matches.single().pattern)
    }

    @Test
    fun keepsIndicesOfTheOriginalText() {
        val matcher = NameMatcher(listOf("Sonia"))
        val text = "«¿Dónde está Sónia?», preguntó."
        val match = matcher.find(text).single()
        assertEquals("Sónia", text.substring(match.start, match.end))
    }

    @Test
    fun emptyMatcherFindsNothing() {
        assertTrue(NameMatcher(emptyList()).find("Rodia").isEmpty())
    }
}
