package dev.joseramos.aireader.pdf

import dev.joseramos.aireader.core.common.AppDirs
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Pruebas del PDF de escritorio con un PDF de 9 páginas (3 capítulos de 3 páginas). */
class PdfBoxTest {
    private lateinit var dir: File
    private lateinit var pdf: File
    private lateinit var dirs: AppDirs

    @Before
    fun setUp() {
        dir = createTempDirectory("pdfbox-test").toFile()
        pdf = File(dir, "prueba.pdf")
        checkNotNull(javaClass.getResourceAsStream("/prueba.pdf")).use { input ->
            pdf.outputStream().use { input.copyTo(it) }
        }
        dirs = AppDirs(files = File(dir, "files"), cache = File(dir, "cache").apply { mkdirs() })
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun extractsTextMetadataAndLineGeometry() {
        PdfBoxTextDocumentFactory(dirs).open(pdf).use { document ->
            assertEquals(9, document.pageCount)
            assertTrue(document.pageText(1).contains("Capítulo 1"))
            assertTrue(document.pageText(4).contains("Capítulo 2"))

            val lines = document.pageContent(1).lines
            val title = lines.first { it.text.contains("Capítulo 1") }
            val body = lines.last()
            // El título es más grande que el cuerpo y está por encima.
            assertTrue(title.size > body.size)
            assertTrue(title.baseline < body.baseline)
            assertTrue(body.right > body.left)
        }
    }

    @Test
    fun measuresPagesAndRendersThemKeepingTheAspect() = runBlocking {
        PdfBoxRendererFactory(dirs).open(pdf).use { renderer ->
            assertEquals(9, renderer.pageCount)
            val size = renderer.pageSizes.first()
            assertEquals(PageSize(595, 842), size)

            val page = renderer.render(0, 400)
            assertEquals(400, page.width)
            assertEquals((400f * size.height / size.width).toInt(), page.height, 2)
            // Segundo render igual: sale de la caché.
            assertTrue(renderer.render(0, 400) === page)
        }
    }

    @Test
    fun writesTheCoverAsPng() {
        val cover = File(dir, "covers/a.png")
        val pages = PdfBoxRendererFactory(dirs).writeCover(pdf, cover, 300)
        assertEquals(9, pages)
        assertTrue(cover.length() > 0)
        // Cabecera de un PNG.
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), cover.readBytes().take(4).map { it.toInt() and 0xFF })
    }

    private fun assertEquals(expected: Int, actual: Int, delta: Int) {
        assertTrue("$actual no está a $delta de $expected", kotlin.math.abs(expected - actual) <= delta)
    }
}
