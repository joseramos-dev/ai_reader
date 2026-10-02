package dev.joseramos.aireader.core.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Base de datos de la app. El esquema de cada versión se exporta a `core/data/schemas/` y se
 * versiona en git: cualquier cambio de entidades requiere subir [version] y añadir la migración.
 *
 * - v2: marcapáginas, `maxPage`, tipo de documento, repasos (`untilPage`) y personajes (F8–F11).
 * - v3: geometría de las líneas (`page_layouts`), nivel de cada párrafo y apartados en el índice.
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
        SummaryEntity::class,
        ReadingPositionEntity::class,
        ChatThreadEntity::class,
        ChatMessageEntity::class,
        DownloadedModelEntity::class,
        BookmarkEntity::class,
        CharacterEntity::class,
        CharacterNameEntity::class,
        CharacterFactEntity::class,
        RelationEntity::class,
        CharacterScanEntity::class
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = AppDatabase.Migration1To2::class),
        AutoMigration(from = 2, to = 3)
    ]
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    abstract fun chapterDao(): ChapterDao

    abstract fun pageTextDao(): PageTextDao

    abstract fun pageLayoutDao(): PageLayoutDao

    abstract fun chunkDao(): ChunkDao

    abstract fun chunkEmbeddingDao(): ChunkEmbeddingDao

    abstract fun summaryDao(): SummaryDao

    abstract fun readingPositionDao(): ReadingPositionDao

    abstract fun chatDao(): ChatDao

    abstract fun downloadedModelDao(): DownloadedModelDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun characterDao(): CharacterDao

    /** La página más avanzada de los libros ya empezados es, como mínimo, la página actual. */
    class Migration1To2 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE reading_positions SET maxPage = page")
        }
    }

    companion object {
        const val NAME = "aireader.db"
    }
}
