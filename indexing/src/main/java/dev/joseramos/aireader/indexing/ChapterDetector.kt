package dev.joseramos.aireader.indexing

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.ChapterDao
import dev.joseramos.aireader.core.data.db.ChapterEntity
import dev.joseramos.aireader.core.data.db.ChapterSource
import dev.joseramos.aireader.core.data.db.PageTextDao
import dev.joseramos.aireader.pdf.PdfTextDocument
import dev.joseramos.aireader.text.ChapterHeuristics
import dev.joseramos.aireader.text.DetectedChapter
import java.io.File
import java.util.Optional
import javax.inject.Inject

/**
 * Detección de capítulos con IA, para cuando el PDF no trae índice y las heurísticas fallan.
 * La implementa el módulo `:ai:llm`; si no hay clave de API devuelve una lista vacía.
 */
interface LlmChapterDetection {
    /** [pageHeads]: número de página (base 1) y su primera línea con texto. */
    suspend fun detect(pageHeads: List<Pair<Int, String>>, pageCount: Int): List<DetectedChapter>
}

/** Capítulos en cascada: índice del PDF → encabezados → IA → bloques de páginas. */
class ChapterDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chapterDao: ChapterDao,
    private val pageTextDao: PageTextDao,
    private val llm: Optional<LlmChapterDetection>
) {
    suspend fun run(book: BookEntity) {
        if (chapterDao.getByBook(book.id).isNotEmpty()) return
        val (chapters, source) = detect(book)
        chapterDao.insertAll(
            chapters.sortedBy { it.startPage }.distinctBy { it.startPage }.let { sorted ->
                sorted.mapIndexed { i, chapter ->
                    val end = sorted.getOrNull(i + 1)?.startPage?.minus(1) ?: book.pageCount
                    ChapterEntity(
                        bookId = book.id,
                        number = i + 1,
                        title = chapter.title,
                        startPage = chapter.startPage,
                        endPage = maxOf(chapter.startPage, end),
                        source = source
                    )
                }
            }
        )
    }

    private suspend fun detect(book: BookEntity): Pair<List<DetectedChapter>, ChapterSource> {
        val outline = runCatching {
            PdfTextDocument.open(context, File(book.filePath)).use { it.outline() }
        }.getOrDefault(emptyList())
        val topLevel = outline.filter { it.page != null }.let { entries ->
            val minDepth = entries.minOfOrNull { it.depth } ?: 0
            entries.filter { it.depth == minDepth }
        }
        if (topLevel.size >= MIN_CHAPTERS) {
            return topLevel.map { DetectedChapter(it.title, it.page!!) } to ChapterSource.OUTLINE
        }

        val pages = pageTextDao.getByBook(book.id)
        val firstLines = pages.map { page -> page.rawText.lines().filter { it.isNotBlank() }.take(HEAD_LINES) }
        ChapterHeuristics.detect(firstLines).takeIf { it.isNotEmpty() }?.let { return it to ChapterSource.HEURISTIC }

        val heads = firstLines.mapIndexedNotNull { i, lines ->
            lines.firstOrNull()?.let {
                pages[i].page to
                    it.take(HEAD_CHARS)
            }
        }
        val fromLlm = llm.orElse(null)?.let {
            runCatching { it.detect(heads, book.pageCount) }.getOrDefault(emptyList())
        }
        if (!fromLlm.isNullOrEmpty() && fromLlm.size >= MIN_CHAPTERS) return fromLlm to ChapterSource.LLM

        return ChapterHeuristics.blocks(book.pageCount) to ChapterSource.BLOCKS
    }

    companion object {
        private const val MIN_CHAPTERS = 2
        private const val HEAD_LINES = 4
        private const val HEAD_CHARS = 120
    }
}
