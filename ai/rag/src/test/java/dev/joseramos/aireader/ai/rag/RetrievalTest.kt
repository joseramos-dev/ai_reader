package dev.joseramos.aireader.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalTest {
    @Test
    fun vectorIndexReturnsMostSimilarFirst() {
        val index = VectorIndex(
            ids = longArrayOf(10, 20, 30),
            vectors = floatArrayOf(1f, 0f, 0f, 1f, 0.7071f, 0.7071f),
            dimensions = 2
        )
        assertEquals(listOf(20L, 30L), index.search(floatArrayOf(0f, 1f), k = 2))
    }

    @Test
    fun rrfRewardsItemsRankedHighInBothLists() {
        val fused = reciprocalRankFusion(listOf(listOf(1L, 2L, 3L), listOf(2L, 4L, 5L)))
        assertEquals(2L, fused.first())
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), fused.toSet())
    }

    @Test
    fun ftsQueryDropsStopWordsAndOperators() {
        val query = FtsQuery.from("¿Qué dice el autor sobre la \"memoria\" episódica OR NEAR?")!!
        assertTrue("memoria*" in query)
        assertTrue("episódica*" in query)
        assertTrue("episodica*" in query)
        assertTrue("autor" !in query.split(" "))
        assertTrue("\"" !in query)
    }

    @Test
    fun ftsQueryIsNullWhenNothingMeaningful() {
        assertNull(FtsQuery.from("¿Y qué?"))
    }

    @Test
    fun routerDetectsGlobalQuestions() {
        assertEquals(QueryScope.GLOBAL, QueryRouter.route("¿De qué trata el libro?"))
        assertEquals(QueryScope.SPECIFIC, QueryRouter.route("¿Quién firmó el tratado de 1648?"))
    }

    @Test
    fun citationsOutsideSentFragmentsAreRemoved() {
        val fragments = listOf(Fragment(1, "…", 42, 43, null), Fragment(2, "…", 57, 57, null))
        val answer = CitationParser.validate(
            "El tratado se firmó en 1648 [p. 42]. Fue largo [p. 99]. Y difícil [p. 57].",
            fragments
        )

        assertEquals("El tratado se firmó en 1648 [p. 42]. Fue largo. Y difícil [p. 57].", answer.text)
        assertEquals(listOf(42, 57), answer.pages)
    }
}
