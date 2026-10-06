package dev.joseramos.aireader.core.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Base de datos de la app. El esquema de cada versión se exporta a `core/data/schemas/` y se
 * versiona en git: cualquier cambio de entidades requiere subir [version] y añadir la migración.
 *
 * - v2: marcapáginas, `maxPage`, tipo de documento, repasos (`untilPage`) y personajes (F8–F11).
 * - v3: geometría de las líneas (`page_layouts`), nivel de cada párrafo y apartados en el índice.
 * - v4: índice compuesto (bookId, createdAt) en `bookmarks` para el marcapáginas más reciente de
 *   cada libro en la biblioteca (antes sin índice para ese orden).
 * - v6: subrayados de texto (`highlights`).
 * - v7: `chunks_fts` con el tokenizador `unicode61` sin diacríticos (migración manual: reconstruye el índice).
 * - v8: fragmentos enviados al modelo en cada respuesta del chat (`sourcesJson`), para la sección «Fuentes».
 * - v9: los resúmenes (`summaries`) dejan paso a los hechos clave de cada capítulo, con su página
 *   (`chapter_key_points`).
 */
@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        PageTextEntity::class,
        PageLayoutEntity::class,
        ChunkEntity::class,
        ChunkFtsEntity::class,
        ChunkEmbeddingEntity::class,
        ChapterKeyPointsEntity::class,
        ReadingPositionEntity::class,
        ChatThreadEntity::class,
        ChatMessageEntity::class,
        DownloadedModelEntity::class,
        BookmarkEntity::class,
        CharacterEntity::class,
        CharacterNameEntity::class,
        CharacterFactEntity::class,
        RelationEntity::class,
        CharacterScanEntity::class,
        HighlightEntity::class
    ],
    version = 9,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = AppDatabase.Migration1To2::class),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = AppDatabase.DeleteSummaries::class)
    ]
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    abstract fun chapterDao(): ChapterDao

    abstract fun pageTextDao(): PageTextDao

    abstract fun pageLayoutDao(): PageLayoutDao

    abstract fun chunkDao(): ChunkDao

    abstract fun chunkEmbeddingDao(): ChunkEmbeddingDao

    abstract fun keyPointsDao(): KeyPointsDao

    abstract fun readingPositionDao(): ReadingPositionDao

    abstract fun chatDao(): ChatDao

    abstract fun downloadedModelDao(): DownloadedModelDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun characterDao(): CharacterDao

    abstract fun highlightDao(): HighlightDao

    /** La página más avanzada de los libros ya empezados es, como mínimo, la página actual. */
    class Migration1To2 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE reading_positions SET maxPage = page")
        }
    }

    /** Los resúmenes guardados se descartan: ahora se generan, cuando se piden, a partir de los hechos clave. */
    @DeleteTable(tableName = "summaries")
    class DeleteSummaries : AutoMigrationSpec

    companion object {
        const val NAME = "aireader.db"
    }
}
