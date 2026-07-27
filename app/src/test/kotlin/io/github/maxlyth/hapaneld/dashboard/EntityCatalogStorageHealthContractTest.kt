package io.github.maxlyth.hapaneld.dashboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityCatalogStorageHealthContractTest {
    private val source by lazy {
        listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityCatalogStore.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityCatalogStore.kt"),
        ).first(File::isFile).readText()
    }

    @Test fun probeUsesTheOpenedDatabaseFilesystemAllowlistedFilesAndCancellableQuickCheck() {
        val probe = functionBody("storageHealthObservation")

        assertTrue("StatFs must measure the database SQLite actually opened", "StatFs(database.path)" in probe)
        assertTrue("quick_check must be bounded", "PRAGMA quick_check(1)" in probe)
        assertTrue("quick_check must receive lifecycle cancellation", "null, cancellationSignal" in probe)
        assertTrue("main database size must be measured", "storageKnownFileBytes(database)" in probe)
        assertTrue("WAL must remain a distinct metric", "database.path + \"-wal\"" in probe)
        assertTrue("SHM must be allowlisted", "database.path + \"-shm\"" in probe)
        assertTrue("rollback journal must be allowlisted", "database.path + \"-journal\"" in probe)
        assertFalse("the health probe must never enumerate arbitrary files", "listFiles" in probe)
        assertTrue("filesystem-only failure must preserve SQLite metrics", "runCatching { StatFs(database.path) }.getOrNull()" in probe)
        assertTrue("SQLite read failure must be latched", "recordDatabaseFailure(\"storage-health-read\"" in probe)
    }

    @Test fun everyDirectWritableDatabaseOwnerHasAnAuditedBoundary() {
        val owners = source.lines().mapIndexedNotNull { index, line ->
            if ("writableDatabase" !in line) return@mapIndexedNotNull null
            val prefix = source.lines().take(index + 1).joinToString("\n")
            Regex("fun\\s+([A-Za-z0-9_]+)\\s*\\(").findAll(prefix).lastOrNull()?.groupValues?.get(1)
        }.toSet()

        assertEquals(
            setOf(
                "markStatus",
                "commitSync",
                "recordAccess",
                "recordMetrics",
                "recordDashboardPerformance",
                "recordAmbientHistory",
                "seedAmbientHistory",
                "resetAmbientHistory",
                "setOverrides",
                "resetEvidence",
                "setIssueIgnored",
                "maintainSoftLimit",
                "writeProximityBatch",
                "clearProximityLearning",
            ),
            owners,
        )

        val operations = listOf(
            "catalog-status",
            "catalog-sync",
            "catalog-access-history",
            "catalog-metric-history",
            "dashboard-performance-history",
            "ambient-history",
            "ambient-history-seed",
            "ambient-history-reset",
            "catalog-overrides",
            "catalog-reset",
            "catalog-issue-override",
            "proximity-history",
            "proximity-history-reset",
        )
        operations.forEach { operation ->
            assertTrue("missing observed boundary for $operation", "observedWrite(\"$operation\"" in source)
        }
        assertTrue("single-row override must use the observed batch owner",
            "setOverrides(instance, path, listOf(entityId), override)" in functionBody("setOverride"))
        assertTrue("best-effort proximity maintenance failures must still latch",
            "recordDatabaseFailure(\"proximity-history-maintenance\"" in functionBody("writeProximityBatch"))
        val issueWrite = functionBody("setIssueIgnored")
        assertTrue("a committed issue override must report write recovery",
            "reportsSuccessfulWrite = { it }" in issueWrite)
        assertTrue("benign issue-override exits must pass through the observed boundary",
            "return@observedWrite false" in issueWrite)
    }

    @Test fun schemaAndPreopenFailuresCannotDisappear() {
        assertTrue("onCreate must have a schema failure boundary",
            "observedSchemaWrite(\"database-create\")" in functionBody("onCreate"))
        assertTrue("onUpgrade must have a schema failure boundary",
            "observedSchemaWrite(\"database-upgrade\")" in functionBody("onUpgrade"))
        listOf(
            "database-preopen-reconcile",
            "database-version-read",
            "database-vault-read",
            "database-vault-restore",
        ).forEach { operation ->
            assertTrue("missing pre-open failure operation $operation", "retainDatabaseFailure(\"$operation\"" in source)
        }
        val checkpoint = functionBody("checkpointDatabaseFile")
        assertTrue("checkpoint exceptions must reach the shared failure authority",
            "recordDatabaseFailure(\"database-checkpoint\", failure)" in checkpoint)
        assertTrue("a busy checkpoint result must also be truthful",
            "recordDatabaseFailure(" in checkpoint && "\"database-checkpoint\"" in checkpoint)
    }

    @Test fun managerForwardsTheCancellationSignalWithoutOwningAnotherProbe() {
        val manager = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt"),
        ).first(File::isFile).readText()
        val start = manager.indexOf("fun storageHealthObservation")
        val forwarder = manager.substring(start, manager.indexOf('\n', manager.indexOf("store.storageHealthObservation", start)))

        assertTrue("CancellationSignal?" in forwarder)
        assertTrue("store.storageHealthObservation(cancellationSignal)" in forwarder)
        assertFalse("PRAGMA" in forwarder)
    }

    private fun functionBody(name: String): String {
        val function = Regex("fun\\s+$name\\s*\\(").find(source)
            ?: error("missing function $name")
        val next = Regex("\\n    (?:(?:private|internal|override) )?fun\\s+")
            .find(source, function.range.last + 1)?.range?.first ?: source.length
        return source.substring(function.range.first, next)
    }
}
