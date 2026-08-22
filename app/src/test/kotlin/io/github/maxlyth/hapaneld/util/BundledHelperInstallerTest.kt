package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledHelperInstallerTest {
    @Test fun `an already-current helper is admitted without probing root at all`() {
        var probes = 0
        val result = bundledHelperAdmission(
            bundledBuildMatches = true,
            companionSupported = true,
            guardSupported = true,
            rootObserved = { probes++; true },
        )
        assertEquals(BundledHelperInstaller.Result.ALREADY_CURRENT, result)
        assertEquals(0, probes)
    }

    @Test fun `equal build without canonical Guard support requires reprovision without probing root`() {
        var probes = 0
        val result = bundledHelperAdmission(
            bundledBuildMatches = true,
            companionSupported = true,
            guardSupported = false,
            rootObserved = { probes++; true },
        )
        assertEquals(BundledHelperInstaller.Result.REPROVISION_REQUIRED, result)
        assertEquals(0, probes)
        assertFalse(bundledHelperIsCanonical(
            bundledBuildMatches = true,
            companionSupported = true,
            guardSupported = false,
        ))
    }

    @Test fun `a panel with no observed root is skipped, which is the helper-only protection`() {
        // A helper-only panel is exactly one where the app has no su of its own, so observing root is
        // what keeps this migration away from the daemon such a panel depends on.
        assertEquals(
            BundledHelperInstaller.Result.SKIPPED,
            bundledHelperAdmission(
                bundledBuildMatches = false,
                companionSupported = true,
                guardSupported = true,
                rootObserved = { false },
            ),
        )
    }

    @Test fun `observed root admits a different-build canonical helper migration`() {
        // The regression this pins: `app_can_su` is an attempt-order hint written against the firmware
        // the profile author saw. An owner who flashes a rooted build keeps that stock-derived profile,
        // and vetoing on it denied the helper to a panel that plainly had root. Admission takes no
        // profile argument at all, so the hint cannot re-enter as a veto by being passed in.
        assertNull(bundledHelperAdmission(
            bundledBuildMatches = false,
            companionSupported = true,
            guardSupported = true,
            rootObserved = { true },
        ))
    }

    private val stagedBuild = "a".repeat(64)
    private val incumbentBuild = "b".repeat(64)
    private val emptyStatus = "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0"
    private val supervisedCaps =
        "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE"

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

    @Test fun `stage command writes only fixed root owned bounded candidate`() {
        val hash = "c".repeat(64)
        val command = bundledHelperStageCommand(hash)
        assertTrue(command.contains("rm -f /data/local/.hapaneld-helper.new"))
        assertTrue(command.contains("cat > /data/local/.hapaneld-helper.new"))
        assertTrue(command.contains("[ \"\$actual\" = \"$hash\" ]"))
        assertTrue(command.contains("chown 0:0 /data/local/.hapaneld-helper.new"))
        assertTrue(command.contains("chmod 700 /data/local/.hapaneld-helper.new"))
        assertTrue(command.contains("0:0:700:1:*"))
        assertTrue(command.contains("16777216"))
        assertTrue(command.contains("echo STAGED_OK"))
        assertFalse(command.contains("/data/local/hapaneld-helper"))
        assertFalse(command.contains(".hapaneld-helper.previous"))
        listOf("mv ", "cp ", "pkill", " stop ", " start ", "--supervise", "awk", "/data/local/tmp")
            .forEach { assertFalse(it, command.contains(it)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stage command rejects non digest interpolation`() {
        bundledHelperStageCommand("a; reboot")
    }

    @Test fun `replacement nonce is fresh lowercase sha sized material`() {
        val first = freshBundledHelperReplacementNonce()
        val second = freshBundledHelperReplacementNonce()
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertTrue(second.matches(Regex("[0-9a-f]{64}")))
        assertTrue(first != second)
    }

    @Test fun `reply loss reconciles exact new helper without retrying RETIRE`() {
        var retires = 0
        val result = executeBundledHelperReplacement(
            retire = { retires++; GuardDbMaintenanceProtocol.AppRetireResult.Indeterminate },
            probe = { exactProbe(stagedBuild) },
            pause = {},
            polls = 2,
        )
        assertEquals(BundledHelperReplacementSettlement.INSTALLED, result)
        assertEquals(1, retires)
    }

    @Test fun `PREPARE winning retire race blocks before settlement probes`() {
        var retires = 0
        var probes = 0
        val result = executeBundledHelperReplacement(
            retire = {
                retires++
                GuardDbMaintenanceProtocol.AppRetireResult.Rejected("ARMED", "replacement")
            },
            probe = { probes++; HelperReplacementProbe.Hold },
            pause = {},
            polls = 2,
        )
        assertEquals(BundledHelperReplacementSettlement.BLOCKED_ACTIVE, result)
        assertEquals(1, retires)
        assertEquals(0, probes)
    }

    @Test fun `retire winning race tolerates handoff hold then settles new`() {
        var retires = 0
        val probes = ArrayDeque<HelperReplacementProbe>().apply {
            add(HelperReplacementProbe.Hold)
            add(exactProbe(stagedBuild))
        }
        val result = executeBundledHelperReplacement(
            retire = { retires++; GuardDbMaintenanceProtocol.AppRetireResult.Requested },
            probe = { probes.removeFirst() },
            pause = {},
            polls = 2,
        )
        assertEquals(BundledHelperReplacementSettlement.INSTALLED, result)
        assertEquals(1, retires)
    }

    @Test fun `old safe requires two uninterrupted exact incumbent tuples`() {
        val probes = ArrayDeque<HelperReplacementProbe>().apply {
            add(exactProbe(incumbentBuild))
            add(HelperReplacementProbe.Hold)
            add(exactProbe(incumbentBuild))
            add(exactProbe(incumbentBuild))
        }
        val result = executeBundledHelperReplacement(
            retire = { GuardDbMaintenanceProtocol.AppRetireResult.Indeterminate },
            probe = { probes.removeFirst() },
            pause = {},
            polls = 4,
        )
        assertEquals(BundledHelperReplacementSettlement.OLD_SAFE, result)
    }

    @Test fun `single incumbent tuple or unavailable deadline remains HOLD`() {
        val probes = ArrayDeque<HelperReplacementProbe>().apply {
            add(exactProbe(incumbentBuild))
            add(HelperReplacementProbe.Hold)
            add(exactProbe(incumbentBuild))
        }
        assertEquals(
            BundledHelperReplacementSettlement.HOLD,
            executeBundledHelperReplacement(
                retire = { GuardDbMaintenanceProtocol.AppRetireResult.Requested },
                probe = { probes.removeFirst() },
                pause = {},
                polls = 3,
            ),
        )
    }

    @Test fun `definitely not submitted never probes or mutates`() {
        var probes = 0
        assertEquals(
            BundledHelperReplacementSettlement.NOT_SUBMITTED,
            executeBundledHelperReplacement(
                retire = { GuardDbMaintenanceProtocol.AppRetireResult.NotSubmitted },
                probe = { probes++; HelperReplacementProbe.Hold },
                pause = {},
                polls = 1,
            ),
        )
        assertEquals(0, probes)
    }

    @Test fun `replacement tuple requires ping exact build supervised and exact EMPTY`() {
        assertEquals(HelperReplacementProbe.Settled(HelperReplacementBuild.NEW), exactProbe(stagedBuild))
        assertEquals(HelperReplacementProbe.Settled(HelperReplacementBuild.OLD), exactProbe(incumbentBuild))
        val valid = arrayOf<String?>("OK", "BUILDID $stagedBuild", supervisedCaps, emptyStatus)
        val invalid = listOf(
            valid.copyOf().also { it[0] = "ok" },
            valid.copyOf().also { it[1] = "BUILDID ${"c".repeat(64)}" },
            valid.copyOf().also { it[2] = "${GuardDbMaintenanceProtocol.CAPS_REPLY} SUPERVISED" },
            valid.copyOf().also {
                it[2] = "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED"
            },
            valid.copyOf().also { it[2] = GuardDbMaintenanceProtocol.CAPS_REPLY },
            valid.copyOf().also { it[3] = "ERR HOLD startup" },
            valid.copyOf().also { it[3] = emptyStatus.replace("EMPTY", "STAGING") },
            valid.copyOf().also { it[3] = null },
        )
        invalid.forEach { tuple ->
            assertEquals(
                tuple.toList().toString(),
                HelperReplacementProbe.Hold,
                classifyHelperReplacementProbe(
                    tuple[0], tuple[1], tuple[2], tuple[3], stagedBuild, incumbentBuild,
                ),
            )
        }
    }

    @Test fun `armed ambiguous and unreachable status block staging`() {
        val status = GuardDbMaintenanceProtocol.Status(
            generation = 8L,
            phase = GuardDbMaintenanceProtocol.Phase.AMBIGUOUS,
            session = "1".repeat(64),
            bootNonce = "2".repeat(64),
            role = null,
            apkSha256 = null,
            versionCode = null,
            schema = null,
            baselineAppStateCount = 4L,
            error = "PM_UNKNOWN",
            outcome = GuardDbMaintenanceProtocol.Outcome.AMBIGUOUS,
            overallDeadlineElapsedMs = 1_800_000L,
            forwardDeadlineElapsedMs = 1_320_000L,
        )
        assertFalse(bundledHelperReplacementAllowed(GuardDbMaintenanceClient.StatusProbe.Valid(status)))
        assertFalse(bundledHelperReplacementAllowed(
            GuardDbMaintenanceClient.StatusProbe.Valid(status.copy(
                phase = GuardDbMaintenanceProtocol.Phase.FINALIZED,
                role = GuardDbMaintenanceProtocol.Role.A,
                apkSha256 = stagedBuild,
                versionCode = 568L,
                schema = 14,
                error = null,
                outcome = GuardDbMaintenanceProtocol.Outcome.CANARY_PASSED,
            )),
        ))
        assertTrue(bundledHelperReplacementAllowed(GuardDbMaintenanceClient.StatusProbe.Unsupported))
        assertFalse(bundledHelperReplacementAllowed(GuardDbMaintenanceClient.StatusProbe.Unreachable))
        assertFalse(bundledHelperReplacementAllowed(GuardDbMaintenanceClient.StatusProbe.Malformed))
    }

    private fun exactProbe(build: String): HelperReplacementProbe = classifyHelperReplacementProbe(
        "OK",
        "BUILDID $build",
        supervisedCaps,
        emptyStatus,
        stagedBuild,
        incumbentBuild,
    )
}
