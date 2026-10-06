package dev.joseramos.aireader.core.data.book

import dev.joseramos.aireader.core.data.db.ChapterKeyPointsEntity
import dev.joseramos.aireader.core.data.db.KeyPointsDao
import dev.joseramos.aireader.core.data.db.KeyPointsStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Un hecho clave (o una idea clave) de un capítulo y la página (base 1) donde ocurre. */
@Serializable
data class KeyPoint(val text: String, val page: Int)

/** Hechos clave de un capítulo, en orden. Sin [KeyPointsStatus.READY], [points] está vacío. */
data class ChapterKeyPoints(val chapterId: Long, val status: KeyPointsStatus, val points: List<KeyPoint>) {
    /** Los que ocurren hasta [page] (anti-spoilers); todos si es `null`. */
    fun until(page: Int?): List<KeyPoint> = if (page == null) points else points.filter { it.page <= page }
}

/**
 * Hechos clave guardados de cada capítulo. Se generan cuando hacen falta (`KeyPointsGenerator`):
 * un capítulo que no aparece aquí aún no los tiene.
 */
@Singleton
class KeyPointsRepository @Inject constructor(private val dao: KeyPointsDao) {
    fun observe(bookId: String): Flow<Map<Long, ChapterKeyPoints>> =
        dao.observeByBook(bookId).map { list -> list.associate { it.chapterId to it.toKeyPoints() } }

    suspend fun all(bookId: String): Map<Long, ChapterKeyPoints> =
        dao.getByBook(bookId).associate { it.chapterId to it.toKeyPoints() }

    suspend fun get(chapterId: Long): ChapterKeyPoints? = dao.get(chapterId)?.toKeyPoints()

    suspend fun save(bookId: String, chapterId: Long, status: KeyPointsStatus, points: List<KeyPoint>, model: String) =
        dao.upsert(
            ChapterKeyPointsEntity(
                chapterId = chapterId,
                bookId = bookId,
                status = status,
                pointsJson = Json.encodeToString(points),
                model = model,
                createdAt = System.currentTimeMillis()
            )
        )

    /** Olvida los de un capítulo, para volver a generarlos. */
    suspend fun delete(chapterId: Long) = dao.delete(chapterId)
}

private fun ChapterKeyPointsEntity.toKeyPoints() = ChapterKeyPoints(
    chapterId = chapterId,
    status = status,
    points = runCatching { Json.decodeFromString<List<KeyPoint>>(pointsJson) }.getOrDefault(emptyList())
)
