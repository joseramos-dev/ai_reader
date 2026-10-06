package dev.joseramos.aireader.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassageLocatorTest {
    private val pages = listOf(
        1 to listOf("Capítulo 1", "Era una mañana fría. Raskólnikov salió de su cuartucho sin hacer ruido."),
        2 to listOf(
            "La vieja vivía en el cuarto piso. Él subió despacio, contando los escalones.",
            "—¿Quién es? —preguntó una voz desde dentro.",
            "Nadie respondió durante un largo rato."
        )
    )

    @Test
    fun chunkMadeOfParagraphsIsFoundWhole() {
        // Como lo genera el Chunker: párrafos enteros unidos por una línea en blanco.
        val passage = pages[1].second.take(2).joinToString("\n\n")

        val spans = PassageLocator.inParagraphs(passage, pages)

        assertEquals(
            listOf(
                ParagraphSpan(2, 0, 0, pages[1].second[0].length),
                ParagraphSpan(2, 1, 0, pages[1].second[1].length)
            ),
            spans
        )
    }

    @Test
    fun overlapSentenceMarksOnlyTheEndOfItsParagraph() {
        // El fragmento empieza con la última frase del anterior (solapamiento del Chunker).
        val passage = "Raskólnikov salió de su cuartucho sin hacer ruido.\n\n" + pages[1].second[0]

        val spans = PassageLocator.inParagraphs(passage, pages)

        val first = pages[0].second[1]
        assertEquals(ParagraphSpan(1, 1, first.indexOf("Raskólnikov"), first.length), spans.first())
        assertEquals(ParagraphSpan(2, 0, 0, pages[1].second[0].length), spans[1])
        assertEquals(2, spans.size)
    }

    @Test
    fun ignoresSpacesLineBreaksAndHyphenation() {
        val passage = "La vieja vivía en el cuarto\npiso. Él su-\nbió despacio, contando los escalones."

        val spans = PassageLocator.inParagraphs(passage, pages)

        assertEquals(listOf(ParagraphSpan(2, 0, 0, pages[1].second[0].length)), spans)
    }

    @Test
    fun findsPassageInPdfLinesWithHeaderInBetween() {
        val lines = listOf(
            1 to listOf(line("Crimen y castigo"), line("Era una mañana fría. Raskólnikov"), line("salió de su cuar-")),
            2 to listOf(line("Crimen y castigo"), line("tucho sin hacer ruido."), line("La vieja vivía"))
        )

        val spans = PassageLocator.inLines(
            "Era una mañana fría. Raskólnikov salió de su cuartucho sin hacer ruido.",
            lines
        )

        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), spans.map { it.page to it.line })
        assertTrue(spans.all { it.from == 0f && it.to == 1f })
    }

    @Test
    fun partialLineGivesFractionOfIt() {
        val lines = listOf(1 to listOf(line("Fin del capítulo. Empieza otro")))

        val span = PassageLocator.inLines("Empieza otro", lines).single()

        assertEquals("Fin del capítulo. ".length / 30f, span.from, 0.001f)
        assertEquals(1f, span.to, 0.001f)
    }

    @Test
    fun missingTextGivesNothing() {
        assertEquals(emptyList<ParagraphSpan>(), PassageLocator.inParagraphs("Algo que no está en el libro.", pages))
        assertEquals(emptyList<ParagraphSpan>(), PassageLocator.inParagraphs("Algo", emptyList()))
    }

    private fun line(text: String) = TextLine(text, left = 0f, right = 100f, baseline = 0f, size = 10f)
}
