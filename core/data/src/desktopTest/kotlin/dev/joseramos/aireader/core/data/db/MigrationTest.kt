package dev.joseramos.aireader.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        schemaDirectoryPath = Path("schemas"),
        databasePath = createTempDirectory("migration-test").resolve("test.db"),
        driver = BundledSQLiteDriver(),
        databaseClass = AppDatabase::class,
        databaseFactory = { AppDatabaseConstructor.initialize() },
        autoMigrationSpecs = listOf(AppDatabase.Migration1To2())
    )

    @Test
    fun migrate1To2KeepsBooksAndStartsMaxPageAtCurrentPage() {
        helper.createDatabase(1).apply {
            execSQL(
                """
                INSERT INTO books (id, title, author, fileName, filePath, pageCount, coverPath, importedAt,
                    lastOpenedAt, indexStatus, indexProgress, cleanerVersion)
                VALUES ('a', 'Libro', NULL, 'a.pdf', '/a.pdf', 10, NULL, 1, NULL, 'READY', 1.0, 1)
                """.trimIndent()
            )
            execSQL(
                "INSERT INTO reading_positions (bookId, page, paragraphIndex, phraseIndex, updatedAt) " +
                    "VALUES ('a', 7, 0, 0, 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(2, emptyList())
        db.prepare("SELECT maxPage FROM reading_positions WHERE bookId = 'a'").use {
            assertTrue(it.step())
            assertEquals(7, it.getInt(0))
        }
        db.prepare("SELECT documentType, documentTypeSource FROM books WHERE id = 'a'").use {
            assertTrue(it.step())
            assertTrue(it.isNull(0))
            assertEquals("AUTO", it.getText(1))
        }
        db.close()
    }

    @Test
    fun migrate2To3AddsLevelsWithDefaults() {
        helper.createDatabase(2).apply {
            execSQL(
                """
                INSERT INTO books (id, title, author, fileName, filePath, pageCount, coverPath, importedAt,
                    lastOpenedAt, indexStatus, indexProgress, cleanerVersion, documentTypeSource)
                VALUES ('a', 'Libro', NULL, 'a.pdf', '/a.pdf', 10, NULL, 1, NULL, 'READY', 1.0, 1, 'AUTO')
                """.trimIndent()
            )
            execSQL(
                "INSERT INTO chapters (bookId, number, title, startPage, endPage, source) " +
                    "VALUES ('a', 1, 'Uno', 1, 10, 'OUTLINE')"
            )
            execSQL(
                "INSERT INTO page_texts " +
                    "(bookId, page, rawText, cleanText, paragraphsJson, isScanned, cleanerVersion) " +
                    "VALUES ('a', 1, 'raw', 'clean', '[\"clean\"]', 0, 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(3, emptyList())
        db.prepare("SELECT level FROM chapters WHERE bookId = 'a'").use {
            assertTrue(it.step())
            assertEquals(0, it.getInt(0))
        }
        db.prepare("SELECT levelsJson FROM page_texts WHERE bookId = 'a'").use {
            assertTrue(it.step())
            assertEquals("[]", it.getText(0))
        }
        db.close()
    }

    @Test
    fun migrate3To4KeepsBookmarksAndAddsCompositeIndex() {
        helper.createDatabase(3).apply {
            execSQL(
                """
                INSERT INTO books (id, title, author, fileName, filePath, pageCount, coverPath, importedAt,
                    lastOpenedAt, indexStatus, indexProgress, cleanerVersion, documentTypeSource)
                VALUES ('a', 'Libro', NULL, 'a.pdf', '/a.pdf', 10, NULL, 1, NULL, 'READY', 1.0, 1, 'AUTO')
                """.trimIndent()
            )
            execSQL(
                "INSERT INTO bookmarks (id, bookId, page, note, createdAt) VALUES (1, 'a', 3, NULL, 100)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(4, emptyList())
        db.prepare("SELECT page FROM bookmarks WHERE bookId = 'a'").use {
            assertTrue(it.step())
            assertEquals(3, it.getInt(0))
        }
        db.prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'bookmarks'").use { cursor ->
            val names = generateSequence { if (cursor.step()) cursor.getText(0) else null }.toList()
            assertTrue(names.any { it.contains("bookId") })
        }
        db.close()
    }

    @Test
    fun migrate4To5MarksExistingBooksAsNotPreparedWithAi() {
        helper.createDatabase(4).apply {
            execSQL(
                """
                INSERT INTO books (id, title, author, fileName, filePath, pageCount, coverPath, importedAt,
                    lastOpenedAt, indexStatus, indexProgress, cleanerVersion, documentTypeSource)
                VALUES ('a', 'Libro', NULL, 'a.pdf', '/a.pdf', 10, NULL, 1, NULL, 'READY', 1.0, 1, 'AUTO')
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(5, emptyList())
        db.prepare("SELECT aiPrepared FROM books WHERE id = 'a'").use {
            assertTrue(it.step())
            assertEquals(0, it.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate5To6AddsHighlightsTable() {
        helper.createDatabase(5).apply {
            execSQL(
                """
                INSERT INTO books (id, title, author, fileName, filePath, pageCount, coverPath, importedAt,
                    lastOpenedAt, indexStatus, indexProgress, cleanerVersion, documentTypeSource, aiPrepared)
                VALUES ('a', 'Libro', NULL, 'a.pdf', '/a.pdf', 10, NULL, 1, NULL, 'READY', 1.0, 1, 'AUTO', 0)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(6, emptyList())
        db.execSQL(
            "INSERT INTO highlights (id, bookId, page, paragraph, startOffset, endOffset, note, createdAt) " +
                "VALUES (1, 'a', 3, 0, 5, 12, NULL, 100)"
        )
        db.prepare("SELECT page, paragraph, startOffset, endOffset FROM highlights WHERE bookId = 'a'").use {
            assertTrue(it.step())
            assertEquals(3, it.getInt(0))
            assertEquals(0, it.getInt(1))
            assertEquals(5, it.getInt(2))
            assertEquals(12, it.getInt(3))
        }
        db.close()
    }

    @Test
    fun migrate6To7RebuildsFtsIndexIgnoringAccents() {
        helper.createDatabase(6).apply {
            execSQL(
                """
                INSERT INTO books (id, title, author, fileName, filePath, pageCount, coverPath, importedAt,
                    lastOpenedAt, indexStatus, indexProgress, cleanerVersion, documentTypeSource, aiPrepared)
                VALUES ('a', 'Libro', NULL, 'a.pdf', '/a.pdf', 10, NULL, 1, NULL, 'READY', 1.0, 1, 'AUTO', 0)
                """.trimIndent()
            )
            execSQL(
                "INSERT INTO chunks (id, bookId, chapterId, ordinal, text, startPage, endPage, charStart, charEnd, " +
                    "tokenCount) VALUES (1, 'a', NULL, 0, 'Raskólnikov mató a la vieja', 1, 1, 0, 26, 6)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(7, listOf(MIGRATION_6_7))
        for (query in listOf("mato", "mató", "raskolnikov*")) {
            db.prepare("SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH '$query'").use {
                assertTrue(query, it.step())
                assertEquals(1, it.getInt(0))
            }
        }
        // Los disparadores siguen sincronizando el índice con `chunks`.
        db.execSQL(
            "INSERT INTO chunks (id, bookId, chapterId, ordinal, text, startPage, endPage, charStart, charEnd, " +
                "tokenCount) VALUES (2, 'a', NULL, 1, 'Último capítulo', 2, 2, 0, 15, 3)"
        )
        db.prepare("SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH 'ultimo'").use {
            assertTrue(it.step())
            assertEquals(2, it.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate7To8KeepsMessagesWithoutSources() {
        helper.createDatabase(7).apply {
            execSQL(
                """
                INSERT INTO books (id, title, author, fileName, filePath, pageCount, coverPath, importedAt,
                    lastOpenedAt, indexStatus, indexProgress, cleanerVersion, documentTypeSource, aiPrepared)
                VALUES ('a', 'Libro', NULL, 'a.pdf', '/a.pdf', 10, NULL, 1, NULL, 'READY', 1.0, 1, 'AUTO', 0)
                """.trimIndent()
            )
            execSQL("INSERT INTO chat_threads (id, bookId, createdAt) VALUES (1, 'a', 1)")
            execSQL(
                "INSERT INTO chat_messages (id, threadId, role, text, citationsJson, createdAt) " +
                    "VALUES (1, 1, 'ASSISTANT', 'Respuesta [p. 3]', '[3]', 2)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(8, emptyList())
        db.prepare("SELECT text, citationsJson, sourcesJson FROM chat_messages WHERE id = 1").use {
            assertTrue(it.step())
            assertEquals("Respuesta [p. 3]", it.getText(0))
            assertEquals("[3]", it.getText(1))
            assertEquals("[]", it.getText(2))
        }
        db.close()
    }

    @Test
    fun migrate8To9ReplacesSummariesWithKeyPoints() {
        helper.createDatabase(8).apply {
            execSQL(
                """
                INSERT INTO books (id, title, author, fileName, filePath, pageCount, coverPath, importedAt,
                    lastOpenedAt, indexStatus, indexProgress, cleanerVersion, documentTypeSource, aiPrepared)
                VALUES ('a', 'Libro', NULL, 'a.pdf', '/a.pdf', 10, NULL, 1, NULL, 'READY', 1.0, 1, 'AUTO', 0)
                """.trimIndent()
            )
            execSQL(
                "INSERT INTO chapters (id, bookId, number, title, startPage, endPage, source, level) " +
                    "VALUES (1, 'a', 1, 'Uno', 1, 10, 'OUTLINE', 0)"
            )
            execSQL(
                "INSERT INTO summaries (id, bookId, chapterId, kind, text, model, createdAt, untilPage) " +
                    "VALUES (1, 'a', 1, 'CHAPTER_SHORT', 'Resumen', 'm', 1, NULL)"
            )
            execSQL("INSERT INTO chat_threads (id, bookId, createdAt) VALUES (1, 'a', 1)")
            close()
        }

        val db = helper.runMigrationsAndValidate(9, emptyList())
        db.prepare("SELECT COUNT(*) FROM sqlite_master WHERE name = 'summaries'").use {
            assertTrue(it.step())
            assertEquals(0, it.getInt(0))
        }
        db.execSQL(
            "INSERT INTO chapter_key_points (chapterId, bookId, status, pointsJson, model, createdAt) " +
                "VALUES (1, 'a', 'READY', '[]', 'm', 2)"
        )
        db.prepare("SELECT status FROM chapter_key_points WHERE chapterId = 1").use {
            assertTrue(it.step())
            assertEquals("READY", it.getText(0))
        }
        db.prepare("SELECT COUNT(*) FROM chat_threads").use {
            assertTrue(it.step())
            assertEquals(1, it.getInt(0))
        }
        db.close()
    }
}
