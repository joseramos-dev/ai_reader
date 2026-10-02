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
}
