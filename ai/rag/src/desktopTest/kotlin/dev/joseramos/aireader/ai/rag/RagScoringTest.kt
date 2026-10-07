package dev.joseramos.aireader.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RagScoringTest {
    @Test
    fun rankIsTheFirstChunkThatTouchesAnExpectedPage() {
        val ranking = listOf(10..11, 40..41, 61..62, 63..63)
        assertEquals(3, RagScoring.firstRelevantRank(ranking, setOf(62, 63)))
        assertEquals(1, RagScoring.firstRelevantRank(ranking, setOf(11)))
        assertNull(RagScoring.firstRelevantRank(ranking, setOf(100)))
    }

    @Test
    fun recallAndMrrAverageOverQuestions() {
        val score = RagScoring.score(listOf(1, 3, 9, null), ks = listOf(1, 3, 8))
        assertEquals(mapOf(1 to 0.25, 3 to 0.5, 8 to 0.5), score.recallAt)
        assertEquals((1.0 + 1.0 / 3 + 1.0 / 9) / 4, score.mrr, 1e-9)
    }

    @Test
    fun evaluationFileIsParsed() {
        val set = EvalSet.parse(
            """
            {"books": [{"title": "Crimen y castigo",
              "questions": [{"question": "¿Dónde vive Raskólnikov?", "pages": [5, 6]}]}]}
            """.trimIndent()
        )
        assertEquals(listOf(5, 6), set.books.single().questions.single().pages)
    }
}
