package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.core.data.db.ChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun onlyFollowUpQuestionsNeedTheConversation() {
        assertTrue(QueryRouter.needsContext("¿Y después?"))
        assertTrue(QueryRouter.needsContext("¿Por qué lo hizo él?"))
        assertTrue(QueryRouter.needsContext("¿Cómo se llama su hermana?"))
        assertTrue(QueryRouter.needsContext("Explícalo mejor"))
        assertFalse(QueryRouter.needsContext("¿Quién mató a la vieja usurera?"))
        assertFalse(QueryRouter.needsContext("¿Qué le pasa a Raskolnikov en el capítulo 3?"))
    }

    private fun chunk(ordinal: Int, text: String, page: Int, chapterId: Long? = 1) = ChunkEntity(
        id = ordinal.toLong(),
        bookId = "b",
        chapterId = chapterId,
        ordinal = ordinal,
        text = text,
        startPage = page,
        endPage = page,
        charStart = 0,
        charEnd = text.length,
        tokenCount = 1
    )

    @Test
    fun consecutiveChunksAreMergedWithoutTheRepeatedPhrase() {
        val merged = ChunkMerger.merge(
            listOf(
                chunk(4, "Lejos de allí. Nadie lo supo.", 9),
                chunk(1, "Era de noche. Llovía sin parar.", 2),
                chunk(2, "Llovía sin parar.\n\nRaskolnikov salió.", 3)
            )
        )
        assertEquals(2, merged.size)
        assertEquals("Era de noche. Llovía sin parar.\n\nRaskolnikov salió.", merged[0].text)
        assertEquals(2, merged[0].startPage)
        assertEquals(3, merged[0].endPage)
        assertEquals("Lejos de allí. Nadie lo supo.", merged[1].text)
    }

    @Test
    fun chunksOfDifferentChaptersAreNotMerged() {
        val merged = ChunkMerger.merge(listOf(chunk(1, "Fin del uno.", 5, 1), chunk(2, "Empieza el dos.", 6, 2)))
        assertEquals(2, merged.size)
    }
}
