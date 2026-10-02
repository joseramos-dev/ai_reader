package dev.joseramos.aireader.core.data.book

import dev.joseramos.aireader.core.data.db.ChapterDao
import dev.joseramos.aireader.core.data.db.ChapterEntity
import dev.joseramos.aireader.core.data.db.PageTextDao
import dev.joseramos.aireader.core.data.db.PageTextEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Entrada del índice con sus páginas (base 1, ambas incluidas). [level] 0 es un capítulo; 1 y 2, sus
 * apartados y subapartados.
 */
data class Chapter(
    val id: Long,
    val number: Int,
    val title: String,
    val startPage: Int,
    val endPage: Int,
    val level: Int = 0
) {
    operator fun contains(page: Int) = page in startPage..endPage
}

/**
 * Texto limpio de una página, dividido en párrafos. [levels] da el nivel de cada párrafo (0 texto,
 * 1… títulos de mayor a menor); está vacío si el libro se procesó sin geometría.
 */
data class PageText(
    val page: Int,
    val paragraphs: List<String>,
    val isScanned: Boolean,
    val levels: List<Int> = emptyList()
) {
    fun levelOf(paragraph: Int): Int = levels.getOrElse(paragraph) { 0 }
}

/** Capítulos y texto extraído de un libro, tal como los usan el lector, la voz y la IA. */
@Singleton
class BookContentRepository @Inject constructor(
    private val chapterDao: ChapterDao,
    private val pageTextDao: PageTextDao
) {
    fun observeChapters(bookId: String): Flow<List<Chapter>> =
        chapterDao.observeByBook(bookId).map { list -> list.map { it.toChapter() } }

    suspend fun chapters(bookId: String): List<Chapter> = chapterDao.getByBook(bookId).map { it.toChapter() }

    /** Índice completo: capítulos con sus apartados, en orden de lectura. */
    fun observeContents(bookId: String): Flow<List<Chapter>> =
        chapterDao.observeContents(bookId).map { list -> list.map { it.toChapter() } }

    fun observePages(bookId: String): Flow<List<PageText>> =
        pageTextDao.observeByBook(bookId).map { list -> list.filter { it.cleanerVersion > 0 }.map { it.toPageText() } }

    suspend fun pages(bookId: String): List<PageText> =
        pageTextDao.getByBook(bookId).filter { it.cleanerVersion > 0 }.map { it.toPageText() }

    /** Hasta [limit] páginas limpias a partir de [fromPage] (base 1). */
    suspend fun pagesFrom(bookId: String, fromPage: Int, limit: Int): List<PageText> =
        pageTextDao.getFrom(bookId, fromPage, limit).filter { it.cleanerVersion > 0 }.map { it.toPageText() }

    /** Texto completo de un rango de páginas, con los párrafos separados por líneas en blanco. */
    suspend fun text(bookId: String, fromPage: Int, toPage: Int): String =
        pagesFrom(bookId, fromPage, toPage - fromPage + 1).joinToString("\n\n") { it.paragraphs.joinToString("\n\n") }
}

private fun ChapterEntity.toChapter() = Chapter(id, number, title, startPage, endPage, level)

private fun PageTextEntity.toPageText() = PageText(
    page = page,
    paragraphs = runCatching { Json.decodeFromString<List<String>>(paragraphsJson) }.getOrDefault(emptyList()),
    isScanned = isScanned,
    levels = runCatching { Json.decodeFromString<List<Int>>(levelsJson) }.getOrDefault(emptyList())
)
