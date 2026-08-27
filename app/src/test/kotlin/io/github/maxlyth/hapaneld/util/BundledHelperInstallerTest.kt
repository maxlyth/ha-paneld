package io.github.maxlyth.hapaneld.util

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
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

    @Test fun `released base without Guard verbs uses candidate owned takeover`() {
        // RC2 answers bare ERR to GUARDSTATUS, which statusProbe classifies as Unsupported. It cannot
        // consume GUARDRETIRE, so this must never enter the guarded-retire settlement loop.
        assertEquals(
            BundledHelperReplacementMode.RELEASED_LEGACY_TAKEOVER,
            bundledHelperReplacementMode(GuardDbMaintenanceClient.StatusProbe.Unsupported),
        )

        val hash = "c".repeat(64)
        val command = bundledLegacyHelperTakeoverCommand(hash, stagedBuild, incumbentBuild)
        assertTrue(command.contains("stage=/data/local/.hapaneld-helper.new"))
        assertTrue(command.contains("live=/data/local/hapaneld-helper"))
        assertTrue(command.contains("lock=/dev/.hapaneld-helper-transaction.lock"))
        assertTrue(command.contains("--replacement-safe"))
        assertTrue(command.contains("previous_tmp=/data/local/.hapaneld-helper.previous.tmp"))
        assertTrue(command.contains("previous_sha"))
        assertTrue(command.contains("= \"BUILDID $incumbentBuild\""))
        assertTrue(command.contains("mv -f \"\$stage\" \"\$live\""))
        assertTrue(command.contains("mv -f \"\$previous\" \"\$live\""))
        assertTrue(command.contains("\"\$live\" --supervise"))
        assertTrue(command.contains("--request BUILDID"))
        assertTrue(command.contains("--request GUARDSELF"))
        assertTrue(command.contains("$hash $stagedBuild"))
        assertTrue(command.contains("AUTONOMOUS SUPERVISED TERMINAL_RETIRE"))
        assertTrue(command.contains(emptyStatus))
        assertFalse(command.contains("GUARDRETIRE"))
        assertFalse(command.contains("pkill"))
        assertFalse(command.contains("killall"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `legacy takeover rejects non digest build interpolation`() {
        bundledLegacyHelperTakeoverCommand("c".repeat(64), "a; reboot", incumbentBuild)
    }

    @Test fun `released base takeover executes exact candidate and clears rollback custody`() {
        withTakeoverFiles(candidateStarts = true) { directory, live, stage, oldBytes, candidateBytes ->
            val command = bundledLegacyHelperTakeoverCommand(
                sha256(stage), stagedBuild, incumbentBuild,
                dataLocal = directory.absolutePath,
                lockPath = File(directory, ".transaction-lock").absolutePath,
                polls = 1,
            )
            assertEquals(0, runTakeoverCommand(command))
            assertEquals(candidateBytes, live.readText())
            assertFalse(stage.exists())
            assertFalse(File(directory, ".hapaneld-helper.previous").exists())
            assertFalse(File(directory, ".hapaneld-helper.previous.tmp").exists())
            assertFalse(File(directory, ".transaction-lock").exists())
            assertTrue(oldBytes != live.readText())
        }
    }

    @Test fun `candidate supervision failure restores and verifies exact released incumbent`() {
        withTakeoverFiles(candidateStarts = false) { directory, live, stage, oldBytes, _ ->
            val oldSha = sha256(live)
            val command = bundledLegacyHelperTakeoverCommand(
                sha256(stage), stagedBuild, incumbentBuild,
                dataLocal = directory.absolutePath,
                lockPath = File(directory, ".transaction-lock").absolutePath,
                polls = 1,
            )
            assertEquals(1, runTakeoverCommand(command))
            assertEquals(oldBytes, live.readText())
            assertEquals(oldSha, sha256(live))
            assertFalse(stage.exists())
            assertFalse(File(directory, ".hapaneld-helper.previous").exists())
            assertFalse(File(directory, ".hapaneld-helper.previous.tmp").exists())
            assertFalse(File(directory, ".transaction-lock").exists())
        }
    }

    @Test fun `live shared transaction owner blocks takeover before custody or swap`() {
        withTakeoverFiles(candidateStarts = true) { directory, live, stage, oldBytes, candidateBytes ->
            val lock = File(directory, ".transaction-lock").apply { mkdir() }
            File(lock, "pid").writeText("1\n")
            val command = bundledLegacyHelperTakeoverCommand(
                sha256(stage), stagedBuild, incumbentBuild,
                dataLocal = directory.absolutePath,
                lockPath = lock.absolutePath,
                polls = 1,
            )
            assertEquals(75, runTakeoverCommand(command))
            assertEquals(oldBytes, live.readText())
            assertEquals(candidateBytes, stage.readText())
            assertTrue(lock.isDirectory)
            assertFalse(File(directory, ".hapaneld-helper.previous").exists())
            assertFalse(File(directory, ".hapaneld-helper.previous.tmp").exists())
        }
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

    @Test fun `only exact empty or released unsupported status selects a replacement authority`() {
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
        assertNull(bundledHelperReplacementMode(GuardDbMaintenanceClient.StatusProbe.Valid(status)))
        assertNull(bundledHelperReplacementMode(
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
        assertEquals(
            BundledHelperReplacementMode.RELEASED_LEGACY_TAKEOVER,
            bundledHelperReplacementMode(GuardDbMaintenanceClient.StatusProbe.Unsupported),
        )
        assertEquals(
            BundledHelperReplacementMode.GUARDED_RETIRE,
            bundledHelperReplacementMode(GuardDbMaintenanceClient.StatusProbe.Valid(status.copy(
                generation = 0L,
                phase = GuardDbMaintenanceProtocol.Phase.EMPTY,
                session = null,
                bootNonce = null,
                role = null,
                apkSha256 = null,
                versionCode = null,
                schema = null,
                baselineAppStateCount = 0L,
                error = null,
                outcome = null,
                overallDeadlineElapsedMs = 0L,
                forwardDeadlineElapsedMs = 0L,
            ))),
        )
        assertNull(bundledHelperReplacementMode(GuardDbMaintenanceClient.StatusProbe.Unreachable))
        assertNull(bundledHelperReplacementMode(GuardDbMaintenanceClient.StatusProbe.Malformed))
    }

    private fun exactProbe(build: String): HelperReplacementProbe = classifyHelperReplacementProbe(
        "OK",
        "BUILDID $build",
        supervisedCaps,
        emptyStatus,
        stagedBuild,
        incumbentBuild,
    )

    private fun withTakeoverFiles(
        candidateStarts: Boolean,
        test: (directory: File, live: File, stage: File, oldBytes: String, candidateBytes: String) -> Unit,
    ) {
        val directory = Files.createTempDirectory("bundled-helper-takeover-").toFile()
        try {
            val live = File(directory, "hapaneld-helper")
            val stage = File(directory, ".hapaneld-helper.new")
            val oldBytes = fakeHelper(incumbentBuild, candidate = false, starts = true)
            val candidateBytes = fakeHelper(stagedBuild, candidate = true, starts = candidateStarts)
            writeExecutable(live, oldBytes)
            writeExecutable(stage, candidateBytes)
            test(directory, live, stage, oldBytes, candidateBytes)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun fakeHelper(build: String, candidate: Boolean, starts: Boolean): String = """
        #!/bin/sh
        if [ "${'$'}1" = --replacement-safe ]; then
          ${if (candidate) "echo REPLACE_SAFE; exit 0" else "exit 2"}
        fi
        if [ "${'$'}1" = --supervise ]; then exit ${if (starts) 0 else 7}; fi
        if [ "${'$'}1" = --request ]; then
          case "${'$'}2" in
            PING) echo OK ;;
            BUILDID) echo "BUILDID $build" ;;
            GUARDCAPS) ${if (candidate && starts) "echo '${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE'" else "echo ERR"} ;;
            GUARDSELF)
              self_bytes=${'$'}(stat -c %s "${'$'}0")
              self_sha=${'$'}(sha256sum "${'$'}0")
              self_sha=${'$'}{self_sha%% *}
              echo "OK GUARDSELF 1 ${'$'}self_bytes ${'$'}self_sha $build"
              ;;
            GUARDSTATUS) ${if (candidate && starts) "echo '$emptyStatus'" else "echo ERR"} ;;
            *) echo ERR ;;
          esac
          exit 0
        fi
        exit 3
    """.trimIndent() + "\n"

    private fun writeExecutable(file: File, bytes: String) {
        file.writeText(bytes)
        Files.setPosixFilePermissions(file.toPath(), setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        ))
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun runTakeoverCommand(command: String): Int = ProcessBuilder("bash", "-c", command)
        .redirectErrorStream(true)
        .start()
        .let { process ->
            process.inputStream.bufferedReader().readText()
            process.waitFor()
        }
}
