package io.github.maxlyth.hapaneld.control

import android.util.Log
import io.github.maxlyth.hapaneld.device.ScreenOff
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.NoWakeTap
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.platform.ScreenPower
import io.github.maxlyth.hapaneld.platform.WakeTap
import io.github.maxlyth.hapaneld.util.HelperClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class WakeOutcome { WOKEN, ALREADY_ON, STALE_GENERATION, ACTUATION_FAILED }

/** Ownership proof for one screen-off transition created by an automatic policy. */
@JvmInline
value class AutomaticOffEpoch internal constructor(internal val generation: Long)

/**
 * Screen on/off — vendor-free with one serialized transition owner.
 *
 * The active profile selects helper, direct `su`, or brightness-zero as the preferred route. The two
 * privileged routes may fall through to each other and then brightness when unavailable; an explicit
 * brightness-zero profile never probes a privileged actuator. All routes leave the device Awake — no
 * keyguard and no PIN on wake.
 *
 * This deliberately avoids `DevicePolicyManager.lockNow()`, which turns the screen off via the
 * keyguard and therefore demands the device PIN on wake. Its collaborators are seamed ([Backlight],
 * [ScreenPower], [RootShell], [Daemon], [WakeTap]) so the never-blank logic is unit-testable without a device.
 *
 * Never-blank guarantee: a screen-off must ALWAYS be locally wakeable. Every real off arms a [WakeTap]
 * (a non-consuming touch overlay) so a tap re-lights the panel. If a wake can't be guaranteed
 * ([WakeTap.arm] cannot confirm attachment), the off degrades to a visible dim rather than a true dark,
 * so the panel can never look bricked (the failure mode that stranded a freshly-provisioned panel dark
 * + touch-dead).
 */
class ScreenController(
    private val backlight: Backlight,
    private val power: ScreenPower,
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
    private val wakeTap: WakeTap = NoWakeTap,
    private val route: ScreenOff,
) {
    // Last known "on" level, used by the brightness fallback. Survives an off/on cycle.
    @Volatile private var savedLevel = DEFAULT_ON

    /** Invoked after a LOCAL touch-wake. A non-null epoch proves that this exact physical tap woke the
     * automatic OFF generation; null means it woke a manual OFF and must never train auto-sleep. */
    @Volatile var onWakeByTap: ((AutomaticOffEpoch?) -> Unit)? = null
    /** Invoked after every completed physical wake. Keep callbacks non-blocking; the service schedules
     * auto-brightness reconciliation away from this serialized screen transition. */
    @Volatile var onWakeCompleted: (() -> Unit)? = null

    // True only between a genuine screen-off and the next wake. The never-blank watchdog uses this to
    // tell a USER-intended dark screen (leave it) from an unintended one (re-light it) — so a stray/
    // stale screen-off can never strand the panel dark, but a deliberate "screen off" still stays off.
    @Volatile private var intendedOff = false
    @Volatile private var appliedOffRoute: ScreenOff? = null
    private val stateGeneration = AtomicLong()
    @Volatile private var intendedOffGeneration = 0L
    @Volatile private var observedDarkGeneration = 0L
    @Volatile private var automaticOffGeneration = 0L
    private val admissionClosed = AtomicBoolean(false)

    fun isOn(): Boolean = power.isInteractive()

    /** Whether the last screen state ha-paneld set was a deliberate off (vs. never-asked / woken). */
    fun isIntendedOff(): Boolean = intendedOff

    /** Serialize a brightness write with screen transitions. Either the write completes before a later
     * sleep (which then wins), or an already-intended off rejects it; ALS can never relight an OFF panel. */
    @Synchronized
    fun actuateBrightnessIfOn(action: () -> Unit): Boolean {
        if (intendedOff || admissionClosed.get()) return false
        action()
        return true
    }

    /** Best-effort: is the backlight actually dark? bl_power 4=off/0=on (root/daemon panels); else the
     *  brightness-fallback path where 0 == off. Unknown → false (never re-light on a guess). */
    fun observedDark(): Boolean? {
        val effective = { backlight.getBrightness().takeIf { it >= 0 }?.let { it <= 0 } }
        fun fromPower(power: Int): Boolean? {
            // A powered backlight with an effective level of zero is still physically dark.
            return if (power == 0) effective() else true
        }
        return when (route) {
            ScreenOff.DAEMON_BLPOWER, ScreenOff.SU_BLPOWER -> observedBlPower()?.let(::fromPower) ?: effective()
            ScreenOff.BRIGHTNESS_ZERO -> effective()
        }
    }

    /**
     * Affirmative proof that the screen is physically lit for a process handoff. Unlike [observedDark],
     * privileged bl_power profiles never degrade to brightness-only evidence: a positive level cannot
     * prove visibility while the panel's backlight power remains off.
     */
    fun observedLit(): Boolean? {
        val effectiveLit = { backlight.getBrightness().takeIf { it >= 0 }?.let { it > 0 } }
        return when (route) {
            ScreenOff.DAEMON_BLPOWER, ScreenOff.SU_BLPOWER -> observedBlPower()?.let { powerState ->
                if (powerState == 0) effectiveLit() else false
            }
            ScreenOff.BRIGHTNESS_ZERO -> effectiveLit()
        }
    }

    private fun observedBlPower(): Int? = when (route) {
        ScreenOff.DAEMON_BLPOWER -> daemon.send("BLPOWER")?.trim()?.toIntOrNull()
            ?.takeIf { it in 0..4 }
            ?: root.runOutput(blPowerRead())?.trim()?.toIntOrNull()?.takeIf { it in 0..4 }
        ScreenOff.SU_BLPOWER -> root.runOutput(blPowerRead())?.trim()?.toIntOrNull()
            ?.takeIf { it in 0..4 }
            ?: daemon.send("BLPOWER")?.trim()?.toIntOrNull()?.takeIf { it in 0..4 }
        ScreenOff.BRIGHTNESS_ZERO -> null
    }

    /** Cautious boolean used by the never-blank watchdog: unknown is not grounds to alter hardware. */
    fun looksDark(): Boolean = observedDark() == true

    /** Recover only a dark backlight on an otherwise interactive panel. A non-interactive device has
     * entered Android's normal screen sleep and must not be woken by the periodic never-blank guard.
     * The potentially slow hardware observation stays outside the transition monitor; its generation
     * is then admitted atomically with the wake so a concurrent explicit screen-off always wins. */
    fun recoverUnexpectedDark(): Boolean {
        if (intendedOff || admissionClosed.get() || !power.isInteractive()) return false
        val observedGeneration = stateGeneration.get()
        if (!looksDark()) return false
        return recoverUnexpectedDark(observedGeneration)
    }

    @Synchronized
    private fun recoverUnexpectedDark(observedGeneration: Long): Boolean {
        if (
            intendedOff || admissionClosed.get() ||
            stateGeneration.get() != observedGeneration || !power.isInteractive()
        ) return false
        // Brightness writes do not represent a screen-state generation, so fresh readback is the final
        // authority: a newer positive write must win over the stale dark observation made above.
        if (!looksDark() || !power.isInteractive()) return false
        wake()
        return true
    }

    /** Record an explicit brightness so the fallback off/on restores to it. */
    fun noteLevel(level: Int) {
        if (level > 0) savedLevel = level.coerceIn(1, 255)
    }

    @Synchronized
    fun sleep() {
        sleepInternal(automatic = false)
    }

    /**
     * Turn the screen off on behalf of an automatic policy and return ownership of that exact epoch.
     * If the screen is already intentionally off, the existing (possibly manual) owner wins and no
     * epoch is returned. This prevents an automatic controller from adopting and later waking a manual
     * screen-off.
     */
    @Synchronized
    fun sleepAutomatically(): AutomaticOffEpoch? = sleepInternal(automatic = true)

    private fun sleepInternal(automatic: Boolean): AutomaticOffEpoch? {
        if (admissionClosed.get() || (automatic && intendedOff)) return null
        intendedOffGeneration = stateGeneration.incrementAndGet()
        automaticOffGeneration = if (automatic) intendedOffGeneration else 0L
        observedDarkGeneration = 0L
        intendedOff = true
        // Never go fully dark without a guaranteed way back. If touch-to-wake can't be armed (no overlay
        // permission), a real screen-off would leave the panel unwakeable except via HA/proximity — the
        // "looks bricked" failure that stranded a freshly-provisioned panel dark. Degrade to a visible
        // dim instead: still legible + tappable, and HA/proximity can still restore full brightness. This
        // path leaves the screen VISIBLE, so the built-in renderer must NOT be frozen here (a frozen
        // WebView on a still-lit dashboard would show stale, un-tappable cards).
        val wakeTapGeneration = intendedOffGeneration
        val wakeTapAutomaticEpoch = automaticEpochOrNull()
        if (!wakeTap.canArm() || !wakeTap.arm {
                // A touch observer may hand work to another thread. By the time that worker runs, a
                // manual command or a later automatic transition may own a different OFF epoch. Never
                // let the old tap wake that newer state: retain and prove the exact epoch armed here.
                if (wakeIfStillDark(wakeTapGeneration) == WakeOutcome.WOKEN) {
                    onWakeByTap?.invoke(wakeTapAutomaticEpoch)
                }
            }
        ) {
            val cur = backlight.getBrightness()
            if (cur > 0) savedLevel = cur
            backlight.setBrightness(NO_WAKE_DIM)
            appliedOffRoute = null
            Log.w(TAG, "screen-off with no touch-to-wake — dimming to floor (never-blank; saved=$savedLevel)")
            return automaticEpochOrNull()
        }
        // Guaranteed locally wakeable: arm the tap, then power the backlight off for real. Only the two
        // bl_power paths below take the panel *truly* dark — freeze the WebView there (no point rendering
        // behind a black backlight). The brightness fallback (0) is not guaranteed dark on panels that
        // clamp a minimum, so it does NOT freeze (correctness over the CPU saving on those rare panels).
        val poweredOffRoute = when (route) {
            ScreenOff.DAEMON_BLPOWER -> when {
                daemon.send("SCREEN OFF") == "OK" -> ScreenOff.DAEMON_BLPOWER
                root.run(blPower(false)) -> ScreenOff.SU_BLPOWER
                else -> null
            }
            ScreenOff.SU_BLPOWER -> when {
                root.run(blPower(false)) -> ScreenOff.SU_BLPOWER
                daemon.send("SCREEN OFF") == "OK" -> ScreenOff.DAEMON_BLPOWER
                else -> null
            }
            ScreenOff.BRIGHTNESS_ZERO -> null
        }
        if (poweredOffRoute != null) {
            appliedOffRoute = poweredOffRoute
            // A successful bl_power actuator is authoritative for this exact off epoch. This permits
            // a quick external wake to reconcile before the next heartbeat. Brightness-zero remains
            // unconfirmed until read back dark because some panels visibly clamp its raw zero.
            observedDarkGeneration = intendedOffGeneration
            BuiltinDashboard.onScreenAwake(false)
            Log.d(TAG, "screen -> off (${poweredOffRoute.name.lowercase()})")
            return automaticEpochOrNull()
        }
        // Last resort: no daemon, no su — dim to 0 (only a dim on panels that clamp a minimum). Uses the
        // raw setter so it can reach 0: the public setBrightness floors at MIN_VISIBLE to stay never-blank.
        val cur = backlight.getBrightness()
        if (cur > 0) savedLevel = cur
        backlight.setBrightnessRaw(0)
        appliedOffRoute = ScreenOff.BRIGHTNESS_ZERO
        Log.d(TAG, "screen -> off (brightness fallback; saved=$savedLevel)")
        return automaticEpochOrNull()
    }

    private fun automaticEpochOrNull(): AutomaticOffEpoch? =
        automaticOffGeneration.takeIf { it != 0L }?.let(::AutomaticOffEpoch)

    @Synchronized
    fun wake() {
        stateGeneration.incrementAndGet()
        automaticOffGeneration = 0L
        intendedOff = false
        wakeTap.disarm()
        val wakingRoute = appliedOffRoute ?: route
        appliedOffRoute = null
        when (wakingRoute) {
            ScreenOff.DAEMON_BLPOWER -> {
                if (daemon.send("SCREEN ON") == "OK") {
                    completeWake("screen -> on (daemon bl_power)")
                    return
                }
                if (root.run(blPower(true))) {
                    completeWake("screen -> on (su bl_power fallback)")
                    return
                }
            }
            ScreenOff.SU_BLPOWER -> {
                if (root.run(blPower(true))) {
                    completeWake("screen -> on (su bl_power)")
                    return
                }
                if (daemon.send("SCREEN ON") == "OK") {
                    completeWake("screen -> on (daemon bl_power fallback)")
                    return
                }
            }
            ScreenOff.BRIGHTNESS_ZERO -> Unit
        }
        backlight.setBrightness(savedLevel.coerceAtLeast(MIN_ON))
        completeWake("screen -> on (brightness fallback; $savedLevel)")
    }

    /** Token for generation-safe local wake work. Null means there is no deliberate screen-off to wake. */
    fun currentOffGeneration(): Long? = intendedOffGeneration.takeIf { intendedOff && it != 0L }

    /** Reconcile a physical/vendor wake that did not pass through [wake]. No actuator is touched: the
     * observed lit state is authoritative, but stale off intent and queued gesture generations must die. */
    @Synchronized
    fun noteObservedDark(expectedGeneration: Long?): Boolean {
        if (
            expectedGeneration == null || !intendedOff ||
            expectedGeneration != intendedOffGeneration || stateGeneration.get() != expectedGeneration
        ) return false
        observedDarkGeneration = expectedGeneration
        return true
    }

    @Synchronized
    fun reconcileObservedLit(expectedGeneration: Long?): Boolean {
        // The read belongs to one exact off epoch, and that epoch must previously have been observed
        // genuinely dark. This distinguishes a physical wake from brightness-zero that clamps visible.
        if (
            expectedGeneration == null || !intendedOff ||
            expectedGeneration != intendedOffGeneration || stateGeneration.get() != expectedGeneration ||
            observedDarkGeneration != expectedGeneration
        ) return false
        stateGeneration.incrementAndGet()
        intendedOffGeneration = 0L
        observedDarkGeneration = 0L
        automaticOffGeneration = 0L
        intendedOff = false
        appliedOffRoute = null
        wakeTap.disarm()
        BuiltinDashboard.onScreenAwake(true)
        onWakeCompleted?.invoke()
        return true
    }

    /** Reject a gesture queued for an older screen state. This is called only from the existing wake
     * worker, so privileged reads and writes remain off the sensor/main threads. */
    @Synchronized
    fun wakeIfStillDark(expectedGeneration: Long, admissionStillValid: () -> Boolean = { true }): WakeOutcome {
        if (!intendedOff) return WakeOutcome.ALREADY_ON
        if (expectedGeneration != intendedOffGeneration || stateGeneration.get() != expectedGeneration) {
            return WakeOutcome.STALE_GENERATION
        }
        if (!admissionStillValid()) return WakeOutcome.STALE_GENERATION
        return runCatching {
            wake()
            WakeOutcome.WOKEN
        }.getOrElse {
            Log.e(TAG, "generation-safe wake failed", it)
            WakeOutcome.ACTUATION_FAILED
        }
    }

    /**
     * Wake only if [epoch] still owns the current automatic screen-off. Manual sleep/wake commands,
     * physical wake reconciliation, and any later automatic epoch make an older caller harmless.
     */
    @Synchronized
    fun wakeAutomaticallyIfOwned(
        epoch: AutomaticOffEpoch,
        admissionStillValid: () -> Boolean = { true },
    ): WakeOutcome {
        if (!intendedOff) return WakeOutcome.ALREADY_ON
        if (
            epoch.generation != automaticOffGeneration ||
            epoch.generation != intendedOffGeneration ||
            stateGeneration.get() != epoch.generation
        ) return WakeOutcome.STALE_GENERATION
        if (!admissionStillValid()) return WakeOutcome.STALE_GENERATION
        return runCatching {
            wake()
            WakeOutcome.WOKEN
        }.getOrElse {
            Log.e(TAG, "automatic generation-safe wake failed", it)
            WakeOutcome.ACTUATION_FAILED
        }
    }

    /** Publish renderer wake only after the physical wake path and wakelock pulse have completed. */
    private fun completeWake(message: String) {
        power.pulseWake()
        BuiltinDashboard.onScreenAwake(true)
        onWakeCompleted?.invoke()
        Log.d(TAG, message)
    }

    /** Release the wake-overlay owner without ever leaving an intentionally dark panel behind. */
    @Synchronized
    fun close() {
        closeAdmission()
        if (intendedOff) wake() else wakeTap.disarm()
    }

    /** Close future screen-off and brightness admission without performing any hardware I/O. */
    fun closeAdmission() {
        admissionClosed.set(true)
        onWakeByTap = null
        onWakeCompleted = null
    }

    /**
     * Restore the screen and establish the safest available process-exit boundary. A failed privileged
     * proof actively retries the wake actuators on every call, even after an earlier fallback cleared
     * off intent. If both helper and root have permanently disappeared, physical backlight-power proof
     * is impossible: after an active wake attempt, Android interactivity plus positive brightness is an
     * explicit fail-open boundary so recovery can replace the wedged process. That degraded result is
     * not claimed to prove that a privileged `bl_power=4` epoch was physically relit.
     */
    @Synchronized
    fun restoreAndEstablishExitSafety(): Boolean {
        closeAdmission()
        if (intendedOff || observedLit() != true) wake() else wakeTap.disarm()
        val privilegedProof = observedLit()
        if (privilegedProof != null) return privilegedProof
        // Deliberately prefer a fresh control plane over a permanent recovery fence when privileged
        // readback has vanished. This can remain physically dark after an earlier bl_power=4 write.
        return power.isInteractive() && backlight.getBrightness() > 0
    }

    // Write FB_BLANK to the first backlight device's bl_power (0=on, 4=off). Fails (exit!=0, so the
    // caller falls through) if there's no backlight node — never silently "succeeds" doing nothing.
    private fun blPower(on: Boolean): String {
        val v = if (on) 0 else 4
        return "d=\$(ls -d /sys/class/backlight/*/ 2>/dev/null|head -1);" +
            "[ -n \"\$d\" ]&&echo $v >\${d}bl_power"
    }

    private fun blPowerRead(): String =
        "d=\$(ls -d /sys/class/backlight/*/ 2>/dev/null|head -1);cat \${d}bl_power 2>/dev/null"

    companion object {
        private const val TAG = "ha-paneld/screen"
        private const val DEFAULT_ON = 160
        private const val MIN_ON = 10
        // Dim level for a screen-off that can't be made touch-wakeable: low but clearly on, never blank.
        private const val NO_WAKE_DIM = 10
    }
}
