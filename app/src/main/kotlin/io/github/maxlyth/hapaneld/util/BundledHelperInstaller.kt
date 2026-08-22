package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.control.Su
import java.io.File
import java.security.SecureRandom

/**
 * First-start migration backstop for the supported in-app self-update path.
 *
 * Provisioning remains the durable helper installer. A previously released direct-su panel can,
 * however, install a newer APK before the external provisioner runs. The new APK therefore carries
 * its exact helper and launches a root-owned `/data/local` copy when the installed daemon lacks the
 * Companion protocol. Helper-only panels cannot safely replace their own old daemon and remain
 * explicitly reprovision-gated instead of accepting a generic privileged upgrade verb.
 *
 * That protection comes from OBSERVING root, not from the profile declaring it. A panel is helper-only
 * precisely when the app has no su of its own, which is exactly when [Su.available] is false, so the
 * observation already excludes the unsafe case on its own. The profile's `app_can_su` is an
 * attempt-order hint — the same thing Issue #21 established for metric routes — and it used to veto
 * this path before the probe could speak. That cost real capability rather than buying safety: a panel
 * whose owner has flashed a firmware with root still carries a profile written against the stock image,
 * so it reported `app_can_su: false` and never installed the helper it could plainly have installed.
 */
internal object BundledHelperInstaller {
    enum class Result {
        ALREADY_CURRENT,
        INSTALLED,
        SKIPPED,
        REPROVISION_REQUIRED,
        BLOCKED_ACTIVE,
        FAILED,
    }

    @Synchronized
    fun ensureCurrent(context: Context): Result {
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) return Result.BLOCKED_ACTIVE
        val companionSupported = HelperClient.supportsCompanionData()
        val bundledBuildMatches = HelperClient.matchesBundledHelper()
        val guardSupported = companionSupported && bundledBuildMatches && GuardDbMaintenance.client.supported()
        bundledHelperAdmission(
            bundledBuildMatches = bundledBuildMatches,
            companionSupported = companionSupported,
            guardSupported = guardSupported,
            rootObserved = { Su.available() },
        )?.let { return it }
        val guardStatus = GuardDbMaintenance.client.statusProbe()
        if (!bundledHelperReplacementAllowed(guardStatus)) return Result.BLOCKED_ACTIVE
        val stagedBuildId = BuildConfig.HELPER_BUILD_ID
            .takeIf(GuardDbMaintenanceProtocol::validSha256) ?: return Result.FAILED
        val incumbentBuildId = HelperClient.installedBuildId()
            ?.takeIf { it != stagedBuildId } ?: return Result.FAILED
        val asset = helperAssetName(Build.SUPPORTED_ABIS.asIterable()) ?: return Result.SKIPPED
        val staged = runCatching { File.createTempFile("hapaneld-helper-", ".bin", context.cacheDir) }
            .getOrElse { return Result.FAILED }
        return try {
            context.assets.open(asset).use { input -> staged.outputStream().use(input::copyTo) }
            val expectedSha256 = AppInstaller.sha256(staged)
            val output = Su.runWithStdinLongChecked(
                bundledHelperStageCommand(expectedSha256),
                staged,
                INSTALL_TIMEOUT_MS,
            )
            if (output?.lineSequence()?.lastOrNull()?.trim() != "STAGED_OK") {
                Log.w(TAG, "bundled helper staging failed: ${output.orEmpty().take(120)}")
                return Result.FAILED
            }
            when (executeBundledHelperReplacement(
                retire = {
                    GuardDbMaintenance.client.retireApp(
                        freshBundledHelperReplacementNonce(),
                        expectedSha256,
                        stagedBuildId,
                    )
                },
                probe = { HelperClient.replacementProbe(stagedBuildId, incumbentBuildId) },
                pause = { Thread.sleep(SETTLEMENT_POLL_MS) },
                polls = SETTLEMENT_POLLS,
            )) {
                BundledHelperReplacementSettlement.INSTALLED -> Result.INSTALLED
                BundledHelperReplacementSettlement.BLOCKED_ACTIVE -> Result.BLOCKED_ACTIVE
                BundledHelperReplacementSettlement.OLD_SAFE,
                BundledHelperReplacementSettlement.NOT_SUBMITTED,
                BundledHelperReplacementSettlement.HOLD -> Result.FAILED
            }
        } catch (failure: Exception) {
            Log.w(TAG, "bundled helper migration failed", failure)
            Result.FAILED
        } finally {
            staged.delete()
        }
    }

    private const val TAG = "ha-paneld/helper-migrate"
    private const val INSTALL_TIMEOUT_MS = 30_000L
    private const val SETTLEMENT_POLLS = 120
    private const val SETTLEMENT_POLL_MS = 250L
}

/**
 * Whether this migration may proceed, and why not when it may not.
 *
 * Deliberately takes no device-profile argument. The profile's `app_can_su` is an attempt-order hint,
 * and a hint standing in for an observation is what Issue #21 corrected elsewhere; here it used to veto
 * the migration outright, which denied the helper to any panel whose owner had flashed a rooted
 * firmware while its profile still described the stock image. Root readiness is read live only when
 * a different helper build is eligible for replacement. An equal-build daemon must also expose the
 * exact Companion and autonomous/supervised/terminal Guard surfaces; an equal but noncanonical daemon
 * is left untouched for explicit reprovisioning. `rootObserved` is a lambda so neither terminal
 * equal-build result probes root.
 *
 * Returns the terminal result, or null when the caller should continue with the install.
 */
internal fun bundledHelperAdmission(
    bundledBuildMatches: Boolean,
    companionSupported: Boolean,
    guardSupported: Boolean,
    rootObserved: () -> Boolean,
): BundledHelperInstaller.Result? = when {
    bundledHelperIsCanonical(bundledBuildMatches, companionSupported, guardSupported) ->
        BundledHelperInstaller.Result.ALREADY_CURRENT
    bundledBuildMatches -> BundledHelperInstaller.Result.REPROVISION_REQUIRED
    !rootObserved() -> BundledHelperInstaller.Result.SKIPPED
    else -> null
}

internal fun bundledHelperIsCanonical(
    bundledBuildMatches: Boolean,
    companionSupported: Boolean,
    guardSupported: Boolean,
): Boolean = bundledBuildMatches && companionSupported && guardSupported

internal fun helperAssetName(abis: Iterable<String>): String? = when {
    abis.any { it == "arm64-v8a" } -> "hapaneld-helper-arm64"
    abis.any { it == "armeabi-v7a" } -> "hapaneld-helper-arm"
    else -> null
}

internal fun bundledHelperReplacementAllowed(status: GuardDbMaintenanceClient.StatusProbe): Boolean = when (status) {
    GuardDbMaintenanceClient.StatusProbe.Unsupported -> true
    is GuardDbMaintenanceClient.StatusProbe.Valid -> !status.status.ownsMaintenance
    GuardDbMaintenanceClient.StatusProbe.Unreachable,
    GuardDbMaintenanceClient.StatusProbe.Malformed -> false
}

/** Root stages one fixed candidate. Only the helper-owned R1 lease may replace or launch it. */
internal fun bundledHelperStageCommand(expectedSha256: String): String {
    require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
    val dollar = '$'
    return """
        [ -d /data/local ] && [ ! -L /data/local ] &&
        rm -f /data/local/.hapaneld-helper.new &&
        cat > /data/local/.hapaneld-helper.new &&
        [ -f /data/local/.hapaneld-helper.new ] && [ ! -L /data/local/.hapaneld-helper.new ] &&
        actual=${dollar}(sha256sum /data/local/.hapaneld-helper.new 2>/dev/null || toybox sha256sum /data/local/.hapaneld-helper.new 2>/dev/null) &&
        actual=${dollar}{actual%% *} &&
        [ "${dollar}actual" = "$expectedSha256" ] &&
        chown 0:0 /data/local/.hapaneld-helper.new &&
        chmod 700 /data/local/.hapaneld-helper.new &&
        meta=${dollar}(stat -c '%u:%g:%a:%h:%s' /data/local/.hapaneld-helper.new 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' /data/local/.hapaneld-helper.new 2>/dev/null) &&
        case "${dollar}meta" in 0:0:700:1:*) ;; *) false ;; esac &&
        bytes=${dollar}{meta##*:} &&
        [ "${dollar}bytes" -ge 1 ] && [ "${dollar}bytes" -le $MAX_STAGED_HELPER_BYTES ] &&
        echo STAGED_OK
    """.trimIndent()
}

private const val MAX_STAGED_HELPER_BYTES = 16 * 1024 * 1024
private val BUNDLED_HELPER_REPLACEMENT_RANDOM = SecureRandom()

internal fun freshBundledHelperReplacementNonce(): String = ByteArray(32)
    .also(BUNDLED_HELPER_REPLACEMENT_RANDOM::nextBytes)
    .joinToString("") { "%02x".format(it) }

internal enum class BundledHelperReplacementSettlement {
    INSTALLED,
    OLD_SAFE,
    BLOCKED_ACTIVE,
    NOT_SUBMITTED,
    HOLD,
}

/** Submit RETIRE exactly once; after possible submission the helper alone owns every filesystem/process mutation. */
internal fun executeBundledHelperReplacement(
    retire: () -> GuardDbMaintenanceProtocol.AppRetireResult,
    probe: () -> HelperReplacementProbe,
    pause: () -> Unit,
    polls: Int,
): BundledHelperReplacementSettlement {
    require(polls > 0)
    when (val result = retire()) {
        GuardDbMaintenanceProtocol.AppRetireResult.Requested,
        GuardDbMaintenanceProtocol.AppRetireResult.Indeterminate -> Unit
        GuardDbMaintenanceProtocol.AppRetireResult.NotSubmitted ->
            return BundledHelperReplacementSettlement.NOT_SUBMITTED
        is GuardDbMaintenanceProtocol.AppRetireResult.Rejected -> return if (
            result.code in setOf("ARMED", "HOLD") && result.token == "replacement"
        ) BundledHelperReplacementSettlement.BLOCKED_ACTIVE else BundledHelperReplacementSettlement.NOT_SUBMITTED
    }
    var consecutiveOld = 0
    repeat(polls) { attempt ->
        when (val observed = probe()) {
            is HelperReplacementProbe.Settled -> when (observed.build) {
                HelperReplacementBuild.NEW -> return BundledHelperReplacementSettlement.INSTALLED
                HelperReplacementBuild.OLD -> {
                    consecutiveOld++
                    if (consecutiveOld == 2) return BundledHelperReplacementSettlement.OLD_SAFE
                }
            }
            HelperReplacementProbe.Hold -> consecutiveOld = 0
        }
        if (attempt + 1 < polls) pause()
    }
    return BundledHelperReplacementSettlement.HOLD
}
