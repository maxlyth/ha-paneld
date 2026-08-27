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
 * Guard replacement protocol. Helper-only panels cannot safely replace their own old daemon and remain
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
        val replacementMode = bundledHelperReplacementMode(GuardDbMaintenance.client.statusProbe())
            ?: return Result.BLOCKED_ACTIVE
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
            val settlement = when (replacementMode) {
                BundledHelperReplacementMode.GUARDED_RETIRE -> executeBundledHelperReplacement(
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
                )
                BundledHelperReplacementMode.RELEASED_LEGACY_TAKEOVER -> {
                    val installed = Su.runSingleAttempt(
                        bundledLegacyHelperTakeoverCommand(
                            expectedSha256,
                            stagedBuildId,
                            incumbentBuildId,
                        ),
                        LEGACY_TAKEOVER_TIMEOUT_MS,
                    )
                    if (installed) BundledHelperReplacementSettlement.INSTALLED
                    else BundledHelperReplacementSettlement.HOLD
                }
            }
            when (settlement) {
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

internal enum class BundledHelperReplacementMode {
    GUARDED_RETIRE,
    RELEASED_LEGACY_TAKEOVER,
}

/**
 * A bare ERR is the released pre-Guard helper's exact response to an unknown verb. It cannot create
 * the Guard replacement journal, so asking it to retire and then waiting for Guard capabilities can
 * only hold forever. A direct-su app instead lets the staged, authenticated candidate prove the Guard
 * namespace empty and perform one exact in-place takeover with an authenticated incumbent rollback.
 * Any ambiguous reply remains fail-closed.
 */
internal fun bundledHelperReplacementMode(
    status: GuardDbMaintenanceClient.StatusProbe,
): BundledHelperReplacementMode? = when (status) {
    GuardDbMaintenanceClient.StatusProbe.Unsupported ->
        BundledHelperReplacementMode.RELEASED_LEGACY_TAKEOVER
    is GuardDbMaintenanceClient.StatusProbe.Valid -> if (!status.status.ownsMaintenance) {
        BundledHelperReplacementMode.GUARDED_RETIRE
    } else {
        null
    }
    GuardDbMaintenanceClient.StatusProbe.Unreachable,
    GuardDbMaintenanceClient.StatusProbe.Malformed -> null
}

/** Root stages one fixed candidate; replacement happens only through the selected exact authority. */
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

/**
 * Released helpers predate GUARDRETIRE, so the new candidate owns this narrowly-scoped takeover.
 * It first proves the Guard namespace empty using its native implementation, then retains and verifies
 * the incumbent before atomically making the already authenticated candidate live. The new supervisor
 * retires only helper processes whose executable inode differs from its own. Success requires the live
 * daemon's exact bytes/build, autonomous/supervised/terminal capabilities and EMPTY Guard status; a
 * failed candidate is replaced by the exact retained incumbent and that daemon is re-verified.
 */
internal fun bundledLegacyHelperTakeoverCommand(
    expectedSha256: String,
    stagedBuildId: String,
    incumbentBuildId: String,
    dataLocal: String = "/data/local",
    lockPath: String = "/dev/.hapaneld-helper-transaction.lock",
    polls: Int = LEGACY_TAKEOVER_POLLS,
): String {
    require(GuardDbMaintenanceProtocol.validSha256(expectedSha256))
    require(GuardDbMaintenanceProtocol.validSha256(stagedBuildId))
    require(GuardDbMaintenanceProtocol.validSha256(incumbentBuildId))
    require(stagedBuildId != incumbentBuildId)
    require(dataLocal.startsWith("/") && SAFE_ABSOLUTE_PATH.matches(dataLocal) &&
        dataLocal.split('/').none { it == ".." })
    require(lockPath.startsWith("/") && SAFE_ABSOLUTE_PATH.matches(lockPath) &&
        lockPath.split('/').none { it == ".." })
    require(polls in 1..LEGACY_TAKEOVER_POLLS)
    val dollar = '$'
    val expectedCapabilities =
        "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE"
    val expectedStatus = "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0"
    return """
        lock=$lockPath
        if ! mkdir "${dollar}lock" 2>/dev/null; then
          holder=${dollar}(cat "${dollar}lock/pid" 2>/dev/null || true)
          case "${dollar}holder" in
            ''|*[!0-9]*) exit 75 ;;
            *) [ ! -d "/proc/${dollar}holder" ] || exit 75 ;;
          esac
          rm -rf "${dollar}lock" 2>/dev/null || exit 75
          mkdir "${dollar}lock" 2>/dev/null || exit 75
        fi
        echo ${dollar}${dollar} > "${dollar}lock/pid" || { rm -rf "${dollar}lock"; exit 75; }
        cleanup_helper_lock() { rm -rf "$lockPath"; }
        trap cleanup_helper_lock 0 1 2 3 15

        [ -d "$dataLocal" ] && [ ! -L "$dataLocal" ] || exit 1
        stage=$dataLocal/.hapaneld-helper.new
        live=$dataLocal/hapaneld-helper
        previous=$dataLocal/.hapaneld-helper.previous
        previous_tmp=$dataLocal/.hapaneld-helper.previous.tmp
        [ -f "${dollar}stage" ] && [ ! -L "${dollar}stage" ] &&
        [ -f "${dollar}live" ] && [ ! -L "${dollar}live" ] &&
        [ ! -e "${dollar}previous" ] && [ ! -L "${dollar}previous" ] &&
        [ ! -e "${dollar}previous_tmp" ] && [ ! -L "${dollar}previous_tmp" ] || exit 1
        stage_meta=${dollar}(stat -c '%u:%g:%a:%h:%s' "${dollar}stage" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${dollar}stage" 2>/dev/null) || exit 1
        live_meta=${dollar}(stat -c '%u:%g:%a:%h:%s' "${dollar}live" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${dollar}live" 2>/dev/null) || exit 1
        case "${dollar}stage_meta" in 0:0:700:1:*) ;; *) exit 1 ;; esac
        case "${dollar}live_meta" in 0:0:700:1:*) ;; *) exit 1 ;; esac
        stage_bytes=${dollar}{stage_meta##*:}
        live_bytes=${dollar}{live_meta##*:}
        [ "${dollar}stage_bytes" -ge 1 ] && [ "${dollar}stage_bytes" -le $MAX_STAGED_HELPER_BYTES ] &&
        [ "${dollar}live_bytes" -ge 1 ] && [ "${dollar}live_bytes" -le $MAX_STAGED_HELPER_BYTES ] || exit 1
        stage_sha=${dollar}(sha256sum "${dollar}stage" 2>/dev/null || toybox sha256sum "${dollar}stage" 2>/dev/null) || exit 1
        stage_sha=${dollar}{stage_sha%% *}
        [ "${dollar}stage_sha" = "$expectedSha256" ] || exit 1
        live_sha=${dollar}(sha256sum "${dollar}live" 2>/dev/null || toybox sha256sum "${dollar}live" 2>/dev/null) || exit 1
        live_sha=${dollar}{live_sha%% *}
        [ "${dollar}live_sha" != "$expectedSha256" ] || exit 1
        [ "${dollar}("${dollar}live" --request BUILDID 2>/dev/null)" = "BUILDID $incumbentBuildId" ] || exit 1
        [ "${dollar}("${dollar}stage" --replacement-safe 2>/dev/null)" = REPLACE_SAFE ] || exit 1

        if ! cp "${dollar}live" "${dollar}previous_tmp" 2>/dev/null; then
          rm -f "${dollar}previous_tmp"
          toybox cp "${dollar}live" "${dollar}previous_tmp" 2>/dev/null || exit 1
        fi
        chown 0:0 "${dollar}previous_tmp" && chmod 700 "${dollar}previous_tmp" || exit 1
        previous_meta=${dollar}(stat -c '%u:%g:%a:%h:%s' "${dollar}previous_tmp" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${dollar}previous_tmp" 2>/dev/null) || exit 1
        [ "${dollar}previous_meta" = "0:0:700:1:${dollar}live_bytes" ] || exit 1
        previous_sha=${dollar}(sha256sum "${dollar}previous_tmp" 2>/dev/null || toybox sha256sum "${dollar}previous_tmp" 2>/dev/null) || exit 1
        previous_sha=${dollar}{previous_sha%% *}
        [ "${dollar}previous_sha" = "${dollar}live_sha" ] || exit 1
        sync || exit 1
        mv -f "${dollar}previous_tmp" "${dollar}previous" || exit 1
        sync || exit 1
        mv -f "${dollar}stage" "${dollar}live" || exit 1
        sync || exit 1
        "${dollar}live" --supervise >/dev/null 2>&1 &
        attempt=0
        candidate_ready=0
        while [ "${dollar}attempt" -lt $polls ]; do
          self=${dollar}("${dollar}live" --request GUARDSELF 2>/dev/null) || self=
          if [ "${dollar}self" = "OK GUARDSELF 1 ${dollar}stage_bytes $expectedSha256 $stagedBuildId" ]; then
            candidate_ready=1
            break
          fi
          attempt=${dollar}((attempt + 1))
          [ "${dollar}attempt" -ge $polls ] || sleep 1
        done
        if [ "${dollar}candidate_ready" = 1 ]; then
          caps=${dollar}("${dollar}live" --request GUARDCAPS 2>/dev/null) || caps=
          status=${dollar}("${dollar}live" --request GUARDSTATUS 2>/dev/null) || status=
          if [ "${dollar}caps" = "$expectedCapabilities" ] && [ "${dollar}status" = "$expectedStatus" ]; then
            rm -f "${dollar}previous" || exit 1
            sync || exit 1
            exit 0
          fi
        fi

        candidate_meta=${dollar}(stat -c '%u:%g:%a:%h:%s' "${dollar}live" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${dollar}live" 2>/dev/null) || exit 1
        previous_meta=${dollar}(stat -c '%u:%g:%a:%h:%s' "${dollar}previous" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${dollar}previous" 2>/dev/null) || exit 1
        [ "${dollar}candidate_meta" = "0:0:700:1:${dollar}stage_bytes" ] &&
        [ "${dollar}previous_meta" = "0:0:700:1:${dollar}live_bytes" ] || exit 1
        candidate_sha=${dollar}(sha256sum "${dollar}live" 2>/dev/null || toybox sha256sum "${dollar}live" 2>/dev/null) || exit 1
        candidate_sha=${dollar}{candidate_sha%% *}
        rollback_sha=${dollar}(sha256sum "${dollar}previous" 2>/dev/null || toybox sha256sum "${dollar}previous" 2>/dev/null) || exit 1
        rollback_sha=${dollar}{rollback_sha%% *}
        [ "${dollar}candidate_sha" = "$expectedSha256" ] &&
        [ "${dollar}rollback_sha" = "${dollar}live_sha" ] || exit 1
        mv -f "${dollar}previous" "${dollar}live" || exit 1
        sync || exit 1
        "${dollar}live" --supervise >/dev/null 2>&1 &
        attempt=0
        while [ "${dollar}attempt" -lt $polls ]; do
          build=${dollar}("${dollar}live" --request BUILDID 2>/dev/null) || build=
          if [ "${dollar}build" = "BUILDID $incumbentBuildId" ]; then
            exit 1
          fi
          attempt=${dollar}((attempt + 1))
          [ "${dollar}attempt" -ge $polls ] || sleep 1
        done
        exit 1
    """.trimIndent()
}

private const val MAX_STAGED_HELPER_BYTES = 16 * 1024 * 1024
private const val LEGACY_TAKEOVER_POLLS = 3
private const val LEGACY_TAKEOVER_TIMEOUT_MS = 45_000L
private val SAFE_ABSOLUTE_PATH = Regex("/[A-Za-z0-9._/-]+")
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
