package dev.joseramos.aireader.ai.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterHeadsTest {
    @Test
    fun onlyPagesThatCanStartAChapterAreSent() {
        val heads = (1..20).map { page ->
            page to when (page) {
                1 -> "La isla del tesoro"
                5 -> "I. El viejo lobo de mar"
                12 -> "II. Perro Negro aparece y desaparece"
                // El resto empieza a mitad de frase o con la cabecera del libro, repetida.
                else -> if (page % 2 == 0) "la isla del tesoro" else "y siguió andando hasta la posada"
            }
        }

        val candidates = AiChapterDetection.candidateHeads(heads)

        assertEquals(listOf(5, 12), candidates.map { it.first })
    }

    @Test
    fun longLinesAreCutAndBlankOnesSkipped() {
        val candidates = AiChapterDetection.candidateHeads(
            listOf(
                1 to "   ",
                2 to "Capítulo primero: " + "x".repeat(200)
            )
        )
        assertEquals(1, candidates.size)
        assertEquals(80, candidates.single().second.length)
    }
}
