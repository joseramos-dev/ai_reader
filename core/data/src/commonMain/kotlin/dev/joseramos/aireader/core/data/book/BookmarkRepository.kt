package dev.joseramos.aireader.core.data.book

import dev.joseramos.aireader.core.common.currentTimeMillis
import dev.joseramos.aireader.core.data.db.BookmarkDao
import dev.joseramos.aireader.core.data.db.BookmarkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Marcapáginas de una página (base 1), con nota opcional. */
data class Bookmark(val id: Long, val page: Int, val note: String?, val createdAt: Long)

class BookmarkRepository(private val dao: BookmarkDao) {
    /** Marcapáginas del libro, por orden de página. */
    fun observe(bookId: String): Flow<List<Bookmark>> =
        dao.observeByBook(bookId).map { list -> list.map { Bookmark(it.id, it.page, it.note, it.createdAt) } }

    /** Marca la página si no lo está y la desmarca si ya lo estaba. */
    suspend fun toggle(bookId: String, page: Int) {
        val existing = dao.get(bookId, page)
        if (existing != null) {
            dao.delete(existing.id)
        } else {
            dao.upsert(
                BookmarkEntity(bookId = bookId, page = page, note = null, createdAt = currentTimeMillis())
            )
        }
    }

    /** Marca la página con una nota (o cambia la nota si ya estaba marcada). */
    suspend fun setNote(bookId: String, page: Int, note: String?) {
        val existing = dao.get(bookId, page)
        val clean = note?.trim()?.takeIf { it.isNotEmpty() }
        dao.upsert(
            existing?.copy(note = clean)
                ?: BookmarkEntity(bookId = bookId, page = page, note = clean, createdAt = currentTimeMillis())
        )
    }

    suspend fun delete(id: Long) = dao.delete(id)
}
