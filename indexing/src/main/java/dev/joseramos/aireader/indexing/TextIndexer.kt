package dev.joseramos.aireader.indexing

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.joseramos.aireader.core.data.db.BookDao
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.PageLayoutDao
import dev.joseramos.aireader.core.data.db.PageLayoutEntity
import dev.joseramos.aireader.core.data.db.PageTextDao
import dev.joseramos.aireader.core.data.db.PageTextEntity
import dev.joseramos.aireader.pdf.PdfTextDocument
import dev.joseramos.aireader.text.LayoutAnalyzer
import dev.joseramos.aireader.text.TextBlock
import dev.joseramos.aireader.text.TextCleaner
import dev.joseramos.aireader.text.TextLine
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

/**
 * Etapa de texto de la indexación, en dos pasadas:
 * 1. Extrae el texto bruto y las líneas con su geometría (posición, tamaño de letra, negrita) de
 *    cada página y los guarda en cuanto los tiene (si se interrumpe, continúa por la primera página
 *    que falte).
 * 2. Detecta cabeceras, pies y la tipografía de todo el libro, y reconstruye los párrafos y títulos
 *    de cada página con [LayoutAnalyzer].
 *
 * Cuando sube [TextCleaner.VERSION], los libros ya procesados se vuelven a limpiar sin dejar de
 * poderse leer: el texto anterior se mantiene hasta que se sustituye.
 */
class TextIndexer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val pageTextDao: PageTextDao,
    private val pageLayoutDao: PageLayoutDao
) {
    /** El texto de todas las páginas está extraído y limpio con la versión actual del limpiador. */
    suspend fun isComplete(book: BookEntity): Boolean =
        pageTextDao.countUpToDate(book.id, TextCleaner.VERSION) == book.pageCount

    /** Hay texto limpio de una versión anterior: el libro ya se puede leer mientras se actualiza. */
    suspend fun hasOutdatedText(book: BookEntity): Boolean = book.id in pageTextDao.bookIdsWithOutdatedText(
        TextCleaner.VERSION
    )

    /** [onProgress] recibe 0..1. Devuelve `true` si ha tenido que (re)hacer el texto. */
    suspend fun run(book: BookEntity, onProgress: suspend (Float) -> Unit): Boolean {
        if (isComplete(book)) return false
        extract(book, onProgress)
        clean(book, onProgress)
        return true
    }

    private suspend fun extract(book: BookEntity, onProgress: suspend (Float) -> Unit) {
        val pages = pageTextDao.getByBook(book.id)
        val texts = pages.map { it.page }.toSet()
        // Al subir la versión del limpiador también se vuelve a extraer la geometría, por si cambió. Las
        // páginas nuevas (versión 0) con geometría ya están extraídas: así se reanuda donde se quedó.
        val outdated = pages.filter { it.cleanerVersion in 1 until TextCleaner.VERSION }.map { it.page }.toSet()
        val layouts = pageLayoutDao.pages(book.id).toSet()
        PdfTextDocument.open(context, File(book.filePath)).use { pdf ->
            updateMetadata(book, pdf)
            for (page in 1..book.pageCount) {
                coroutineContext.ensureActive()
                if (page in outdated || page !in layouts) {
                    val content = runCatching { pdf.pageContent(page) }
                        .onFailure { Log.w(TAG, "Sin geometría en la página $page de ${book.id}", it) }
                        .getOrNull()
                    val raw = content?.text ?: runCatching { pdf.pageText(page) }.getOrDefault("")
                    val lines = content?.lines.orEmpty().map {
                        it.copy(text = TextCleaner.normalizeCharacters(it.text))
                    }
                    pageLayoutDao.upsert(PageLayoutEntity(book.id, page, json.encodeToString(lines)))
                    // Si ya había texto limpio (de una versión anterior), se conserva para seguir leyendo.
                    if (page !in texts) {
                        pageTextDao.upsert(
                            PageTextEntity(book.id, page, raw, "", "[]", raw.isBlank(), cleanerVersion = 0)
                        )
                    }
                }
                onProgress(page.toFloat() / book.pageCount * EXTRACTION_SHARE)
            }
        }
    }

    private suspend fun clean(book: BookEntity, onProgress: suspend (Float) -> Unit) {
        val raw = pageTextDao.getByBook(book.id)
        val layouts = pageLayoutDao.getByBook(book.id).associate { layout ->
            layout.page to
                runCatching { json.decodeFromString<List<TextLine>>(layout.linesJson) }.getOrDefault(emptyList())
        }
        val linesOf = raw.map { layouts[it.page].orEmpty() }
        val layout = TextCleaner.detectLayout(
            raw.mapIndexed { i, page -> linesOf[i].map { it.text }.ifEmpty { page.rawText.lines() } }
        )
        val typography = LayoutAnalyzer.typography(linesOf)
        var blocks = raw.mapIndexed { i, page ->
            val lines = linesOf[i]
            if (lines.isEmpty()) {
                TextCleaner.pageToParagraphs(page.rawText, layout).map { TextBlock(it) }
            } else {
                LayoutAnalyzer.blocks(TextCleaner.stripMargins(lines, layout), typography)
            }
        }
        blocks = mergeHyphensAcrossPages(blocks)
        raw.forEachIndexed { index, page ->
            coroutineContext.ensureActive()
            val clean = blocks[index]
            val paragraphs = clean.map { it.text }
            pageTextDao.upsert(
                page.copy(
                    cleanText = paragraphs.joinToString("\n\n"),
                    paragraphsJson = json.encodeToString(paragraphs),
                    levelsJson = json.encodeToString(clean.map { it.level }),
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

    private fun mergeHyphensAcrossPages(pages: List<List<TextBlock>>): List<List<TextBlock>> {
        val result = pages.toMutableList()
        for (i in 0 until result.lastIndex) {
            val (left, right) = TextCleaner.mergeBlocksAcrossPages(result[i], result[i + 1])
            result[i] = left
            result[i + 1] = right
        }
        return result
    }

    companion object {
        private const val TAG = "TextIndexer"
        private const val EXTRACTION_SHARE = 0.85f
        private const val SCANNED_THRESHOLD = 20
        private val json = Json { ignoreUnknownKeys = true }
    }
}
