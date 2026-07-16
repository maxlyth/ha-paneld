package io.github.maxlyth.hapaneld.util

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableRecoveryMarkerTest {
    @Test fun failedRecoveryKeepsMarkerForTheNextProcess() {
        val dir = Files.createTempDirectory("navbar-recovery-test").toFile()
        try {
            val marker = DurableRecoveryMarker(dir.resolve("pending"))
            assertTrue(marker.arm())

            assertFalse(marker.recoverIfArmed { false })
            assertTrue(marker.isArmed())
            assertTrue(DurableRecoveryMarker(dir.resolve("pending")).isArmed())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun successfulRecoveryClearsMarkerDurably() {
        val dir = Files.createTempDirectory("navbar-recovery-test").toFile()
        try {
            val file = dir.resolve("pending")
            val marker = DurableRecoveryMarker(file)
            assertTrue(marker.arm())
            assertTrue(marker.recoverIfArmed { true })
            assertFalse(marker.isArmed())
            assertTrue(DurableRecoveryMarker(file).recoverIfArmed { error("already resolved") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
