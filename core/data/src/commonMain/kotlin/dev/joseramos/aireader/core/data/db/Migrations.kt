package dev.joseramos.aireader.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v6 → v7: `chunks_fts` pasa del tokenizador `simple` a `unicode61` sin diacríticos. Un índice FTS no
 * se puede alterar: se recrea (con sus disparadores de sincronización) y se reconstruye desde `chunks`.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        for (event in listOf("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT")) {
            connection.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_chunks_fts_$event")
        }
        connection.execSQL("DROP TABLE IF EXISTS `chunks_fts`")
        connection.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `chunks_fts` USING FTS4(`text` TEXT NOT NULL, " +
                "tokenize=unicode61 `remove_diacritics=1`, content=`chunks`)"
        )
        connection.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chunks_fts_BEFORE_UPDATE BEFORE UPDATE ON `chunks` " +
                "BEGIN DELETE FROM `chunks_fts` WHERE `docid`=OLD.`rowid`; END"
        )
        connection.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chunks_fts_BEFORE_DELETE BEFORE DELETE ON `chunks` " +
                "BEGIN DELETE FROM `chunks_fts` WHERE `docid`=OLD.`rowid`; END"
        )
        connection.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chunks_fts_AFTER_UPDATE AFTER UPDATE ON `chunks` " +
                "BEGIN INSERT INTO `chunks_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END"
        )
        connection.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chunks_fts_AFTER_INSERT AFTER INSERT ON `chunks` " +
                "BEGIN INSERT INTO `chunks_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END"
        )
        connection.execSQL("INSERT INTO `chunks_fts`(`chunks_fts`) VALUES ('rebuild')")
    }
}
