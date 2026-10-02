package dev.joseramos.aireader.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutAnalyzerTest {
    private val typography =
        BookTypography(bodySize = 10f, headingSizes = listOf(20f, 16f), textWidth = 400f, lineGap = 12f)

    /** Línea de prueba: cada carácter mide 5 puntos; [full] la estira hasta el margen derecho (justificada). */
    private fun line(
        text: String,
        y: Float,
        left: Float = LEFT,
        size: Float = 10f,
        bold: Boolean = false,
        full: Boolean = false
    ) = TextLine(
        text = text,
        left = left,
        right = if (full) LEFT + WIDTH else left + text.length * CHAR,
        baseline = y,
        size = size,
        bold = bold,
        firstWordWidth = text.substringBefore(' ').length * CHAR
    )

    @Test
    fun joinsJustifiedLinesAndStartsParagraphsOnIndent() {
        val blocks = LayoutAnalyzer.blocks(
            listOf(
                line("Era una mañana fría y el viento soplaba", 100f, full = true),
                line("con fuerza sobre los tejados de la ciudad", 112f, full = true),
                line("vieja.", 124f),
                line("Nadie salió a la calle aquel día.", 136f, left = LEFT + 15f)
            ),
            typography
        )
        assertEquals(
            listOf(
                "Era una mañana fría y el viento soplaba con fuerza sobre los tejados de la ciudad vieja.",
                "Nadie salió a la calle aquel día."
            ),
            blocks.map { it.text }
        )
    }

    @Test
    fun keepsIntentionalLineBreaks() {
        val blocks = LayoutAnalyzer.blocks(
            listOf(
                line("En un lugar de la Mancha,", 100f),
                line("de cuyo nombre no quiero acordarme,", 112f),
                line("no ha mucho tiempo que vivía", 124f),
                line("un hidalgo de los de lanza en astillero", 136f),
                line("adarga antigua y galgo corredor", 148f),
                line("y rocín flaco.", 160f)
            ),
            typography
        )
        assertEquals(1, blocks.size)
        assertEquals(6, blocks.single().text.lines().size)
        assertEquals("y rocín flaco.", blocks.single().text.lines().last())
    }

    @Test
    fun startsParagraphOnShortLineEndingSentenceOrExtraSpace() {
        val blocks = LayoutAnalyzer.blocks(
            listOf(
                line("Primera idea completa.", 100f),
                line("Segunda idea que empieza aquí y sigue", 112f, full = true),
                line("en otra línea", 124f),
                line("Tercera idea tras un hueco.", 150f)
            ),
            typography
        )
        assertEquals(
            listOf(
                "Primera idea completa.",
                "Segunda idea que empieza aquí y sigue en otra línea",
                "Tercera idea tras un hueco."
            ),
            blocks.map { it.text }
        )
    }

    @Test
    fun splitsListItemsAndKeepsTheirIndentedContinuation() {
        val blocks = LayoutAnalyzer.blocks(
            listOf(
                line("Los elementos son:", 100f),
                line("• El primero, que ocupa más de una línea", 112f, full = true),
                line("y continúa sangrado.", 124f, left = LEFT + 10f),
                line("• El segundo.", 136f),
                line("1. Un paso numerado.", 148f)
            ),
            typography
        )
        assertEquals(
            listOf(
                "Los elementos son:",
                "• El primero, que ocupa más de una línea y continúa sangrado.",
                "• El segundo.",
                "1. Un paso numerado."
            ),
            blocks.map { it.text }
        )
    }

    @Test
    fun givesHeadingsTheirLevelBySizeAndBold() {
        val blocks = LayoutAnalyzer.blocks(
            listOf(
                line("Tema 3", 60f, size = 20f),
                line("La célula", 84f, size = 20f),
                line("1.1 Estructura", 120f, size = 16f),
                line("La célula es la unidad básica de la vida.", 140f),
                line("Membrana plasmática", 160f, bold = true),
                line("Rodea la célula.", 172f)
            ),
            typography
        )
        assertEquals(
            listOf(
                TextBlock("Tema 3\nLa célula", 1),
                TextBlock("1.1 Estructura", 2),
                TextBlock("La célula es la unidad básica de la vida.", 0),
                TextBlock("Membrana plasmática", 3),
                TextBlock("Rodea la célula.", 0)
            ),
            blocks
        )
    }

    @Test
    fun mergesDropCapsAndHyphenatedWords() {
        val blocks = LayoutAnalyzer.blocks(
            listOf(
                line("E", 100f, size = 30f),
                line("ra una vez un acuerdo de alcance inter-", 100f + 4f, full = true),
                line("nacional.", 116f)
            ),
            typography
        )
        assertEquals(listOf("Era una vez un acuerdo de alcance internacional."), blocks.map { it.text })
    }

    @Test
    fun typographyFindsBodySizeAndHeadingLevels() {
        val body = (0 until 30).map {
            line("Texto normal del libro con bastantes letras", 100f + it * 12f, full = true)
        }
        val pages = listOf(
            listOf(line("Título del libro", 60f, size = 28f)),
            listOf(line("Capítulo 1", 60f, size = 18f)) + body,
            listOf(line("Capítulo 2", 60f, size = 18.2f), line("Apartado", 80f, size = 14f)) + body
        )
        val result = LayoutAnalyzer.typography(pages)
        assertEquals(10f, result.bodySize)
        assertEquals(1, result.levelOf(28f))
        assertEquals(2, result.levelOf(18f))
        assertEquals(2, result.levelOf(18.2f))
        assertEquals(3, result.levelOf(14f))
        assertEquals(0, result.levelOf(10.5f))
        assertEquals(12f, result.lineGap)
        assertEquals(WIDTH, result.textWidth)
    }

    @Test
    fun listMarkersAreRecognised() {
        assertTrue(LayoutAnalyzer.isListItem("• uno"))
        assertTrue(LayoutAnalyzer.isListItem("2) dos"))
        assertTrue(LayoutAnalyzer.isListItem("a) tres"))
        assertTrue(!LayoutAnalyzer.isListItem("J. R. R. Tolkien"))
        assertTrue(!LayoutAnalyzer.isListItem("1990 fue un año"))
    }

    private companion object {
        const val LEFT = 50f
        const val WIDTH = 400f
        const val CHAR = 5f
    }
}

class HeadingOutlineTest {
    @Test
    fun buildsTopicsWithSubtopicsAndIgnoresTheCoverTitle() {
        val headings = listOf(
            PageHeading(1, 1, "Biología general"),
            PageHeading(2, 2, "www.editorial.com"),
            PageHeading(3, 2, "Tema 1"),
            PageHeading(3, 3, "La célula"),
            PageHeading(4, 3, "1.1 Estructura"),
            PageHeading(5, 4, "Membrana"),
            PageHeading(9, 2, "Tema 2\nLos tejidos"),
            PageHeading(10, 3, "2.1 Epitelial:")
        )
        val outline = HeadingOutline.build(headings, pageCount = 40)
        assertEquals(
            listOf(
                DetectedChapter("Tema 1. La célula", 3, 0),
                DetectedChapter("1.1 Estructura", 4, 1),
                DetectedChapter("Membrana", 5, 2),
                DetectedChapter("Tema 2. Los tejidos", 9, 0),
                DetectedChapter("2.1 Epitelial", 10, 1)
            ),
            outline
        )
    }

    @Test
    fun keepsWhatComesBeforeTheFirstChapterAtTheTop() {
        val headings = listOf(
            PageHeading(2, 2, "Introducción"),
            PageHeading(5, 1, "Capítulo 1"),
            PageHeading(6, 2, "Primeros pasos"),
            PageHeading(20, 1, "Capítulo 2")
        )
        val outline = HeadingOutline.build(headings, pageCount = 40)
        assertEquals(listOf(0, 0, 1, 0), outline.map { it.level })
        assertEquals("Introducción", outline.first().title)
    }

    @Test
    fun needsChaptersOnAtLeastTwoPages() {
        assertTrue(HeadingOutline.build(listOf(PageHeading(1, 1, "Título"), PageHeading(1, 1, "Autor")), 10).isEmpty())
    }
}
