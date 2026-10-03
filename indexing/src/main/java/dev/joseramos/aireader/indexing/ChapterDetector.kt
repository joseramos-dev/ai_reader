package dev.joseramos.aireader.indexing

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.joseramos.aireader.core.data.db.BookEntity
import dev.joseramos.aireader.core.data.db.ChapterDao
import dev.joseramos.aireader.core.data.db.ChapterEntity
import dev.joseramos.aireader.core.data.db.ChapterSource
import dev.joseramos.aireader.core.data.db.PageTextDao
import dev.joseramos.aireader.core.data.db.PageTextEntity
import dev.joseramos.aireader.pdf.OutlineEntry
import dev.joseramos.aireader.pdf.PdfTextDocument
import dev.joseramos.aireader.text.ChapterHeuristics
import dev.joseramos.aireader.text.DetectedChapter
import dev.joseramos.aireader.text.HeadingOutline
import dev.joseramos.aireader.text.PageHeading
import java.io.File
import java.util.Optional
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Detección de capítulos con IA, para cuando el PDF no trae índice y las heurísticas fallan.
 * La implementa el módulo `:ai:llm`; si no hay clave de API devuelve una lista vacía.
 */
interface LlmChapterDetection {
    /** [pageHeads]: número de página (base 1) y su primera línea con texto. */
    suspend fun detect(pageHeads: List<Pair<Int, String>>, pageCount: Int): List<DetectedChapter>
}

/**
 * Índice del libro, con capítulos y sus apartados, en cascada: índice del PDF → títulos del texto
 * (por su tamaño de letra) → encabezados típicos («Capítulo 3») → IA → bloques de páginas.
 */
class ChapterDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chapterDao: ChapterDao,
    private val pageTextDao: PageTextDao,
    private val llm: Optional<LlmChapterDetection>
) {
    /**
     * [upgrade]: el texto se acaba de volver a procesar en un libro que ya tenía capítulos. Entonces
     * se añaden los apartados que falten sin perder los resúmenes ya generados de cada capítulo.
     * [retryWithAi]: ahora hay clave de API y antes no; si el índice se quedó en bloques de páginas
     * (sin IA), se vuelve a intentar con ella.
     */
    suspend fun run(book: BookEntity, upgrade: Boolean = false, retryWithAi: Boolean = false) {
        val existing = chapterDao.getContents(book.id)
        val redoBlocks = retryWithAi && existing.isNotEmpty() && existing.all { it.source == ChapterSource.BLOCKS }
        if (existing.isNotEmpty() && !redoBlocks && (!upgrade || existing.any { it.level > 0 })) return
        val (detected, source) = detect(book)
        if (redoBlocks && source == ChapterSource.BLOCKS) return
        val entries = normalize(detected)
        if (existing.isEmpty()) {
            chapterDao.insertAll(toEntities(book, entries, source))
            return
        }
        upgradeContents(book, existing, entries, source)
    }

    private suspend fun upgradeContents(
        book: BookEntity,
        existing: List<ChapterEntity>,
        entries: List<DetectedChapter>,
        source: ChapterSource
    ) {
        val sameChapters = entries.filter { it.level == 0 }.map { it.startPage } == existing.map { it.startPage }
        when {
            sameChapters -> insertSections(book, existing, entries.filter { it.level > 0 }, source)
            chapterDao.countDependents(book.id) == 0 -> {
                Log.i(TAG, "Se rehace el índice de ${book.id} (${entries.size} entradas)")
                chapterDao.deleteByBook(book.id)
                chapterDao.insertAll(toEntities(book, entries, source))
                chapterDao.remapChunks(book.id)
            }
            // Hay resúmenes o personajes colgando de los capítulos actuales: se conservan y lo nuevo
            // entra como apartados suyos.
            else -> insertSections(
                book,
                existing,
                entries.filterNot { entry ->
                    existing.any { it.startPage == entry.startPage && it.title == entry.title }
                }.map { it.copy(level = maxOf(1, it.level)) },
                source
            )
        }
    }

    /** Añade [sections] bajo los capítulos que ya existen. */
    private suspend fun insertSections(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        sections: List<DetectedChapter>,
        source: ChapterSource
    ) {
        if (sections.isEmpty()) return
        val all = (chapters.map { DetectedChapter(it.title, it.startPage, 0) } + sections)
            .sortedWith(compareBy({ it.startPage }, { it.level }))
        chapterDao.insertAll(toEntities(book, all, source).filter { it.level > 0 })
    }

    private suspend fun detect(book: BookEntity): Pair<List<DetectedChapter>, ChapterSource> {
        val outline = runCatching {
            PdfTextDocument.open(context, File(book.filePath)).use { it.outline() }
        }.getOrDefault(emptyList())
        fromOutline(outline).takeIf { it.isNotEmpty() }?.let { return it to ChapterSource.OUTLINE }

        val pages = pageTextDao.getByBook(book.id)
        val firstLines = pages.map { page -> page.rawText.lines().filter { it.isNotBlank() }.take(HEAD_LINES) }
        // Títulos por su tamaño de letra (con apartados) y, si no, encabezados típicos («Capítulo 3»).
        HeadingOutline.build(headings(pages), book.pageCount)
            .ifEmpty { ChapterHeuristics.detect(firstLines) }
            .takeIf { it.isNotEmpty() }
            ?.let { return it to ChapterSource.HEURISTIC }

        val heads = firstLines.mapIndexedNotNull { i, lines ->
            lines.firstOrNull()?.let { pages[i].page to it.take(HEAD_CHARS) }
        }
        val fromLlm = llm.orElse(null)?.let {
            runCatching { it.detect(heads, book.pageCount) }.getOrDefault(emptyList())
        }
        if (!fromLlm.isNullOrEmpty() && fromLlm.size >= MIN_CHAPTERS) return fromLlm to ChapterSource.LLM

        return ChapterHeuristics.blocks(book.pageCount) to ChapterSource.BLOCKS
    }

    /** Párrafos que son títulos, con su nivel tipográfico. */
    private fun headings(pages: List<PageTextEntity>): List<PageHeading> = pages.flatMap { page ->
        val paragraphs = decode<String>(page.paragraphsJson)
        decode<Int>(page.levelsJson).mapIndexedNotNull { i, level ->
            paragraphs.getOrNull(i)?.takeIf { level > 0 }?.let { PageHeading(page.page, level, it) }
        }
    }

    /**
     * Índice del PDF con sus niveles. Si el primer nivel es una única entrada (el título del libro)
     * se baja al siguiente; se conservan hasta [MAX_DEPTH] niveles.
     */
    private fun fromOutline(outline: List<OutlineEntry>): List<DetectedChapter> {
        var entries = outline.filter { it.page != null }
        while (entries.isNotEmpty()) {
            val top = entries.minOf { it.depth }
            val atTop = entries.filter { it.depth == top }
            if (atTop.size >= MIN_CHAPTERS) {
                return entries.filter { it.depth - top < MAX_DEPTH }
                    .map { DetectedChapter(HeadingOutline.title(it.title), it.page!!, it.depth - top) }
            }
            if (entries.size == atTop.size) return emptyList()
            entries = entries.filter { it.depth > top }
        }
        return emptyList()
    }

    /** Orden de lectura, un solo capítulo por página y apartados sin repetir. */
    private fun normalize(entries: List<DetectedChapter>): List<DetectedChapter> {
        val sorted = entries.withIndex().sortedWith(compareBy({ it.value.startPage }, { it.index })).map { it.value }
        val seenChapterPages = mutableSetOf<Int>()
        val seenSections = mutableSetOf<Pair<Int, String>>()
        return sorted.filter { entry ->
            if (entry.level ==
                0
            ) {
                seenChapterPages.add(entry.startPage)
            } else {
                seenSections.add(entry.startPage to entry.title)
            }
        }
    }

    /**
     * Cada entrada acaba donde empieza la siguiente de su nivel o de uno superior; los apartados
     * llevan el número de su capítulo.
     */
    private fun toEntities(
        book: BookEntity,
        entries: List<DetectedChapter>,
        source: ChapterSource
    ): List<ChapterEntity> {
        var number = 0
        return entries.mapIndexed { i, entry ->
            if (entry.level == 0) number++
            val next = entries.drop(i + 1).firstOrNull { it.level <= entry.level }
            val end = next?.startPage?.minus(1) ?: book.pageCount
            ChapterEntity(
                bookId = book.id,
                number = maxOf(1, number),
                title = entry.title,
                startPage = entry.startPage,
                endPage = maxOf(entry.startPage, end),
                source = source,
                level = entry.level
            )
        }
    }

    private inline fun <reified T> decode(json: String): List<T> =
        runCatching { Json.decodeFromString<List<T>>(json) }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "ChapterDetector"
        private const val MIN_CHAPTERS = 2
        private const val MAX_DEPTH = 3
        private const val HEAD_LINES = 4
        private const val HEAD_CHARS = 120
    }
}
