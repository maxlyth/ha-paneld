package io.github.maxlyth.hapaneld.dashboard

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the Tier-2 DB-schema downgrade net ([reconcilePreOpen]). Exercises the
 * upgrade-snapshot (with integrity and disk-space handling), downgrade-restore, sidecar preservation,
 * and no-compatible-backup fresh-start branches with real temp files and a stubbed WAL checkpoint.
 * Database snapshots carry a real SQLite header so integrity validation is exercised. The invariant:
 * config-bearing data is never lost outright, an upgrade never fails for lack of backup room, and a
 * downgrade either restores a valid compatible snapshot or preserves the newer database aside.
 */
class EntityCatalogDowngradeReconcileTest {
    private val dir: File = Files.createTempDirectory("dbnet").toFile()
    private val target = File(dir, "ha-paneld.db")

    @After fun cleanup() {
        dir.deleteRecursively()
    }

    // A minimal valid SQLite file: the 16-byte header magic followed by a marker payload.
    private val magic = byteArrayOf(
        0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66, 0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00,
    )

    private fun writeDb(file: File, marker: String) = file.writeBytes(magic + marker.toByteArray())
    private fun markerOf(file: File): String {
        val bytes = file.readBytes()
        return String(bytes, magic.size, bytes.size - magic.size)
    }
    private fun write(file: File, content: String) = file.writeText(content) // plain sidecars
    private fun premig(version: Int) = preMigrationBackupFile(target, version)
    private fun superseded(version: Int) = supersededFile(target, version)
    private fun premigFiles() = dir.listFiles()!!.filter { it.name.contains(".$PREMIGRATE_TAG") }

    @Test fun freshInstallNoFileIsNoOp() {
        val result = reconcilePreOpen(target, currentVersion = 11, onDiskVersion = null)
        assertEquals(SchemaReconcileAction.NONE, result.action)
        assertFalse(target.exists())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun sameVersionIsNoOpAndTakesNoBackup() {
        writeDb(target, "v11")
        val result = reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 11)
        assertEquals(SchemaReconcileAction.NONE, result.action)
        assertEquals("v11", markerOf(target))
        assertTrue(premigFiles().isEmpty())
    }

    @Test fun laterNoOpReconcileDoesNotEraseFirstMeaningfulOutcome() {
        val preserved = SchemaReconcile(SchemaReconcileAction.PRESERVED_FRESH, 13, 11)
        val first = retainFirstSchemaReconcile(null, preserved)
        val later = retainFirstSchemaReconcile(
            first,
            SchemaReconcile(SchemaReconcileAction.NONE, 11, 11),
        )

        assertEquals(preserved, later)
    }

    @Test fun upgradeWritesPreMigrationBackupAndLeavesLiveDataForOnUpgrade() {
        writeDb(target, "v12-data")
        val result = reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 12)
        assertEquals(SchemaReconcileAction.BACKED_UP, result.action)
        assertEquals(12, result.fromVersion)
        assertEquals(13, result.toVersion)
        assertEquals("v12-data", markerOf(target)) // live DB untouched; onUpgrade migrates it afterwards
        assertTrue(premig(12).isFile)
        assertEquals("v12-data", markerOf(premig(12)))
    }

    @Test fun upgradeSkipsBackupWhenCheckpointFails() {
        writeDb(target, "v12")
        val result = reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 12, checkpoint = { false })
        assertEquals(SchemaReconcileAction.BACKED_UP, result.action) // the upgrade still proceeds
        assertFalse(premig(12).exists())
    }

    @Test fun upgradeSkipsBackupWhenDatabaseTooLarge() {
        writeDb(target, "0123456789")
        val result = reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 12, maxBackupBytes = 4)
        assertEquals(SchemaReconcileAction.BACKED_UP, result.action)
        assertFalse(premig(12).exists())
    }

    @Test fun upgradePrunesToNewestBackups() {
        writeDb(premig(10), "old10")
        writeDb(premig(11), "old11")
        writeDb(target, "v12")
        reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 12, keepBackups = 2)
        assertTrue(premig(12).isFile)
        assertTrue(premig(11).isFile)
        assertFalse(premig(10).exists())
    }

    @Test fun upgradeReducesRetainedBackupsAndNeverFailsWhenDiskIsTight() {
        writeDb(premig(10), "old10")
        writeDb(premig(11), "old11")
        writeDb(target, "v12")
        // Almost no space free: retention must shrink and the copy is skipped — never a failed upgrade.
        val result = reconcilePreOpen(
            target, currentVersion = 13, onDiskVersion = 12, keepBackups = 2, freeSpace = { 1L },
        )
        assertEquals(SchemaReconcileAction.BACKED_UP, result.action) // upgrade proceeds
        assertFalse(premig(12).exists()) // no room even after pruning -> skipped, not failed
        assertFalse(premig(10).exists()) // old snapshots dropped to try to free room
        assertFalse(premig(11).exists())
    }

    @Test fun upgradeFreesRoomForNewBackupByDroppingOldest() {
        writeDb(premig(10), "old10")
        writeDb(premig(11), "old11")
        writeDb(target, "v12")
        reconcilePreOpen(
            target, currentVersion = 13, onDiskVersion = 12, keepBackups = 2, freeSpace = { 100_000_000L },
        )
        assertTrue(premig(12).isFile) // new copy written
        assertTrue(premig(11).isFile) // newest existing kept (total capped at keepBackups)
        assertFalse(premig(10).exists()) // oldest dropped
    }

    /**
     * A downgrade across additive versions must be a non-event. Setting the database aside would reset
     * the owner's configuration, so this is what stops every future version bump from being a latent
     * config-reset event. Sound only because SchemaAdditivePolicy enforces additivity.
     */
    @Test fun aNewerButAdditivelyCompatibleDatabaseIsOpenedUntouched() {
        writeDb(target, "written-by-a-newer-build")
        val result = reconcilePreOpen(
            target, currentVersion = 12, onDiskVersion = 14, minimumCompatibleVersion = 11,
        )
        assertEquals(SchemaReconcileAction.NONE, result.action)
        assertEquals("the live database must be left in place", "written-by-a-newer-build", markerOf(target))
        assertFalse("nothing may be set aside", superseded(14).exists())
        assertTrue("no health warning: configuration was not reset", result.action == SchemaReconcileAction.NONE)
    }

    @Test fun aNewerDatabaseBelowTheCompatibleBaselineStillFallsBackToTheNet() {
        writeDb(target, "incompatible")
        val result = reconcilePreOpen(
            target, currentVersion = 12, onDiskVersion = 14, minimumCompatibleVersion = 20,
        )
        assertEquals(SchemaReconcileAction.PRESERVED_FRESH, result.action)
        assertTrue("must remain recoverable", superseded(14).isFile)
    }

    /** Even a tolerated open is an unusual moment, and a copy costs a fraction of a percent. */
    @Test fun aToleratedDowngradeStillVaultsConfiguration() {
        writeDb(target, "newer")
        val seen = mutableListOf<File>()
        reconcilePreOpen(
            target, currentVersion = 12, onDiskVersion = 14, minimumCompatibleVersion = 11,
            vaultConfig = { seen += it },
        )
        assertEquals(listOf(target), seen)
    }

    /**
     * Configuration is a fraction of a percent of the bytes and effectively all of the value, so it must
     * be copied out whenever the structure is about to change — including in the two cases where the
     * whole-database snapshot deliberately gives up (file too large, or too little free space), which
     * are precisely the conditions under which loss is most likely.
     */
    @Test fun configurationIsVaultedEvenWhenTheWholeDatabaseSnapshotIsSkipped() {
        fun vaultedDuring(configure: (MutableList<File>) -> SchemaReconcile): List<File> {
            val seen = mutableListOf<File>()
            configure(seen)
            return seen
        }

        writeDb(target, "v12")
        val tooLarge = vaultedDuring { seen ->
            reconcilePreOpen(
                target, currentVersion = 13, onDiskVersion = 12, maxBackupBytes = 1, vaultConfig = { seen += it },
            )
        }
        assertFalse("precondition: the whole-database snapshot was skipped", premig(12).exists())
        assertEquals("configuration must still be vaulted", listOf(target), tooLarge)

        cleanup(); dir.mkdirs(); writeDb(target, "v12")
        val noRoom = vaultedDuring { seen ->
            reconcilePreOpen(
                target, currentVersion = 13, onDiskVersion = 12, freeSpace = { 1L }, vaultConfig = { seen += it },
            )
        }
        assertFalse("precondition: the whole-database snapshot was skipped", premig(12).exists())
        assertEquals("configuration must still be vaulted", listOf(target), noRoom)
    }

    @Test fun configurationIsVaultedBeforeAnOutOfContractDatabaseIsSetAside() {
        writeDb(target, "ancient")
        val seen = mutableListOf<File>()
        reconcilePreOpen(
            target, currentVersion = 13, onDiskVersion = 8, minimumSupportedVersion = 11,
            vaultConfig = { seen += it },
        )
        assertEquals("must run while the database is still readable", listOf(target), seen)
    }

    @Test fun anUnchangedSchemaDoesNotRewriteTheVault() {
        writeDb(target, "v13")
        val seen = mutableListOf<File>()
        reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 13, vaultConfig = { seen += it })
        assertTrue("no structural change means nothing to protect against", seen.isEmpty())
    }

    /**
     * A structure older than the supported floor must be preserved and replaced by a fresh store, never
     * handed to onUpgrade: its migration steps no longer exist, and a throw inside onUpgrade aborts the
     * open and takes configuration down with it.
     */
    @Test fun belowTheSupportedFloorThePreviousDatabaseIsPreservedAndAFreshOneStarts() {
        writeDb(target, "ancient")
        write(File(target.path + "-wal"), "wal")
        val result = reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 8, minimumSupportedVersion = 11)
        assertEquals(SchemaReconcileAction.PRESERVED_FRESH, result.action)
        assertEquals(8, result.fromVersion)
        assertEquals(13, result.toVersion)
        assertFalse("live database must be moved aside so a fresh one is created", target.exists())
        assertTrue("the old database must be recoverable", superseded(8).isFile)
        assertEquals("ancient", markerOf(superseded(8)))
        assertFalse("sidecars must not be left pointing at a removed database", File(target.path + "-wal").exists())
    }

    @Test fun atTheSupportedFloorTheOrdinaryUpgradePathStillRuns() {
        writeDb(target, "floor")
        val result = reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 11, minimumSupportedVersion = 11)
        assertEquals(SchemaReconcileAction.BACKED_UP, result.action)
        assertEquals("floor", markerOf(target)) // left live for onUpgrade
        assertTrue(premig(11).isFile)
    }

    @Test fun downgradeRestoresNewestCompatibleBackupAndPreservesNewer() {
        writeDb(premig(10), "snap10")
        writeDb(premig(11), "snap11")
        writeDb(premig(12), "snap12") // newer than this build; must not be chosen
        writeDb(target, "v13-live")
        val result = reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 13, minimumCompatibleVersion = 20)
        assertEquals(SchemaReconcileAction.RESTORED, result.action)
        assertEquals(11, result.restoredVersion)
        assertEquals(13, result.fromVersion)
        assertEquals("snap11", markerOf(target)) // opens the v11-structured snapshot this build understands
        assertTrue(superseded(13).isFile)
        assertEquals("v13-live", markerOf(superseded(13))) // newer database preserved for recovery
    }

    @Test fun downgradeWithNoCompatibleBackupPreservesNewerAndStartsFresh() {
        writeDb(premig(12), "snap12") // only a newer-than-current snapshot exists
        writeDb(target, "v13-live")
        val result = reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 13, minimumCompatibleVersion = 20)
        assertEquals(SchemaReconcileAction.PRESERVED_FRESH, result.action)
        assertFalse(target.exists())
        assertTrue(superseded(13).isFile)
        assertEquals("v13-live", markerOf(superseded(13)))
        assertTrue(premig(12).isFile)
    }

    @Test fun downgradeSkipsCorruptSnapshotAndPreservesFresh() {
        premig(11).writeText("not-a-sqlite-database") // no SQLite header
        writeDb(target, "v13-live")
        val result = reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 13, minimumCompatibleVersion = 20)
        assertEquals(SchemaReconcileAction.PRESERVED_FRESH, result.action) // corrupt snapshot never restored
        assertFalse(target.exists())
        assertTrue(superseded(13).isFile)
        assertEquals("v13-live", markerOf(superseded(13)))
    }

    @Test fun downgradePreservesDatabaseSidecarsWithTheNewerDatabase() {
        writeDb(premig(11), "snap11")
        writeDb(target, "v13")
        write(File(target.path + "-wal"), "wal")
        write(File(target.path + "-shm"), "shm")
        reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 13, minimumCompatibleVersion = 20)
        assertEquals("snap11", markerOf(target))
        assertFalse(File(target.path + "-wal").exists())
        assertFalse(File(target.path + "-shm").exists())
        assertEquals("wal", File(superseded(13).path + "-wal").readText())
        assertEquals("shm", File(superseded(13).path + "-shm").readText())
    }

    @Test fun failedCheckpointStillPreservesDatabaseSidecars() {
        writeDb(premig(11), "snap11")
        writeDb(target, "v13")
        write(File(target.path + "-wal"), "wal")
        val result = reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 13, minimumCompatibleVersion = 20, checkpoint = { false })
        assertEquals(SchemaReconcileAction.RESTORED, result.action)
        assertEquals("v13", markerOf(superseded(13)))
        assertEquals("wal", File(superseded(13).path + "-wal").readText())
    }

    @Test fun supersededPreservationIsBoundedToNewest() {
        writeDb(superseded(12), "old-superseded")
        writeDb(premig(11), "snap11")
        writeDb(target, "v13")
        reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 13, minimumCompatibleVersion = 20)
        assertTrue(superseded(13).isFile) // newest kept
        assertFalse(superseded(12).exists()) // older pruned
    }

    @Test fun downgradePruningNeverDeletesJustMovedLiveData() {
        writeDb(superseded(14), "stale-v14") // stale HIGHER-version recovery copy from an earlier downgrade
        writeDb(premig(11), "snap11")
        writeDb(target, "v13-live") // current live DB, lower version than the stale aside
        val result = reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 13, minimumCompatibleVersion = 20)
        assertEquals(SchemaReconcileAction.RESTORED, result.action)
        assertTrue(superseded(13).isFile)
        assertEquals("v13-live", markerOf(superseded(13))) // current live data preserved...
        assertFalse(superseded(14).exists()) // ...and the stale higher-version copy pruned, not the live one
        assertEquals("snap11", markerOf(target))
    }

    @Test fun downgradeCheckpointsTooNewDbBeforePreserving() {
        writeDb(premig(11), "snap11")
        writeDb(target, "v13")
        val checkpointed = mutableListOf<String>()
        reconcilePreOpen(
            target, currentVersion = 11, onDiskVersion = 13, minimumCompatibleVersion = 20,
            checkpoint = { f -> checkpointed.add(f.name); true },
        )
        assertTrue(checkpointed.contains(target.name))
    }

    private companion object {
        const val PREMIGRATE_TAG = "premigrate"
    }
}
