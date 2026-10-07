package dev.joseramos.aireader.pdf

import androidx.compose.ui.graphics.ImageBitmap
import dev.joseramos.aireader.text.TextLine
import java.io.Closeable
import java.io.File

/** Entrada del índice (marcadores) del PDF. [page] es en base 1; `null` si no apunta a una página. */
data class OutlineEntry(val title: String, val page: Int?, val depth: Int)

/** Texto bruto de una página y sus líneas con geometría. */
data class PageContent(val text: String, val lines: List<TextLine>)

/** Tamaño de una página en puntos PDF (1/72 de pulgada). */
data class PageSize(val width: Int, val height: Int)

/** Acceso al texto de un PDF: texto por página, metadatos e índice. */
interface PdfTextDocument : Closeable {
    val pageCount: Int
    val title: String?
    val author: String?

    /** Texto bruto de la página [page] (base 1). */
    fun pageText(page: Int, sortByPosition: Boolean = false): String

    /** Texto bruto de la página [page] (base 1) y sus líneas, con posición, tamaño de letra y negrita. */
    fun pageContent(page: Int): PageContent

    /** Índice del PDF aplanado en orden de lectura, con su profundidad. */
    fun outline(): List<OutlineEntry>
}

/** Abre PDF para leer su texto. Cada plataforma usa su biblioteca (PdfBox-Android, Apache PDFBox). */
fun interface PdfTextDocumentFactory {
    /** Lanza `IOException` si no es un PDF válido o está protegido con contraseña. */
    fun open(file: File): PdfTextDocument
}

/** Renderiza páginas de un PDF. Pensado para un único lector: abre una página a la vez. */
interface PdfPageRenderer : Closeable {
    val pageCount: Int

    /**
     * Tamaño de cada página en puntos PDF, para reservar el hueco antes de renderizar. Medirlas puede abrir todas
     * las páginas, así que se pide solo cuando hace falta y fuera del hilo principal.
     */
    val pageSizes: List<PageSize>

    /** Página [index] (base 0) renderizada a [widthPx] de ancho, manteniendo la proporción. */
    suspend fun render(index: Int, widthPx: Int): ImageBitmap

    /** Cierra esperando a que termine el render en curso (cerrar a mitad de un render falla). */
    suspend fun release()
}

/** Abre PDF para mostrarlos y genera sus portadas. Cada plataforma usa su motor de render. */
interface PdfRendererFactory {
    /** [sizesCache]: dónde guardar los tamaños de página medidos, si se quiere. */
    fun open(file: File, sizesCache: File? = null): PdfPageRenderer

    /**
     * Abre el PDF solo para contar páginas y guarda la primera como PNG de [widthPx] de ancho en [cover].
     * Lanza `IOException` si no es un PDF válido y `SecurityException` si está protegido con contraseña.
     */
    fun writeCover(pdf: File, cover: File, widthPx: Int): Int
}
