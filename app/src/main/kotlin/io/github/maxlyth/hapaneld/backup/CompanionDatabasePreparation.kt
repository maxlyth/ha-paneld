package io.github.maxlyth.hapaneld.backup

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import io.github.maxlyth.hapaneld.control.CompanionDb
import java.io.File

/**
 * Validates and repairs a restored Companion database with Android's platform SQLite engine.
 *
 * The uploaded database is handled only in the app's private cache. The live Companion database is
 * still replaced by [CompanionRestore]'s root-side staged transaction, but that transaction no longer
 * depends on a target-device `sqlite3` executable being present.
 */
object CompanionDatabasePreparation {
    const val MAX_DATABASE_BYTES = 32L * 1024L * 1024L

    fun prepare(plan: CompanionRestore.Plan, cacheDir: File): CompanionRestore.Preparation? {
        val databaseIndex = plan.files.indexOfFirst { it.relativePath == CompanionRestore.DATABASE_FILE }
        if (databaseIndex < 0) return CompanionRestore.Preparation.unchanged(plan)
        val source = plan.files[databaseIndex]
        if (source.bytes.size.toLong() > MAX_DATABASE_BYTES) return null

        val temporary = runCatching { File.createTempFile("companion-prepare-", ".db", cacheDir) }.getOrNull()
            ?: return null
        val wal = File("${temporary.path}-wal")
        val shm = File("${temporary.path}-shm")
        val journal = File("${temporary.path}-journal")
        return try {
            temporary.writeBytes(source.bytes)
            val repaired = prepareDatabase(temporary) ?: return null
            // A successful DELETE-journal transaction must leave the complete result in the main file.
            // Never silently discard a WAL that could contain the repair.
            if (wal.exists() || shm.exists() || journal.exists()) return null
            val finalSize = temporary.length()
            if (finalSize <= 0L || finalSize > MAX_DATABASE_BYTES) return null
            val preparedDatabase = CompanionRestore.FilePayload(source.relativePath, temporary.readBytes())
            val files = plan.files.toMutableList().apply { this[databaseIndex] = preparedDatabase }
            CompanionRestore.Preparation(files, repaired)
        } catch (_: Exception) {
            null
        } finally {
            temporary.delete()
            wal.delete()
            shm.delete()
            journal.delete()
        }
    }

    private fun prepareDatabase(file: File): Int? {
        val database = runCatching {
            SQLiteDatabase.openDatabase(
                file.path,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
        }.getOrNull() ?: return null
        return try {
            // The backup deliberately excludes WAL/SHM. Force rollback-journal mode before mutation so
            // the repaired bytes are committed to the one file that will be staged on the target.
            if (!scalar(database, "PRAGMA journal_mode=DELETE").equals("delete", ignoreCase = true)) return null
            if (scalar(database, "PRAGMA quick_check(1)") != "ok") return null

            var repaired = 0
            database.beginTransaction()
            try {
                repaired = database.compileStatement(CompanionDb.INTERNAL_URL_REPAIR_SQL).executeUpdateDelete()
                val remaining = DatabaseUtils.longForQuery(
                    database,
                    CompanionDb.INTERNAL_URL_REPAIR_REMAINING_SQL,
                    null,
                )
                if (remaining != 0L) return null
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            if (scalar(database, "PRAGMA quick_check(1)") != "ok") return null
            repaired
        } catch (_: Exception) {
            null
        } finally {
            database.close()
        }
    }

    private fun scalar(database: SQLiteDatabase, sql: String): String? =
        database.rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
