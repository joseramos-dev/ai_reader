package dev.joseramos.aireader.core.data.book

import dev.joseramos.aireader.core.data.db.HighlightDao
import dev.joseramos.aireader.core.data.db.HighlightEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Subrayado de un fragmento exacto de texto (página, párrafo y caracteres), con nota opcional. */
data class Highlight(
    val id: Long,
    val page: Int,
    val paragraph: Int,
    val startOffset: Int,
    val endOffset: Int,
    val note: String?,
    val createdAt: Long
)

class HighlightRepository(private val dao: HighlightDao) {
    /** Subrayados del libro, por página y posición. */
    fun observe(bookId: String): Flow<List<Highlight>> = dao.observeByBook(bookId).map { list ->
        list.map { Highlight(it.id, it.page, it.paragraph, it.startOffset, it.endOffset, it.note, it.createdAt) }
    }

    suspend fun add(bookId: String, page: Int, paragraph: Int, range: IntRange, note: String? = null): Long =
        dao.upsert(
            HighlightEntity(
                bookId = bookId,
                page = page,
                paragraph = paragraph,
                startOffset = range.first,
                endOffset = range.last + 1,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = System.currentTimeMillis()
            )
        )

    suspend fun setNote(id: Long, note: String?) = dao.setNote(id, note?.trim()?.takeIf { it.isNotEmpty() })

    suspend fun delete(id: Long) = dao.delete(id)
}
