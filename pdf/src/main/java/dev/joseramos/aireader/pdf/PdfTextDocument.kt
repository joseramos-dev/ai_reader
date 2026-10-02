package dev.joseramos.aireader.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.Closeable
import java.io.File

/** Entrada del índice (marcadores) del PDF. [page] es en base 1; `null` si no apunta a una página. */
data class OutlineEntry(val title: String, val page: Int?, val depth: Int)

/**
 * Acceso al texto de un PDF con PdfBox-Android: texto por página, metadatos e índice. Se abre con
 * memoria mixta (16 MB en RAM, el resto en disco) para no disparar el consumo con libros grandes.
 */
class PdfTextDocument private constructor(private val document: PDDocument) : Closeable {
    private val stripper = PDFTextStripper()

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
