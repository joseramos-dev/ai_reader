package dev.joseramos.aireader.core.data.book

import androidx.room.withTransaction
import dev.joseramos.aireader.core.data.db.AppDatabase
import dev.joseramos.aireader.core.data.db.SummaryDao
import dev.joseramos.aireader.core.data.db.SummaryEntity
import dev.joseramos.aireader.core.data.db.SummaryKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Resumen guardado. [chapterId] es `null` para el resumen del libro y para el repaso, que cubre
 * hasta [untilPage].
 */
data class Summary(
    val chapterId: Long?,
    val kind: SummaryKind,
    val text: String,
    val model: String,
    val createdAt: Long,
    val untilPage: Int? = null
)

@Singleton
class SummaryRepository @Inject constructor(private val db: AppDatabase, private val dao: SummaryDao) {
    fun observe(bookId: String): Flow<List<Summary>> =
        dao.observeByBook(bookId).map { list -> list.map { it.toSummary() } }

    suspend fun all(bookId: String): List<Summary> = dao.getByBook(bookId).map { it.toSummary() }

    suspend fun get(bookId: String, chapterId: Long?, kind: SummaryKind): Summary? =
        dao.get(bookId, chapterId, kind)?.toSummary()

    /** Guarda el resumen sustituyendo el anterior del mismo capítulo y tipo. */
    suspend fun save(
        bookId: String,
        chapterId: Long?,
        kind: SummaryKind,
        text: String,
        model: String,
        untilPage: Int? = null
    ) = db.withTransaction {
        dao.delete(bookId, chapterId, kind)
        dao.upsert(
            SummaryEntity(
                bookId = bookId,
                chapterId = chapterId,
                kind = kind,
                text = text,
                model = model,
                createdAt = System.currentTimeMillis(),
                untilPage = untilPage
            )
        )
    }
}

private fun SummaryEntity.toSummary() = Summary(chapterId, kind, text, model, createdAt, untilPage)
