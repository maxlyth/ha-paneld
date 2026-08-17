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
 * The active profile selects helper, direct `su`, keyevent, or brightness-zero as the preferred
 * route. The two bl_power routes may fall through to each other and then brightness when unavailable;
 * an explicit brightness-zero profile never probes a privileged actuator.
 *
 * This deliberately avoids `DevicePolicyManager.lockNow()`, which turns the screen off via the
 * keyguard and therefore demands the device PIN on wake. Its collaborators are seamed ([Backlight],
 * [ScreenPower], [RootShell], [Daemon], [WakeTap]) so the never-blank logic is unit-testable without a device.
 *
 * **Never-blank guarantee: ha-paneld must always be able to undo its own screen-off.** How that is
 * guaranteed is a property of the route, and the two families differ:
 *
 * - The bl_power and brightness routes leave the device Awake, so the dashboard stays foreground and
 *   ha-paneld cannot see a wake tap itself. Each real off therefore arms a [WakeTap] (a non-consuming
 *   touch overlay) and a tap re-lights the panel. If that cannot be confirmed ([WakeTap.arm] returns
 *   false), the off degrades to a visible dim rather than a true dark, so the panel can never look
 *   bricked — the failure mode that stranded a freshly-provisioned panel dark and touch-dead.
 * - [ScreenOff.KEYEVENT] puts Android itself noninteractive, which is a first-class platform state
 *   rather than a backlight ha-paneld blanked behind the framework's back. Its way back is
 *   [ScreenPower.pulseWake] — a wakelock with `ACQUIRE_CAUSES_WAKEUP` that needs no privilege at all,
 *   so it survives root and the helper daemon both disappearing, which the bl_power routes' way back
 *   does not. The touch overlay is deliberately NOT armed on this route: a noninteractive device does
 *   not dispatch touches to windows, so arming it would claim a local wake the route cannot provide.
 *   **Local touch wake on this route is a platform property, declared by the profile rather than
 *   probed** (the same rule as `hasNativeNavbar`): where the touchscreen is a kernel wake source
 *   Android wakes itself and [reconcileObservedLit] adopts that wake; where it is not, Home Assistant
 *   is the way back. The route also refuses to sleep a device with a configured credential
 *   ([ScreenPower.isDeviceSecure]), because waking into a lock screen on a wall panel is the same
 *   class of strand as rebooting one.
 */
class ScreenController(
    private val backlight: Backlight,
    private val power: ScreenPower,
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
    private val wakeTap: WakeTap = NoWakeTap,
    private val route: ScreenOff,
    /** Bounded wait between interactivity read-backs. Seamed so tests confirm without real time. */
    private val nap: (Long) -> Unit = { Thread.sleep(it) },
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
            // Android's own interactivity IS this route's screen state; there is no backlight node to
            // read and no third "unknown" answer to give.
            ScreenOff.KEYEVENT -> !power.isInteractive()
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
            ScreenOff.KEYEVENT -> power.isInteractive()
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
        ScreenOff.KEYEVENT, ScreenOff.BRIGHTNESS_ZERO -> null
    }

    /** Cautious boolean used by the never-blank watchdog: unknown is not grounds to alter hardware. */
    fun looksDark(): Boolean = observedDark() == true

    /** Recover only a dark backlight on an otherwise interactive panel. A non-interactive device has
     * entered Android's normal screen sleep and must not be woken by the periodic never-blank guard.
     * That rule is what makes the watchdog inert on [ScreenOff.KEYEVENT], where "dark" IS
     * noninteractive: the guard exists to undo a backlight ha-paneld blanked behind the framework's
     * back, and it must not start fighting Android's own sleep to reach a route that has none.
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
        // Never go dark without a guaranteed way back. Which guarantee applies is a property of the
        // route (see the class KDoc): the backlight families need a touch overlay, because they leave
        // Android interactive and cannot see the wake tap themselves, and a real off without one would
        // strand the panel dark and touch-dead — the "looks bricked" failure this rule exists for. The
        // keyevent route brings its own unprivileged wake instead, and refuses only where a device
        // credential would stand between that wake and the dashboard.
        val wakeTapGeneration = intendedOffGeneration
        val wakeTapAutomaticEpoch = automaticEpochOrNull()
        val refusal = when (route) {
            // Android's own sleep is undone by the unprivileged wakelock pulse in wake(), so this
            // route brings its own guarantee and never arms the overlay (see the class KDoc). The one
            // thing that can put a barrier in front of the dashboard on the way back is a configured
            // credential, so a secured device is refused rather than slept.
            ScreenOff.KEYEVENT ->
                if (power.isDeviceSecure()) "a device credential would gate the wake" else null
            else -> if (
                wakeTap.canArm() && wakeTap.arm {
                    // A touch observer may hand work to another thread. By the time that worker runs,
                    // a manual command or a later automatic transition may own a different OFF epoch.
                    // Never let the old tap wake that newer state: retain and prove the exact epoch
                    // armed here.
                    if (wakeIfStillDark(wakeTapGeneration) == WakeOutcome.WOKEN) {
                        onWakeByTap?.invoke(wakeTapAutomaticEpoch)
                    }
                }
            ) null else "no touch-to-wake"
        }
        if (refusal != null) return dimToFloor(refusal)
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
            ScreenOff.KEYEVENT -> if (sleepByKeyevent()) ScreenOff.KEYEVENT else null
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
        // Last resort. A raw zero is admissible only because the overlay above was armed, so a tap
        // still re-lights the panel. The keyevent route has no overlay by design, and its way back is
        // the wakelock pulse, which nobody standing at the panel can trigger — so it must not reach a
        // dark it cannot let a person out of, and degrades to the visible floor instead.
        if (route == ScreenOff.KEYEVENT) return dimToFloor("an unconfirmed keyevent sleep")
        // No daemon, no su — dim to 0 (only a dim on panels that clamp a minimum). Uses the raw setter
        // so it can reach 0: the public setBrightness floors at MIN_VISIBLE to stay never-blank.
        val cur = backlight.getBrightness()
        if (cur > 0) savedLevel = cur
        backlight.setBrightnessRaw(0)
        appliedOffRoute = ScreenOff.BRIGHTNESS_ZERO
        Log.d(TAG, "screen -> off (brightness fallback; saved=$savedLevel)")
        return automaticEpochOrNull()
    }

    /**
     * Degrade an off to a visible dim, the never-blank floor. [appliedOffRoute] records what was
     * actually applied rather than what was asked for, so [wake] restores the level this dimmed
     * instead of retrying a privileged actuator that was never used. The screen stays VISIBLE, so the
     * built-in renderer must not be frozen here: a frozen WebView on a still-lit dashboard would show
     * stale, un-tappable cards.
     */
    private fun dimToFloor(reason: String): AutomaticOffEpoch? {
        val cur = backlight.getBrightness()
        if (cur > 0) savedLevel = cur
        backlight.setBrightness(NO_WAKE_DIM)
        appliedOffRoute = ScreenOff.BRIGHTNESS_ZERO
        Log.w(TAG, "screen-off with $reason — dimming to floor (never-blank; saved=$savedLevel)")
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
            ScreenOff.KEYEVENT -> {
                // The wakelock pulse inside completeWake is what actually guarantees this wake, and
                // it needs no privilege, so a failed injection is not worth reporting as a failure —
                // and the brightness fallback below must not run, because this route never changed
                // the brightness and restoring a remembered level would move it for no reason.
                val injected = injectKeyevent("WAKEUP")
                completeWake(
                    if (injected) "screen -> on (keyevent wakeup)"
                    else "screen -> on (wakelock pulse; keyevent wakeup unavailable)"
                )
                return
            }
            ScreenOff.BRIGHTNESS_ZERO -> Unit
        }
        backlight.setBrightness(savedLevel.coerceAtLeast(MIN_ON))
        completeWake("screen -> on (brightness fallback; $savedLevel)")
    }

    /**
     * Put Android noninteractive and prove it happened. `input` is an `app_process` wrapper, and one
     * has been reported exiting zero under the helper daemon's sanitized environment without doing
     * anything, so a submitted request is never accepted as a state change: each transport is judged
     * by the interactivity it produced, and the next one is tried when the first only claimed to work.
     * Root goes first because a full environment is the form the behaviour was reported working in.
     */
    private fun sleepByKeyevent(): Boolean =
        keyeventTransports().any { transport -> transport("SLEEP") && awaitInteractive(false) }

    /** Best-effort wake injection; the caller's wakelock pulse is the actual guarantee. */
    private fun injectKeyevent(name: String): Boolean =
        keyeventTransports().any { transport -> transport(name) }

    private fun keyeventTransports(): List<(String) -> Boolean> = listOf(
        { name: String -> root.run("input keyevent ${keycodeOf(name)}") },
        { name: String -> daemon.send("KEYEVENT $name") == "OK" },
    )

    /** Named keys only, resolved here so no caller or profile document can select another keycode. */
    private fun keycodeOf(name: String): Int = when (name) {
        "SLEEP" -> KEYCODE_SLEEP
        "WAKEUP" -> KEYCODE_WAKEUP
        else -> throw IllegalArgumentException("unsupported screen keyevent: $name")
    }

    /** Poll interactivity for a bounded interval. False means the actuator did not do what it said. */
    private fun awaitInteractive(expected: Boolean): Boolean {
        var waited = 0L
        while (true) {
            if (power.isInteractive() == expected) return true
            if (waited >= KEYEVENT_CONFIRM_MS) return false
            nap(KEYEVENT_POLL_MS)
            waited += KEYEVENT_POLL_MS
        }
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
        private const val KEYCODE_SLEEP = 223
        private const val KEYCODE_WAKEUP = 224
        // Android takes a moment to leave the interactive state, so the read-back is a short bounded
        // poll rather than one immediate sample. It runs inside the transition monitor, which the
        // privileged su/daemon calls around it already hold for a comparable time.
        private const val KEYEVENT_CONFIRM_MS = 1_000L
        private const val KEYEVENT_POLL_MS = 100L
    }
}
