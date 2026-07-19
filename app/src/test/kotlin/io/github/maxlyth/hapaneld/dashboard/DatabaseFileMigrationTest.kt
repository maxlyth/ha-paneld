package io.github.maxlyth.hapaneld.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DatabaseFileMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun movesDatabaseAndSidecarsTogether() {
        val directory = temporary.newFolder()
        val legacy = File(directory, EntityCatalogStore.LEGACY_DATABASE_NAME)
        val target = File(directory, EntityCatalogStore.DATABASE_NAME)
        listOf("", "-wal", "-shm").forEachIndexed { index, suffix ->
            File(legacy.path + suffix).writeText("part-$index")
        }

        assertTrue(migrateDatabaseFiles(legacy, target))

        listOf("", "-wal", "-shm").forEachIndexed { index, suffix ->
            assertFalse(File(legacy.path + suffix).exists())
            assertEquals("part-$index", File(target.path + suffix).readText())
        }
    }

    @Test fun refusesToOverwriteAnExistingTarget() {
        val directory = temporary.newFolder()
        val legacy = File(directory, EntityCatalogStore.LEGACY_DATABASE_NAME).apply { writeText("legacy") }
        val target = File(directory, EntityCatalogStore.DATABASE_NAME).apply { writeText("current") }

        assertFalse(migrateDatabaseFiles(legacy, target))
        assertEquals("legacy", legacy.readText())
        assertEquals("current", target.readText())
    }

    @Test fun footprintCountsOnlyTheDatabaseAndKnownSqliteSidecars() {
        val directory = temporary.newFolder()
        val database = File(directory, EntityCatalogStore.DATABASE_NAME).apply { writeBytes(ByteArray(11)) }
        File(database.path + "-wal").writeBytes(ByteArray(13))
        File(database.path + "-shm").writeBytes(ByteArray(17))
        File(database.path + "-journal").writeBytes(ByteArray(19))
        File(database.path + "-backup").writeBytes(ByteArray(23))

        assertEquals(60L, EntityCatalogStore.knownDatabaseFootprint(database))
    }
}
