package dev.joseramos.aireader.core.data.book

import dev.joseramos.aireader.core.data.db.ReadingPositionDao
import dev.joseramos.aireader.core.data.db.ReadingPositionEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Punto exacto de lectura: página (base 1), párrafo y frase dentro de la página. */
data class ReadingPosition(val page: Int, val paragraph: Int = 0, val phrase: Int = 0)

@Singleton
class ReadingPositionRepository @Inject constructor(private val dao: ReadingPositionDao) {
    suspend fun get(bookId: String): ReadingPosition? =
        dao.get(bookId)?.let { ReadingPosition(it.page, it.paragraphIndex, it.phraseIndex) }

    /**
     * Guarda dónde va la lectura. Con [markRead], la página cuenta además como leída y puede
     * subir `maxPage` (así lo hace la voz, que lee cada frase); si no, `maxPage` se conserva.
     */
    suspend fun save(bookId: String, position: ReadingPosition, markRead: Boolean = false) {
        val previousMax = dao.get(bookId)?.maxPage ?: 0
        dao.upsert(
            ReadingPositionEntity(
                bookId = bookId,
                page = position.page,
                paragraphIndex = position.paragraph,
                phraseIndex = position.phrase,
                updatedAt = System.currentTimeMillis(),
                maxPage = if (markRead) maxOf(previousMax, position.page) else previousMax
            )
        )
    }

    /** La página se ha leído de verdad (no solo se ha pasado por ella): sube `maxPage` si hace falta. */
    suspend fun markRead(bookId: String, page: Int) = dao.raiseMaxPage(bookId, page)

    /** Página más avanzada leída; 0 si el libro no se ha empezado. Nunca baja. */
    fun observeMaxPage(bookId: String): Flow<Int> = dao.observeMaxPage(bookId).map { it ?: 0 }
}
