package dev.joseramos.aireader.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import dev.joseramos.aireader.text.TextLine
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/** Entrada del índice (marcadores) del PDF. [page] es en base 1; `null` si no apunta a una página. */
data class OutlineEntry(val title: String, val page: Int?, val depth: Int)

/** Texto bruto de una página y sus líneas con geometría. */
data class PageContent(val text: String, val lines: List<TextLine>)

/**
 * Acceso al texto de un PDF con PdfBox-Android: texto por página, metadatos e índice. Se abre con
 * memoria mixta (16 MB en RAM, el resto en disco) para no disparar el consumo con libros grandes.
 */
class PdfTextDocument private constructor(private val document: PDDocument) : Closeable {
    private val stripper = PDFTextStripper()
    private val collector = LineCollector()

    val pageCount: Int get() = document.numberOfPages
    val title: String? get() = document.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() }
    val author: String? get() = document.documentInformation?.author?.trim()?.takeIf { it.isNotEmpty() }

    /** Texto bruto de la página [page] (base 1). */
    fun pageText(page: Int, sortByPosition: Boolean = false): String {
        stripper.sortByPosition = sortByPosition
        stripper.startPage = page
        stripper.endPage = page
        return stripper.getText(document)
    }

    /** Texto bruto de la página [page] (base 1) y sus líneas, con posición, tamaño de letra y negrita. */
    fun pageContent(page: Int): PageContent {
        collector.reset()
        collector.startPage = page
        collector.endPage = page
        val text = collector.getText(document)
        collector.flush()
        return PageContent(text, collector.lines.toList())
    }

    /** Índice del PDF aplanado en orden de lectura, con su profundidad. */
    fun outline(): List<OutlineEntry> {
        val root = document.documentCatalog.documentOutline ?: return emptyList()
        val entries = mutableListOf<OutlineEntry>()
        fun walk(node: PDOutlineNode, depth: Int) {
            for (item in node.children()) {
                val page = runCatching { item.findDestinationPage(document)?.let { document.pages.indexOf(it) + 1 } }
                    .getOrNull()
                    ?.takeIf { it > 0 }
                val title = item.title?.trim().orEmpty()
                if (title.isNotEmpty()) entries += OutlineEntry(title, page, depth)
                walk(item, depth + 1)
            }
        }
        walk(root, 0)
        return entries
    }

    override fun close() = document.close()

    companion object {
        private const val MAIN_MEMORY_BYTES = 16L * 1024 * 1024

        fun open(context: Context, file: File): PdfTextDocument {
            PDFBoxResourceLoader.init(context.applicationContext)
            val memory = MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES).setTempDir(context.cacheDir)
            return PdfTextDocument(PDDocument.load(file, memory))
        }
    }
}

/**
 * Extractor que, además del texto, junta las letras de cada línea (PdfBox escribe una línea palabra
 * a palabra y luego un separador de línea o de párrafo) y calcula su geometría.
 */
private class LineCollector : PDFTextStripper() {
    val lines = mutableListOf<TextLine>()
    private val text = StringBuilder()
    private val positions = mutableListOf<TextPosition>()
    private var firstWordEnd = -1
    private val boldFonts = mutableMapOf<PDFont, Boolean>()

    fun reset() {
        lines.clear()
        clearLine()
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        super.writeString(text, textPositions)
        this.text.append(text)
        positions += textPositions
    }

    override fun writeWordSeparator() {
        super.writeWordSeparator()
        if (firstWordEnd < 0) firstWordEnd = positions.size
        text.append(' ')
    }

    override fun writeLineSeparator() {
        flush()
        super.writeLineSeparator()
    }

    override fun writeParagraphEnd() {
        flush()
        super.writeParagraphEnd()
    }

    override fun writePageEnd() {
        flush()
        super.writePageEnd()
    }

    /** Cierra la línea en curso (si la hay) y la añade a [lines]. */
    fun flush() {
        val visible = positions.filter { !it.unicode.isNullOrBlank() }
        val content = text.toString().trim()
        if (visible.isNotEmpty() && content.isNotEmpty()) {
            val wordVisible = firstWord().ifEmpty { visible }
            lines += TextLine(
                text = content,
                left = visible.minOf { it.xDirAdj },
                right = visible.maxOf { it.xDirAdj + it.widthDirAdj },
                baseline = visible.map { it.yDirAdj }.sorted()[visible.size / 2],
                size = dominantSize(visible),
                bold = visible.count { isBold(it.font) } * 2 > visible.size,
                firstWordWidth = wordVisible.maxOf { it.xDirAdj + it.widthDirAdj } - wordVisible.minOf { it.xDirAdj }
            )
        }
        clearLine()
    }

    /**
     * Letras de la primera palabra de la línea. PdfBox separa las palabras por la distancia entre
     * letras, pero si el PDF trae espacios de verdad la línea entera llega como una sola «palabra»:
     * por eso también se corta en el primer espacio.
     */
    private fun firstWord(): List<TextPosition> {
        val start = positions.indexOfFirst { !it.unicode.isNullOrBlank() }
        if (start < 0) return emptyList()
        val limit = if (firstWordEnd > start) firstWordEnd else positions.size
        val end = (start until limit).firstOrNull { positions[it].unicode.isNullOrBlank() } ?: limit
        return positions.subList(start, end)
    }

    private fun clearLine() {
        text.setLength(0)
        positions.clear()
        firstWordEnd = -1
    }

    private fun dominantSize(positions: List<TextPosition>): Float = positions
        .groupingBy { position ->
            val size = position.fontSizeInPt.takeIf { it > 0f } ?: position.heightDir
            (size * SIZE_STEP).roundToInt() / SIZE_STEP
        }
        .eachCount()
        .maxBy { it.value }
        .key

    private fun isBold(font: PDFont?): Boolean = font != null &&
        boldFonts.getOrPut(font) {
            val name = font.name.orEmpty().lowercase()
            val descriptor = font.fontDescriptor
            BOLD_NAMES.any { it in name } ||
                descriptor?.isForceBold == true ||
                (descriptor?.fontWeight ?: 0f) >= BOLD_WEIGHT
        }

    private companion object {
        const val SIZE_STEP = 2f
        const val BOLD_WEIGHT = 600f
        val BOLD_NAMES = listOf("bold", "black", "heavy", "semibold", "demi")
    }
}
