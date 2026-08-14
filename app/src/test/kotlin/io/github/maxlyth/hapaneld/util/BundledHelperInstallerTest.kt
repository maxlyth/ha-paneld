package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledHelperInstallerTest {
    @Test fun `an already-current helper is admitted without probing root at all`() {
        var probes = 0
        val result = bundledHelperAdmission(alreadyCurrent = true, rootObserved = { probes++; true })
        assertEquals(BundledHelperInstaller.Result.ALREADY_CURRENT, result)
        assertEquals(0, probes)
    }

    @Test fun `a panel with no observed root is skipped, which is the helper-only protection`() {
        // A helper-only panel is exactly one where the app has no su of its own, so observing root is
        // what keeps this migration away from the daemon such a panel depends on.
        assertEquals(
            BundledHelperInstaller.Result.SKIPPED,
            bundledHelperAdmission(alreadyCurrent = false, rootObserved = { false }),
        )
    }

    @Test fun `observed root admits the migration, whatever a device profile happens to declare`() {
        // The regression this pins: `app_can_su` is an attempt-order hint written against the firmware
        // the profile author saw. An owner who flashes a rooted build keeps that stock-derived profile,
        // and vetoing on it denied the helper to a panel that plainly had root. Admission takes no
        // profile argument at all, so the hint cannot re-enter as a veto by being passed in.
        assertNull(bundledHelperAdmission(alreadyCurrent = false, rootObserved = { true }))
    }

    @Test fun `ABI selection follows supported runtime preference`() {
        assertEquals("hapaneld-helper-arm64", helperAssetName(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("hapaneld-helper-arm", helperAssetName(listOf("armeabi-v7a")))
        assertNull(helperAssetName(listOf("x86_64")))
    }

    @Test fun `Gradle and portable helper identity implementations agree`() {
        val sourceId = listOf(java.io.File("../helper/source-id.sh"), java.io.File("helper/source-id.sh"))
            .first { it.isFile }
        val process = ProcessBuilder("bash", sourceId.absolutePath).redirectErrorStream(true).start()
        val shellId = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, process.waitFor())
        assertEquals(io.github.maxlyth.hapaneld.BuildConfig.HELPER_BUILD_ID, shellId)
    }

    @Test fun `install command is fixed root-owned atomic staging with hash verification`() {
        val hash = "a".repeat(64)
        val command = bundledHelperInstallCommand(hash)
        assertTrue(command.contains("rm -f /data/local/.hapaneld-helper.new"))
        assertTrue(command.contains("[ \"\$actual\" = \"$hash\" ]"))
        assertTrue(command.contains("chown 0:0 /data/local/.hapaneld-helper.new"))
        assertTrue(command.contains("cp -p /data/local/hapaneld-helper /data/local/.hapaneld-helper.previous"))
        assertTrue(command.contains("mv -f /data/local/.hapaneld-helper.new /data/local/hapaneld-helper"))
        assertFalse(command.contains("/data/local/tmp"))
    }

    @Test fun `recovery restores the prior ephemeral or durable helper`() {
        val commands = bundledHelperRecoveryCommands()
        assertEquals(4, commands.size)
        assertTrue(commands[0].contains("mv -f /data/local/.hapaneld-helper.previous /data/local/hapaneld-helper"))
        assertTrue(commands[0].indexOf("[ -f /data/local/.hapaneld-helper.previous ]") < commands[0].indexOf("pkill"))
        assertTrue(commands[1].contains("start hapaneld_helper"))
        assertTrue(commands[2].contains("/data/adb/hapaneld/hapaneld-helper"))
        assertTrue(commands[2].indexOf("[ -x /data/adb/hapaneld/hapaneld-helper ]") < commands[2].indexOf("pkill"))
        assertTrue(commands[3].contains("/system/bin/hapaneld-helper"))
        assertTrue(commands[3].indexOf("[ -x /system/bin/hapaneld-helper ]") < commands[3].indexOf("pkill"))
        assertTrue(commands.all { !it.contains("/data/local/tmp") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `install command rejects non digest interpolation`() {
        bundledHelperInstallCommand("a; reboot")
    }
}
