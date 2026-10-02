package dev.joseramos.aireader.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Proveedor de solo lectura con un PDF largo generado al vuelo (texto en todas las páginas), para
 * medir el lector sin depender de ficheros del móvil.
 */
class TestPdfProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(pdf(requireNotNull(context)), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
        addRow(arrayOf<Any>(FILE_NAME, pdf(requireNotNull(context)).length()))
    }

    override fun getType(uri: Uri): String = "application/pdf"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        0

    companion object {
        const val PAGES = 320
        private const val FILE_NAME = "Libro largo de prueba.pdf"
        private const val WIDTH = 595
        private const val HEIGHT = 842
        private const val MARGIN = 56f
        private const val LINE = 18f
        private const val TEXT_SIZE = 12f
        private const val LINES_PER_PAGE = 38
        private const val TITLE_SIZE = 20f
        private const val PAGES_PER_CHAPTER = 10

        val uri: Uri = Uri.parse("content://dev.joseramos.aireader.benchmark.pdf/long.pdf")

        @Synchronized
        fun pdf(context: Context): File {
            val file = File(context.filesDir, "long.pdf")
            if (file.exists()) return file
            val document = PdfDocument()
            val paint = Paint().apply { textSize = TEXT_SIZE }
            val title = Paint().apply {
                textSize = TITLE_SIZE
                isFakeBoldText = true
            }
            repeat(PAGES) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, index + 1).create())
                val canvas = page.canvas
                canvas.drawText("Capítulo ${index / PAGES_PER_CHAPTER + 1}", MARGIN, MARGIN, title)
                repeat(LINES_PER_PAGE) { line ->
                    canvas.drawText(
                        "Línea ${line + 1} de la página ${index + 1}: texto de relleno para medir el desplazamiento.",
                        MARGIN,
                        MARGIN + LINE * (line + 2),
                        paint
                    )
                }
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
            document.close()
            return file
        }
    }
}
