package dev.joseramos.aireader.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {
    private fun paragraph(page: Int, words: Int, tag: String) =
        PageParagraph(page, (1..words).joinToString(" ") { "$tag$it" } + ". Fin de $tag.")

    @Test
    fun groupsParagraphsUpToTargetAndKeepsPages() {
        val paragraphs =
            listOf(paragraph(1, 100, "a"), paragraph(1, 100, "b"), paragraph(2, 100, "c"), paragraph(3, 100, "d"))

        val chunks = Chunker.chunk(paragraphs, targetTokens = 300)

        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.tokenCount <= 300 + Chunker.estimateTokens("Fin de x.") * 2 })
        assertEquals(1, chunks.first().startPage)
        assertEquals(3, chunks.last().endPage)
    }

    @Test
    fun nextChunkStartsWithLastSentenceOfPrevious() {
        val chunks = Chunker.chunk(
            listOf(paragraph(1, 150, "a"), paragraph(2, 150, "b"), paragraph(3, 150, "c")),
            targetTokens = 250
        )

        assertTrue(chunks.size >= 2)
        assertTrue(chunks[1].text.startsWith("Fin de "))
    }

    @Test
    fun longParagraphIsSplitBySentences() {
        val long = PageParagraph(5, (1..40).joinToString(" ") { "Frase número $it con algo de texto." })

        val chunks = Chunker.chunk(listOf(long), targetTokens = 60)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.startPage == 5 && it.endPage == 5 })
    }

    @Test
    fun emptyInputGivesNoChunks() {
        assertEquals(emptyList<TextChunk>(), Chunker.chunk(emptyList()))
    }
}
