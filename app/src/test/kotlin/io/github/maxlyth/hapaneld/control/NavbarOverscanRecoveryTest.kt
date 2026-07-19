package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.util.DurableRecoveryMarker
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavbarOverscanRecoveryTest {
    @Test fun positiveCropPublishesRecoveryEvidenceBeforeMutation() = withRecovery { file, recovery, commands ->
        commands.runResult = { file.isFile }

        assertTrue(recovery.applyBottom(56))
        assertTrue(file.isFile)
        assertTrue(commands.runs.contains("wm overscan 0,0,0,56"))
    }

    @Test fun resetNeedsAnAffirmativeZeroDisplayReadbackBeforeClearing() = withRecovery { file, recovery, commands ->
        assertTrue(DurableRecoveryMarker(file).arm())
        commands.output = """
            DisplayInfo{"Built-in", app 800 x 424, real 800 x 480, overscan (0,0,0,56)}
            mOverscanScreen=(0,0) 800x424
        """.trimIndent()

        assertFalse(recovery.resetAndVerify())
        assertTrue(file.isFile)

        commands.output = """
            DisplayInfo{"Built-in", app 800 x 480, real 800 x 480}
            mOverscanScreen=(0,0) 800x480
        """.trimIndent()
        assertTrue(recovery.resetAndVerify())
        assertFalse(file.exists())
    }

    @Test fun malformedOrMissingReadbackRemainsRetryable() = withRecovery { file, recovery, commands ->
        assertTrue(DurableRecoveryMarker(file).arm())
        commands.output = "DisplayInfo unavailable"

        assertFalse(recovery.resetAndVerify())
        assertTrue(file.isFile)
    }

    @Test fun explicitZeroOverscanIsAlsoAccepted() {
        assertTrue(
            NavbarOverscanRecovery.displayOverscanIsZero(
                """
                    DisplayInfo{"Built-in", app 800 x 480, real 800 x 480, overscan (0,0,0,0)}
                    mOverscanScreen=(0,0) 800x480
                """.trimIndent(),
            ),
        )
        assertFalse(
            NavbarOverscanRecovery.displayOverscanIsZero(
                """
                    DisplayInfo{"Built-in", app 800 x 479, real 800 x 480, overscan (0,0,0,1)}
                    mOverscanScreen=(0,0) 800x479
                """.trimIndent(),
            ),
        )
    }

    @Test fun omittedOverscanFieldWithoutMatchingPolicyGeometryIsNotProof() {
        assertFalse(
            NavbarOverscanRecovery.displayOverscanIsZero(
                "DisplayInfo{\"Built-in\", app 800 x 480, real 800 x 480}",
            ),
        )
    }

    @Test fun absentOwnershipNeedsNoRootCommand() = withRecovery { _, recovery, commands ->
        assertTrue(recovery.resetAndVerify())
        assertTrue(commands.outputs.isEmpty())
    }

    private fun withRecovery(
        block: (file: java.io.File, recovery: NavbarOverscanRecovery, commands: Commands) -> Unit,
    ) {
        val directory = Files.createTempDirectory("navbar-overscan-test").toFile()
        try {
            val file = directory.resolve("overscan.pending")
            val commands = Commands()
            val recovery = NavbarOverscanRecovery(
                marker = DurableRecoveryMarker(file),
                run = { command -> commands.runs += command; commands.runResult(command) },
                runOutput = { command -> commands.outputs += command; commands.output },
            )
            block(file, recovery, commands)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class Commands {
        val runs = mutableListOf<String>()
        val outputs = mutableListOf<String>()
        var runResult: (String) -> Boolean = { true }
        var output: String? = null
    }
}
