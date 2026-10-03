package dev.joseramos.aireader.indexing

import dev.joseramos.aireader.core.data.db.BookDao
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.ChapterDao
import dev.joseramos.aireader.core.data.db.DocumentType
import dev.joseramos.aireader.core.data.db.DocumentTypeSource
import dev.joseramos.aireader.core.data.db.PageTextDao
import java.util.Optional
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Confirmación con IA del tipo de documento cuando la heurística duda. La implementa `:ai:llm`;
 * devuelve `null` si no hay clave de API o la respuesta no es válida.
 */
interface LlmDocumentClassification {
    suspend fun classify(title: String, chapterTitles: List<String>, sample: String): DocumentType?
}

/**
 * Paso de la indexación que decide el tipo de documento: heurística local y, si no es
 * concluyente, confirmación con IA. No toca los libros cuyo tipo eligió el usuario.
 */
class DocumentTypeDetector @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val pageTextDao: PageTextDao,
    private val llm: Optional<LlmDocumentClassification>
) {
    /** [retryWithAi]: ahora hay clave de API y antes no; se recalcula por si la heurística dudaba. */
    suspend fun run(book: BookEntity, retryWithAi: Boolean = false) {
        if (book.documentTypeSource == DocumentTypeSource.USER) return
        if (book.documentType != null && !retryWithAi) return
        val pages = pageTextDao.getByBook(book.id)
        val sampled = pages.take(LEADING_PAGES) +
            pages.drop(LEADING_PAGES).let { rest -> rest.filterIndexed { i, _ -> i % sampleStep(rest.size) == 0 } }
        val paragraphs = sampled.map { page ->
            runCatching { Json.decodeFromString<List<String>>(page.paragraphsJson) }.getOrDefault(emptyList())
        }
        val chapterTitles = chapterDao.getByBook(book.id).map { it.title }

        val heuristic = DocumentClassifier.classify(paragraphs, book.pageCount, chapterTitles)
        val type = if (heuristic.conclusive) {
            heuristic.type
        } else {
            val sample = paragraphs.flatten().joinToString(
                "\n\n"
            ).split(Regex("\\s+")).take(SAMPLE_WORDS).joinToString(" ")
            llm.orElse(null)?.let { runCatching { it.classify(book.title, chapterTitles, sample) }.getOrNull() }
                ?: heuristic.type
        }
        bookDao.updateDocumentType(book.id, type, DocumentTypeSource.AUTO)
    }

    private fun sampleStep(remaining: Int) = (remaining / EXTRA_PAGES).coerceAtLeast(1)

    private companion object {
        const val LEADING_PAGES = 30
        const val EXTRA_PAGES = 20
        const val SAMPLE_WORDS = 3_000
    }
}
