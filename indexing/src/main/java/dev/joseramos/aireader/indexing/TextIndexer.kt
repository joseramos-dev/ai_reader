package dev.joseramos.aireader.indexing

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.joseramos.aireader.core.data.db.BookDao
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.PageTextDao
import dev.joseramos.aireader.core.data.db.PageTextEntity
import dev.joseramos.aireader.pdf.PdfTextDocument
import dev.joseramos.aireader.text.TextCleaner
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

/**
 * Etapa de texto de la indexación, en dos pasadas:
 * 1. Extrae el texto bruto de cada página y lo guarda en cuanto lo tiene (si se interrumpe,
 *    continúa por la primera página que falte).
 * 2. Detecta cabeceras y pies sobre todo el libro, limpia cada página y guarda sus párrafos.
 */
class TextIndexer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val pageTextDao: PageTextDao
) {
    /** El texto de todas las páginas está extraído y limpio con la versión actual del limpiador. */
    suspend fun isComplete(book: BookEntity): Boolean =
        pageTextDao.countUpToDate(book.id, TextCleaner.VERSION) == book.pageCount

    /** [onProgress] recibe 0..1. */
    suspend fun run(book: BookEntity, onProgress: suspend (Float) -> Unit) {
        if (isComplete(book)) return

        PdfTextDocument.open(context, File(book.filePath)).use { pdf ->
            updateMetadata(book, pdf)
            val existing = pageTextDao.getByBook(book.id).associateBy { it.page }
            for (page in 1..book.pageCount) {
                coroutineContext.ensureActive()
                if (page !in existing) {
                    val raw = runCatching { pdf.pageText(page) }.getOrDefault("")
                    pageTextDao.upsert(PageTextEntity(book.id, page, raw, "", "[]", raw.isBlank(), cleanerVersion = 0))
                }
                onProgress(page.toFloat() / book.pageCount * EXTRACTION_SHARE)
            }
        }

        val raw = pageTextDao.getByBook(book.id)
        val layout = TextCleaner.detectLayout(raw.map { it.rawText.lines() })
        var paragraphs = raw.map { TextCleaner.pageToParagraphs(it.rawText, layout) }
        paragraphs = mergeHyphensAcrossPages(paragraphs)
        raw.forEachIndexed { index, page ->
            coroutineContext.ensureActive()
            val clean = paragraphs[index]
            pageTextDao.upsert(
                page.copy(
                    cleanText = clean.joinToString("\n\n"),
                    paragraphsJson = Json.encodeToString(clean),
                    isScanned = page.rawText.trim().length < SCANNED_THRESHOLD,
                    cleanerVersion = TextCleaner.VERSION
                )
            )
            onProgress(EXTRACTION_SHARE + (index + 1f) / raw.size * (1 - EXTRACTION_SHARE))
        }
    }

    private suspend fun updateMetadata(book: BookEntity, pdf: PdfTextDocument) {
        val title = pdf.title
        // Solo se sustituye el título derivado del nombre de fichero, no uno ya bueno.
        if (title != null && book.title == book.fileName.substringBeforeLast('.')) {
            bookDao.updateMetadata(book.id, title, pdf.author ?: book.author)
        }
    }

    private fun mergeHyphensAcrossPages(pages: List<List<String>>): List<List<String>> {
        val result = pages.toMutableList()
        for (i in 0 until result.lastIndex) {
            val (left, right) = TextCleaner.mergeAcrossPages(result[i], result[i + 1])
            result[i] = left
            result[i + 1] = right
        }
        return result
    }

    companion object {
        private const val EXTRACTION_SHARE = 0.85f
        private const val SCANNED_THRESHOLD = 20
    }
}
