package io.github.maxlyth.hapaneld.dashboard

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseRestoreTransactionTest {
    private val session = "1".repeat(64)
    private val guard = DatabaseRestoreGuardBinding(session, 16L)

    @Test fun `every durable cut either resumes or safely restarts before canonical mutation`() {
        listOf(false, true).forEach { withPriorAside ->
            DatabaseRestoreCut.values().forEach { selected ->
                withFiles { directory, target, staged ->
                    val label = "$selected priorAside=$withPriorAside"
                    if (withPriorAside) supersededFile(target, 15).writeText("prior-schema15")
                    var fired = false
                    val first = DatabaseRestoreTransaction(target, guard) { cut ->
                        if (!fired && cut == selected) {
                            fired = true
                            throw SimulatedProcessDeath
                        }
                    }
                    try {
                        first.restore(staged, sourceSchema = 15, stagedSchema = 14, checkpoint = { true })
                        throw AssertionError("cut $label was not reached")
                    } catch (_: SimulatedProcessDeath) {
                        // New process below owns all reconciliation.
                    }
                    assertTrue("cut $label must fire", fired)

                    val restarted = DatabaseRestoreTransaction(target, guard)
                    val initial = restarted.reconcile()
                    val result = if (initial is DatabaseRestoreResult.Absent) {
                        // Before PREPARED publication, the exact orphan copy is discarded because the
                        // canonical source was never moved. Retrying creates a fresh bound transaction.
                        restarted.restore(staged, 15, 14, checkpoint = { true })
                    } else initial
                    val restored = result as? DatabaseRestoreResult.Restored
                        ?: throw AssertionError("cut $label settled as $result")
                    assertEquals(label, SchemaReconcileAction.RESTORED, restored.reconcile.action)
                    assertEquals(label, 15, restored.reconcile.fromVersion)
                    assertEquals(label, 14, restored.reconcile.toVersion)
                    assertEquals(label, guard, restored.record.guard)
                    assertEquals(label, "schema14", target.readText())
                    assertEquals(label, "schema15", supersededFile(target, 15).readText())
                    listOf("-wal", "-shm", "-journal", ".tmp").forEach { suffix ->
                        assertFalse(label, File(target.path + suffix).exists())
                        assertFalse(label, File(supersededFile(target, 15).path + suffix).exists())
                    }
                    assertEquals(
                        label,
                        listOf(supersededFile(target, 15).name),
                        directory.listFiles()!!.filter { it.name.contains(".superseded") }.map { it.name },
                    )
                    assertTrue(label, File(directory, ".ha-paneld.db.restore.v1").isFile)
                }
            }
        }
    }

    @Test fun `restart after completed rename retains durable RESTORED proof`() = withFiles { _, target, staged ->
        val result = DatabaseRestoreTransaction(
            target,
            guard,
            ownedStableSidecar = { file, requireEmpty -> !requireEmpty || file.length() == 0L },
        )
            .restore(staged, 15, 14, checkpoint = { true })
        assertTrue(result is DatabaseRestoreResult.Restored)

        val restarted = DatabaseRestoreTransaction(target, guard).reconcile()
            as DatabaseRestoreResult.Restored
        assertEquals(SchemaReconcileAction.RESTORED, restarted.reconcile.action)
        assertEquals(15, restarted.reconcile.fromVersion)
        assertEquals(14, restarted.reconcile.restoredVersion)
        assertEquals(16L, restarted.record.guard?.generation)
    }

    @Test fun `sidecar authority rejects foreign hardlinked and changing identities`() {
        val exact = DatabaseRestoreSidecarIdentity(
            regular = true,
            device = 1L,
            inode = 2L,
            uid = 1000,
            gid = 1000,
            links = 1L,
            bytes = 0L,
            sha256 = "a".repeat(64),
        )
        assertTrue(exactOwnedStableDatabaseSidecar(exact, exact, 1000, requireEmpty = true))
        assertFalse(exactOwnedStableDatabaseSidecar(exact.copy(uid = 0), exact.copy(uid = 0), 1000, true))
        assertFalse(exactOwnedStableDatabaseSidecar(exact.copy(gid = 0), exact.copy(gid = 0), 1000, true))
        assertFalse(exactOwnedStableDatabaseSidecar(exact.copy(links = 2), exact.copy(links = 2), 1000, true))
        assertFalse(exactOwnedStableDatabaseSidecar(exact, exact.copy(sha256 = "b".repeat(64)), 1000, true))
        assertFalse(exactOwnedStableDatabaseSidecar(exact.copy(bytes = 1), exact.copy(bytes = 1), 1000, true))
        assertFalse(exactOwnedStableDatabaseSidecar(exact.copy(regular = false), exact.copy(regular = false), 1000, false))
    }

    @Test fun `unproved sidecar is retained and holds before source rename`() = withFiles { _, target, staged ->
        val wal = File(target.path + "-wal").apply { writeBytes(byteArrayOf()) }
        val result = DatabaseRestoreTransaction(
            target,
            guard,
            ownedStableSidecar = { _, _ -> false },
        ).restore(staged, 15, 14, checkpoint = { true })

        assertTrue(result is DatabaseRestoreResult.Hold)
        assertTrue(wal.exists())
        assertEquals("schema15", target.readText())
        assertFalse(supersededFile(target, 15).exists())
    }

    @Test fun `even an empty rollback journal remains fail closed`() = withFiles { _, target, staged ->
        val journal = File(target.path + "-journal").apply { writeBytes(byteArrayOf()) }

        val result = DatabaseRestoreTransaction(
            target,
            guard,
            ownedStableSidecar = { _, _ -> true },
        ).restore(staged, 15, 14, checkpoint = { true })

        assertTrue(result is DatabaseRestoreResult.Hold)
        assertTrue(journal.exists())
        assertEquals("schema15", target.readText())
        assertFalse(supersededFile(target, 15).exists())
    }

    @Test fun `ordinary receipt is consumed after successful owned WAL open`() = withFiles { directory, target, staged ->
        val sidecarChecks = mutableListOf<Pair<String, Boolean>>()
        val transaction = DatabaseRestoreTransaction(
            target,
            guard = null,
            ownedStableSidecar = { file, requireEmpty ->
                sidecarChecks += file.name to requireEmpty
                !requireEmpty || file.length() == 0L
            },
        )
        assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
        assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
        File(target.path + "-wal").writeBytes(byteArrayOf())
        File(target.path + "-shm").writeText("owned-open-shm")

        assertTrue(transaction.consumeOrdinaryRestored())
        assertEquals(
            listOf("${target.name}-wal" to true, "${target.name}-shm" to false),
            sidecarChecks,
        )
        assertFalse(File(directory, ".ha-paneld.db.restore.v1").exists())
        assertTrue(DatabaseRestoreTransaction(target).reconcile() is DatabaseRestoreResult.Absent)
    }

    @Test fun `ordinary receipt rejects a nonempty open WAL`() = withFiles { directory, target, staged ->
        val transaction = DatabaseRestoreTransaction(
            target,
            guard = null,
            ownedStableSidecar = { file, requireEmpty -> !requireEmpty || file.length() == 0L },
        )
        assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
        val wal = File(target.path + "-wal").apply { writeText("live-frame") }

        assertFalse(transaction.consumeOrdinaryRestored())
        assertEquals("live-frame", wal.readText())
        assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
        assertEquals("schema14", target.readText())
        assertEquals("schema15", supersededFile(target, 15).readText())
    }

    @Test fun `ordinary receipt rejects an unproved empty open WAL`() = withFiles { directory, target, staged ->
        val transaction = DatabaseRestoreTransaction(
            target,
            guard = null,
            ownedStableSidecar = { _, _ -> false },
        )
        assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
        val wal = File(target.path + "-wal").apply { writeBytes(byteArrayOf()) }

        assertFalse(transaction.consumeOrdinaryRestored())
        assertTrue(wal.exists())
        assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
    }

    @Test fun `ordinary receipt retains proof when restored topology drifts`() = withFiles { directory, target, staged ->
        val transaction = DatabaseRestoreTransaction(target, guard = null)
        assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
        target.writeText("changed-after-open")

        assertFalse(transaction.consumeOrdinaryRestored())
        assertEquals("changed-after-open", target.readText())
        assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
        assertEquals("schema15", supersededFile(target, 15).readText())
    }

    @Test fun `ordinary consumer never clears a Guard receipt`() = withFiles { directory, target, staged ->
        val transaction = DatabaseRestoreTransaction(
            target,
            guard,
            ownedStableSidecar = { file, requireEmpty -> !requireEmpty || file.length() == 0L },
        )
        assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
        File(target.path + "-wal").writeBytes(byteArrayOf())
        File(target.path + "-shm").writeText("owned-open-shm")

        assertTrue(transaction.consumeOrdinaryRestored())
        assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
        assertTrue(transaction.reconcile() is DatabaseRestoreResult.Hold)
        assertFalse(transaction.clearRestored("2".repeat(64)))
        assertTrue(transaction.clearRestored(session))
        assertFalse(File(directory, ".ha-paneld.db.restore.v1").exists())
    }

    @Test fun `checkpoint or live sidecar failure mutates no canonical file`() = withFiles { _, target, staged ->
        val failedCheckpoint = DatabaseRestoreTransaction(target, guard)
            .restore(staged, 15, 14, checkpoint = { false })
        assertTrue(failedCheckpoint is DatabaseRestoreResult.Hold)
        assertEquals("schema15", target.readText())
        assertFalse(supersededFile(target, 15).exists())

        File(target.path + "-wal").writeText("committed-frame")
        val liveWal = DatabaseRestoreTransaction(target, guard)
            .restore(staged, 15, 14, checkpoint = { true })
        assertTrue(liveWal is DatabaseRestoreResult.Hold)
        assertEquals("schema15", target.readText())
        assertEquals("committed-frame", File(target.path + "-wal").readText())
        assertFalse(supersededFile(target, 15).exists())
    }

    @Test fun `zero WAL and regular SHM are normalized before main-only restore`() = withFiles { _, target, staged ->
        File(target.path + "-wal").writeBytes(byteArrayOf())
        File(target.path + "-shm").writeText("recognized-app-shm")

        val result = DatabaseRestoreTransaction(
            target,
            guard,
            ownedStableSidecar = { file, requireEmpty -> !requireEmpty || file.length() == 0L },
        )
            .restore(staged, 15, 14, checkpoint = { true })

        assertTrue(result is DatabaseRestoreResult.Restored)
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            assertFalse(File(target.path + suffix).exists())
            assertFalse(File(supersededFile(target, 15).path + suffix).exists())
        }
    }

    @Test fun `unknown partial and foreign topologies hold`() = withFiles { directory, target, staged ->
        File(directory, ".ha-paneld.db.restore.v1").writeText("corrupt")
        assertTrue(DatabaseRestoreTransaction(target, guard).reconcile() is DatabaseRestoreResult.Hold)
        File(directory, ".ha-paneld.db.restore.v1").delete()

        supersededFile(target, 16).writeText("foreign")
        val result = DatabaseRestoreTransaction(target, guard)
            .restore(staged, 15, 14, checkpoint = { true })
        assertTrue(result is DatabaseRestoreResult.Hold)
        assertEquals("schema15", target.readText())
        assertEquals("foreign", supersededFile(target, 16).readText())
    }

    @Test fun `malformed companion and multiple superseded topologies remain fail closed`() {
        fun assertHeld(label: String, arrange: (File, File, File) -> Unit) =
            withFiles { directory, target, staged ->
                arrange(directory, target, staged)
                val before = directory.listFiles()!!.associate { it.name to it.readSafeBytes() }

                val result = DatabaseRestoreTransaction(target, guard)
                    .restore(staged, 15, 14, checkpoint = { true })

                assertTrue(label, result is DatabaseRestoreResult.Hold)
                assertEquals(label, "schema15", target.readText())
                assertEquals(label, before, directory.listFiles()!!.associate { it.name to it.readSafeBytes() })
            }

        assertHeld("expected destination is a directory") { _, target, _ ->
            assertTrue(supersededFile(target, 15).mkdir())
        }
        assertHeld("expected destination is a symbolic link") { _, target, staged ->
            Files.createSymbolicLink(supersededFile(target, 15).toPath(), staged.toPath())
        }
        assertHeld("expected destination is a hard link to the canonical database") { _, target, _ ->
            Files.createLink(supersededFile(target, 15).toPath(), target.toPath())
        }
        assertHeld("expected destination is empty") { _, target, _ ->
            supersededFile(target, 15).writeBytes(byteArrayOf())
        }
        assertHeld("expected destination has a SQLite companion") { _, target, _ ->
            supersededFile(target, 15).writeText("prior")
            File(supersededFile(target, 15).path + "-wal").writeText("retained-wal")
        }
        assertHeld("expected and foreign destinations both exist") { _, target, _ ->
            supersededFile(target, 15).writeText("prior")
            supersededFile(target, 16).writeText("foreign")
        }
        assertHeld("malformed superseded name exists") { directory, target, _ ->
            File(directory, "${target.name}.vbad.superseded").writeText("malformed")
        }
    }

    @Test fun `fixed restore entries fail closed for corrupt directory live link and dangling link`() =
        withFiles { directory, target, staged ->
            val names = listOf(
                ".ha-paneld.db.restore.v1",
                ".ha-paneld.db.restore.v1.tmp",
                ".ha-paneld.db.restore.prepared.v1",
            )
            assertTrue(DatabaseRestoreTransaction(target, guard).reconcile() is DatabaseRestoreResult.Absent)
            names.forEach { name ->
                val entry = File(directory, name)
                val cases = buildList<Pair<String, () -> Unit>> {
                    if (name != ".ha-paneld.db.restore.prepared.v1") {
                        add("corrupt" to { entry.writeText("corrupt") })
                    }
                    add("directory" to { assertTrue(entry.mkdir()) })
                    add("live-link" to { Files.createSymbolicLink(entry.toPath(), staged.toPath()) })
                    add("dangling-link" to {
                        Files.createSymbolicLink(entry.toPath(), File(directory, "missing-target").toPath())
                    })
                }
                cases.forEach { (case, create) ->
                    create()
                    val transaction = DatabaseRestoreTransaction(target, guard)
                    assertTrue("$name $case must hold", transaction.reconcile() is DatabaseRestoreResult.Hold)
                    assertFalse("$name $case must block terminal clear", transaction.clearRestored(session))
                    Files.delete(entry.toPath())
                }
            }
            val prepared = File(directory, ".ha-paneld.db.restore.prepared.v1")
            prepared.writeText("orphan-before-publication")
            assertTrue(DatabaseRestoreTransaction(target, guard).reconcile() is DatabaseRestoreResult.Absent)
            assertFalse(prepared.exists())
        }

    @Test fun `record encoding binds exact target files hashes schemas and Guard generation`() = withFiles {
            directory, target, _ ->
        val record = DatabaseRestoreRecord(
            DatabaseRestoreState.SOURCE_ASIDE,
            directory.absoluteFile.normalize().path,
            target.name,
            15,
            4096,
            "a".repeat(64),
            14,
            2048,
            "b".repeat(64),
            supersededFile(target, 15).name,
            guard,
        )
        val encoded = encodeDatabaseRestoreRecord(record)
        assertEquals(record, parseDatabaseRestoreRecord(encoded))
        assertEquals(null, parseDatabaseRestoreRecord(encoded.copyOf().also { it[20] = 'X'.code.toByte() }))
        assertEquals(null, parseDatabaseRestoreRecord(
            encoded.toString(Charsets.US_ASCII).replace("GUARD $session 16", "GUARD $session 14")
                .toByteArray(Charsets.US_ASCII),
        ))
    }

    private inline fun withFiles(block: (File, File, File) -> Unit) {
        val directory = Files.createTempDirectory("guard-db-restore-").toFile()
        try {
            val target = File(directory, "ha-paneld.db").apply { writeText("schema15") }
            val staged = File(directory, "validated-v14").apply { writeText("schema14") }
            block(directory, target, staged)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun File.readSafeBytes(): List<Byte>? =
        takeIf { Files.isRegularFile(toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
            ?.readBytes()?.toList()

    private data object SimulatedProcessDeath : Error()
}
