package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class GuardDbTerminalRetirementTest {
    @get:Rule val temporary = TemporaryFolder()

    private val intent = GuardDbTerminalRetirement(
        state = GuardDbTerminalRetirementState.INTENT,
        session = "1".repeat(64),
        finalGeneration = 20L,
        bootNonce = "2".repeat(64),
        aSha256 = "a".repeat(64),
        aVersionCode = 568L,
        aSchema = 14,
        outcome = GuardDbMaintenanceProtocol.Outcome.CANARY_PASSED,
        evidenceSha256 = "e".repeat(64),
    )

    @Test fun `retirement record is exact identity bound and checksum protected`() {
        val encoded = encodeGuardDbTerminalRetirement(intent)
        assertEquals(intent, parseGuardDbTerminalRetirement(encoded))
        assertNull(parseGuardDbTerminalRetirement(encoded.toString(Charsets.US_ASCII)
            .replace("FINAL_GENERATION 20", "FINAL_GENERATION 020").toByteArray()))
        assertNull(parseGuardDbTerminalRetirement(encoded.toString(Charsets.US_ASCII)
            .replace("A ${"a".repeat(64)}", "A ${"b".repeat(64)}").toByteArray()))
        assertNull(parseGuardDbTerminalRetirement(encoded.toString(Charsets.US_ASCII)
            .replace("OUTCOME CANARY_PASSED", "OUTCOME AMBIGUOUS").toByteArray()))
        assertNull(parseGuardDbTerminalRetirement(encoded + "TRAILING\n".toByteArray()))
    }

    @Test fun `store moves one exact intent forward to completion but never replaces it`() {
        val directory = temporary.newFolder("retirement")
        val store = GuardDbTerminalRetirementStore(
            directory,
            syncDirectory = { true },
            validateFile = { it.isFile },
        )
        assertTrue(store.load() is GuardDbTerminalRetirementLoad.Absent)
        assertTrue(store.writeIntent(intent))
        assertEquals(intent, (store.load() as GuardDbTerminalRetirementLoad.Valid).retirement)
        assertTrue(store.writeIntent(intent))
        assertFalse(store.writeIntent(intent.copy(evidenceSha256 = "f".repeat(64))))
        assertTrue(store.markComplete(intent))
        val completed = (store.load() as GuardDbTerminalRetirementLoad.Valid).retirement
        assertEquals(GuardDbTerminalRetirementState.COMPLETE, completed.state)
        assertTrue(store.markComplete(intent))
        assertFalse(store.writeIntent(intent))
        val next = intent.copy(session = "9".repeat(64), evidenceSha256 = "9".repeat(64))
        assertTrue(store.writeIntent(next))
        assertEquals(next, (store.load() as GuardDbTerminalRetirementLoad.Valid).retirement)
        assertTrue(store.markRetryable(next))
        assertEquals(
            GuardDbTerminalRetirementState.RETRYABLE,
            (store.load() as GuardDbTerminalRetirementLoad.Valid).retirement.state,
        )
        assertTrue(store.writeIntent(next))
        assertEquals(next, (store.load() as GuardDbTerminalRetirementLoad.Valid).retirement)
    }

    @Test fun `corruption and failed parent sync never authorize a new retirement`() {
        val directory = temporary.newFolder("retirement-corrupt")
        val store = GuardDbTerminalRetirementStore(
            directory,
            syncDirectory = { true },
            validateFile = { it.isFile },
        )
        assertTrue(store.writeIntent(intent))
        directory.resolve("guard-db-terminal-retirement.v1").appendText("corrupt")
        assertTrue(store.load() is GuardDbTerminalRetirementLoad.Corrupt)
        assertFalse(store.writeIntent(intent))

        val unsynced = GuardDbTerminalRetirementStore(
            temporary.newFolder("retirement-unsynced"),
            syncDirectory = { false },
            validateFile = { it.isFile },
        )
        assertFalse(unsynced.writeIntent(intent))
    }

    @Test fun `only unresolved intent and corrupt retirement state fence ordinary mutations`() {
        assertFalse(terminalRetirementBlocksOrdinaryMutations(GuardDbTerminalRetirementLoad.Absent))
        assertTrue(terminalRetirementBlocksOrdinaryMutations(GuardDbTerminalRetirementLoad.Corrupt))
        assertTrue(terminalRetirementBlocksOrdinaryMutations(GuardDbTerminalRetirementLoad.Valid(intent)))
        assertFalse(terminalRetirementBlocksOrdinaryMutations(
            GuardDbTerminalRetirementLoad.Valid(intent.copy(state = GuardDbTerminalRetirementState.RETRYABLE)),
        ))
        assertFalse(terminalRetirementBlocksOrdinaryMutations(
            GuardDbTerminalRetirementLoad.Valid(intent.copy(state = GuardDbTerminalRetirementState.COMPLETE)),
        ))
    }

    @Test fun `already visible completion must fsync its parent again before confirmation`() {
        val directory = temporary.newFolder("retirement-redurable")
        var syncCalls = 0
        val store = GuardDbTerminalRetirementStore(
            directory,
            syncDirectory = {
                syncCalls += 1
                syncCalls != 2
            },
            validateFile = { it.isFile },
        )
        assertTrue(store.writeIntent(intent))
        assertFalse(store.markComplete(intent))
        assertEquals(2, syncCalls)
        assertEquals(
            GuardDbTerminalRetirementState.COMPLETE,
            (store.load() as GuardDbTerminalRetirementLoad.Valid).retirement.state,
        )
        assertTrue(store.markComplete(intent))
        assertEquals(3, syncCalls)
    }

    @Test fun `dangling retirement record is corrupt and cannot authorize replacement`() {
        val directory = temporary.newFolder("retirement-dangling")
        val record = directory.resolve("guard-db-terminal-retirement.v1").toPath()
        Files.createSymbolicLink(record, directory.resolve("missing-retirement").toPath())
        val store = GuardDbTerminalRetirementStore(
            directory,
            syncDirectory = { true },
            validateFile = { it.isFile },
        )

        assertTrue(store.load() is GuardDbTerminalRetirementLoad.Corrupt)
        assertFalse(store.writeIntent(intent))
        assertTrue(Files.isSymbolicLink(record))
    }

    @Test fun `foreign retirement pending entry is never deleted or followed`() {
        val directory = temporary.newFolder("retirement-pending")
        val pending = directory.resolve(".guard-db-terminal-retirement.v1.pending").toPath()
        Files.createSymbolicLink(pending, directory.resolve("missing-pending-retirement").toPath())
        val store = GuardDbTerminalRetirementStore(
            directory,
            syncDirectory = { true },
            validateFile = { it.isFile },
        )

        assertFalse(store.writeIntent(intent))
        assertTrue(Files.isSymbolicLink(pending))
        assertTrue(store.load() is GuardDbTerminalRetirementLoad.Absent)
    }
}
