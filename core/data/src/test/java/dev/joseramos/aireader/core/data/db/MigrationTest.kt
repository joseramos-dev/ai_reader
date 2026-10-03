package dev.joseramos.aireader.core.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    // Con el driver (y no con SupportSQLite) para que la ruta del fichero funcione también en Windows.
    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = ApplicationProvider.getApplicationContext<Context>().getDatabasePath("migration-test"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AppDatabase::class
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
}
