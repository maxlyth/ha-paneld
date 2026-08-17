package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import io.github.maxlyth.hapaneld.platform.WakeTap
import io.github.maxlyth.hapaneld.device.ScreenOff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ScreenController's never-blank logic — the top invariant — over the seamed collaborators (Backlight,
 * ScreenPower, RootShell, Daemon), no device. Covers looksDark polarity per tier, the intendedOff flag,
 * and the daemon → su → brightness screen-off/on tier selection.
 */
class ScreenControllerTest {

    private val backlight = FakeBacklight()
    private val power = FakeScreenPower()
    private val wakeTap = FakeWakeTap()  // canArm=true by default: overlay available, so real screen-off runs

    private fun controller(
        blPower: String? = null,          // bl_power read result: "4"=off, "0"=on, null=unreadable
        suRuns: Boolean = true,           // does the su bl_power write succeed
        daemon: Map<String, String> = emptyMap(),
    ): Pair<ScreenController, FakeRootShell> {
        val root = FakeRootShell(outputs = blPower?.let { mapOf("bl_power" to it) } ?: emptyMap(), runResult = suRuns)
        return ScreenController(backlight, power, root, FakeDaemon(daemon), wakeTap, ScreenOff.DAEMON_BLPOWER) to root
    }

    private fun assertRendererWakeAfter(sc: ScreenController, physicalWakeComplete: () -> Boolean) {
        var observedCompleteWake = false
        val listener: (Boolean) -> Unit = { awake ->
            if (awake) observedCompleteWake = physicalWakeComplete() && power.pulses == 1
        }
        BuiltinDashboard.setScreenListener(listener)
        try {
            sc.wake()
        } finally {
            BuiltinDashboard.clearScreenListener(listener)
        }
        assertTrue(observedCompleteWake)
    }

    // --- looksDark polarity ---
    @Test fun daemonBlPowerOffOverridesNonzeroBrightnessAndUnreadableRoot() {
        backlight.level = 120
        val (sc, root) = controller(blPower = null, daemon = mapOf("BLPOWER" to "4"))
        assertTrue(sc.looksDark())
        assertTrue("authoritative daemon read must avoid failed root probing", root.outputRan.isEmpty())
    }

    @Test fun daemonBlPowerOnUsesEffectiveBrightnessToDistinguishVisibleFromZero() {
        backlight.level = 120
        assertFalse(controller(blPower = "4", daemon = mapOf("BLPOWER" to "0")).first.looksDark())
        backlight.level = 0
        assertTrue(controller(blPower = "4", daemon = mapOf("BLPOWER" to "0")).first.looksDark())
    }

    @Test fun daemonBlPowerUnavailableFallsBackToRootThenBrightness() {
        backlight.level = 120
        assertTrue(controller(blPower = "4", daemon = mapOf("BLPOWER" to "ERR")).first.looksDark())
        backlight.level = 0
        assertTrue(controller(blPower = null, daemon = mapOf("BLPOWER" to "ERR")).first.looksDark())
    }

    @Test fun daemonBlPowerExternalChangeIsObservedWithoutACommand() {
        val replies = mutableMapOf("BLPOWER" to "0")
        backlight.level = 120
        val (sc, _) = controller(daemon = replies)
        assertFalse(sc.looksDark())
        replies["BLPOWER"] = "4"
        assertTrue(sc.looksDark())
    }

    @Test fun looksDarkTrueWhenBlPowerOff() = assertTrue(controller(blPower = "4").first.looksDark())

    @Test fun looksDarkFalseWhenBlPowerOnAndBrightnessVisible() {
        backlight.level = 120
        assertFalse(controller(blPower = "0").first.looksDark())
    }

    @Test fun looksDarkTrueWhenBlPowerOnButBrightnessZero() {
        backlight.level = 0
        assertTrue(controller(blPower = "0").first.looksDark())
    }

    @Test fun looksDarkFallsBackToBrightnessWhenBlPowerUnreadable() {
        backlight.level = 0
        assertTrue("brightness 0 with unknown bl_power reads dark", controller(blPower = null).first.looksDark())
        backlight.level = 120
        assertFalse("brightness >0 with unknown bl_power reads lit", controller(blPower = null).first.looksDark())
    }

    @Test fun observedDarkPreservesUnknownWhenNeitherTierCanRead() {
        backlight.level = -1
        assertEquals(null, controller(blPower = null).first.observedDark())
        assertFalse("unknown must not trigger never-blank hardware changes", controller(blPower = null).first.looksDark())
    }

    @Test fun neverBlankRecoversDarkInteractivePanelsButNotAndroidSleep() {
        val sc = ScreenController(
            backlight, power, FakeRootShell(), FakeDaemon(), wakeTap, ScreenOff.BRIGHTNESS_ZERO,
        )
        backlight.level = 0
        power.interactive = true
        assertTrue(sc.recoverUnexpectedDark())
        assertEquals(1, power.pulses)

        power.interactive = false
        backlight.level = 0
        assertFalse("normal non-interactive Android sleep must not be woken", sc.recoverUnexpectedDark())
    }

    @Test fun neverBlankStillLeavesExplicitScreenOffAlone() {
        val sc = ScreenController(
            backlight, power, FakeRootShell(), FakeDaemon(), wakeTap, ScreenOff.BRIGHTNESS_ZERO,
        )
        power.interactive = true
        sc.sleep()

        assertFalse(sc.recoverUnexpectedDark())
        assertTrue(sc.isIntendedOff())
    }

    @Test fun neverBlankRevalidatesInteractivityAfterTheBacklightRead() {
        var reads = 0
        val transitioningPower = object : io.github.maxlyth.hapaneld.platform.ScreenPower {
            override fun isInteractive(): Boolean = ++reads == 1
            override fun pulseWake() = Unit
        }
        val sc = ScreenController(
            FakeBacklight(0), transitioningPower, FakeRootShell(), FakeDaemon(), wakeTap,
            ScreenOff.BRIGHTNESS_ZERO,
        )

        assertFalse(sc.recoverUnexpectedDark())
        assertEquals(2, reads)
    }

    @Test fun explicitScreenOffWinsWhileNeverBlankIsObservingDarkness() {
        val observationStarted = CountDownLatch(1)
        val releaseObservation = CountDownLatch(1)
        var reads = 0
        val blockingBacklight = object : Backlight {
            val calls = Collections.synchronizedList(mutableListOf<String>())
            override fun getBrightness(): Int {
                if (++reads == 1) {
                    observationStarted.countDown()
                    assertTrue(releaseObservation.await(1, TimeUnit.SECONDS))
                    return 0
                }
                return 120
            }
            override fun setBrightness(level: Int) { calls += "set:$level" }
            override fun setBrightnessRaw(level: Int) { calls += "raw:$level" }
        }
        val sc = ScreenController(
            blockingBacklight, power, FakeRootShell(), FakeDaemon(), wakeTap,
            ScreenOff.BRIGHTNESS_ZERO,
        )
        var recovered: Boolean? = null
        val recovery = Thread(
            { recovered = sc.recoverUnexpectedDark() },
            "test-never-blank-recovery",
        ).apply { start() }

        assertTrue(observationStarted.await(1, TimeUnit.SECONDS))
        sc.sleep()
        releaseObservation.countDown()
        recovery.join(TimeUnit.SECONDS.toMillis(2))

        assertFalse("recovery thread must finish", recovery.isAlive)
        assertEquals(false, recovered)
        assertTrue("the explicit screen-off must remain authoritative", sc.isIntendedOff())
        assertEquals(listOf("raw:0"), blockingBacklight.calls)
        assertEquals("the rejected recovery must not pulse wake", 0, power.pulses)
    }

    @Test fun positiveBrightnessWriteWinsWhileNeverBlankIsObservingDarkness() {
        val observationStarted = CountDownLatch(1)
        val releaseObservation = CountDownLatch(1)
        var reads = 0
        val blockingBacklight = object : Backlight {
            @Volatile var level = 0
            val calls = Collections.synchronizedList(mutableListOf<String>())
            override fun getBrightness(): Int {
                if (++reads == 1) {
                    observationStarted.countDown()
                    assertTrue(releaseObservation.await(1, TimeUnit.SECONDS))
                    return 0
                }
                return level
            }
            override fun setBrightness(level: Int) { calls += "set:$level"; this.level = level }
            override fun setBrightnessRaw(level: Int) { calls += "raw:$level"; this.level = level }
        }
        val sc = ScreenController(
            blockingBacklight, power, FakeRootShell(), FakeDaemon(), wakeTap,
            ScreenOff.BRIGHTNESS_ZERO,
        )
        var recovered: Boolean? = null
        val recovery = Thread(
            { recovered = sc.recoverUnexpectedDark() },
            "test-never-blank-recovery",
        ).apply { start() }

        assertTrue(observationStarted.await(1, TimeUnit.SECONDS))
        assertTrue(sc.actuateBrightnessIfOn { blockingBacklight.setBrightness(180) })
        releaseObservation.countDown()
        recovery.join(TimeUnit.SECONDS.toMillis(2))

        assertFalse("recovery thread must finish", recovery.isAlive)
        assertEquals(false, recovered)
        assertEquals(180, blockingBacklight.level)
        assertEquals(listOf("set:180"), blockingBacklight.calls)
        assertEquals("the rejected recovery must not pulse wake", 0, power.pulses)
    }

    @Test fun privilegedRouteNeedsBlPowerReadbackToProveTheScreenLit() {
        backlight.level = 120
        assertEquals(null, controller(blPower = null).first.observedLit())
        assertEquals(true, controller(blPower = "0").first.observedLit())
        assertEquals(false, controller(blPower = "4").first.observedLit())
    }

    @Test fun processExitFailsOpenAfterPermanentPrivilegeLoss() {
        backlight.level = 120
        power.interactive = true
        assertTrue(controller(blPower = null, suRuns = false).first.restoreAndEstablishExitSafety())

        backlight.level = 0
        power.interactive = false
        assertFalse(controller(blPower = null, suRuns = false).first.restoreAndEstablishExitSafety())
    }

    @Test fun processExitExplicitlyFailsOpenWhenPrivilegeIsLostAfterBlPowerOff() {
        val daemon = PrivilegeLossAfterOffDaemon()
        val sc = ScreenController(
            backlight,
            power,
            FakeRootShell(runResult = false),
            daemon,
            wakeTap,
            ScreenOff.DAEMON_BLPOWER,
        )
        backlight.level = 120
        power.interactive = true
        sc.sleep()
        assertTrue(sc.isIntendedOff())
        daemon.privilegeLost = true

        // The earlier bl_power=4 may still be physically dark. With no remaining privileged read or
        // write path, the policy accepts Android's interactive/brightness state to permit process repair.
        assertTrue(sc.restoreAndEstablishExitSafety())
        assertTrue(daemon.sent.contains("SCREEN ON"))
    }

    @Test fun poweredBacklightStillNeedsPositiveBrightnessToProveTheScreenLit() {
        backlight.level = 0
        assertEquals(false, controller(blPower = "0").first.observedLit())
    }

    @Test fun brightnessOnlyRouteCanProveLitFromItsAuthoritativeActuator() {
        val sc = ScreenController(
            backlight, power, FakeRootShell(), FakeDaemon(), wakeTap, ScreenOff.BRIGHTNESS_ZERO,
        )
        backlight.level = 120
        assertEquals(true, sc.observedLit())
        backlight.level = 0
        assertEquals(false, sc.observedLit())
    }

    // --- intendedOff transitions (what the never-blank watchdog keys on) ---
    @Test fun intendedOffSetOnSleepClearedOnWake() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        assertFalse(sc.isIntendedOff())
        sc.sleep(); assertTrue(sc.isIntendedOff())
        sc.wake(); assertFalse(sc.isIntendedOff())
    }

    @Test fun intendedOffOwnsBrightnessAgainstAutoActuation() {
        val sc = ScreenController(
            backlight, power, FakeRootShell(), FakeDaemon(), wakeTap, ScreenOff.BRIGHTNESS_ZERO,
        )
        backlight.level = 120
        sc.sleep()
        assertEquals(0, backlight.level)
        assertFalse(sc.actuateBrightnessIfOn { backlight.setBrightness(220) })
        assertEquals(0, backlight.level)
        sc.wake()
        assertTrue(sc.actuateBrightnessIfOn { backlight.setBrightness(220) })
        assertEquals(220, backlight.level)
    }

    @Test fun lockFreeWriteTrackingCannotInvertScreenAndAutomaticOrdering() {
        // ScreenController deliberately holds its transition monitor through backlight actuation. The
        // successful-write tracker must therefore stay lock-free while automatic policy waits for screen.
        val sc = ScreenController(
            backlight, power, FakeRootShell(), FakeDaemon(), wakeTap, ScreenOff.BRIGHTNESS_ZERO,
        )
        val writeTracker = BacklightWriteTracker()
        val screenActionEntered = CountDownLatch(1)
        val releaseScreenAction = CountDownLatch(1)
        val trackerMonitorHeld = CountDownLatch(1)
        val screenFinished = CountDownLatch(1)
        val automaticFinished = CountDownLatch(1)
        val screenWrite = Thread({
            sc.actuateBrightnessIfOn {
                screenActionEntered.countDown()
                releaseScreenAction.await()
                writeTracker.record(42L)
            }
            screenFinished.countDown()
        }, "screen-write-tracker-order-test").apply { isDaemon = true }
        val automaticWrite = Thread({
            synchronized(writeTracker) {
                trackerMonitorHeld.countDown()
                sc.actuateBrightnessIfOn { }
            }
            automaticFinished.countDown()
        }, "automatic-screen-order-test").apply { isDaemon = true }

        try {
            screenWrite.start()
            assertTrue(screenActionEntered.await(1, TimeUnit.SECONDS))
            automaticWrite.start()
            assertTrue(trackerMonitorHeld.await(1, TimeUnit.SECONDS))
            val blockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (automaticWrite.state != Thread.State.BLOCKED && System.nanoTime() < blockedDeadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, automaticWrite.state)
            releaseScreenAction.countDown()

            assertTrue("screen write must not wait on write-attribution state", screenFinished.await(2, TimeUnit.SECONDS))
            assertTrue("automatic write must then acquire the screen owner", automaticFinished.await(2, TimeUnit.SECONDS))
            screenWrite.join(1_000)
            automaticWrite.join(1_000)
            assertFalse(screenWrite.isAlive)
            assertFalse(automaticWrite.isAlive)
            assertEquals(42L, writeTracker.snapshot())
        } finally {
            releaseScreenAction.countDown()
            screenWrite.interrupt()
            automaticWrite.interrupt()
            screenWrite.join(1_000)
            automaticWrite.join(1_000)
        }
    }

    @Test fun generationSafeWakeRejectsAnObsoleteGesture() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        sc.sleep()
        val stale = sc.currentOffGeneration()!!
        sc.wake()
        sc.sleep()

        assertEquals(WakeOutcome.STALE_GENERATION, sc.wakeIfStillDark(stale))
        assertTrue(sc.isIntendedOff())
    }

    @Test fun generationSafeWakeActsExactlyOnceForTheCurrentOffState() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        sc.sleep()
        val current = sc.currentOffGeneration()!!

        assertEquals(WakeOutcome.WOKEN, sc.wakeIfStillDark(current))
        assertEquals(WakeOutcome.ALREADY_ON, sc.wakeIfStillDark(current))
        assertEquals(1, power.pulses)
    }

    @Test fun generationSafeWakeRevalidatesFeatureAdmissionInsideTheScreenLock() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        sc.sleep()
        val current = sc.currentOffGeneration()!!

        assertEquals(WakeOutcome.STALE_GENERATION, sc.wakeIfStillDark(current) { false })
        assertTrue(sc.isIntendedOff())
        assertEquals(0, power.pulses)
    }

    @Test fun automaticSleepReturnsOwnershipOnlyWhenItCreatesTheOffEpoch() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))

        val owned = sc.sleepAutomatically()

        assertTrue(owned != null)
        assertEquals(null, sc.sleepAutomatically())
        assertEquals(WakeOutcome.WOKEN, sc.wakeAutomaticallyIfOwned(owned!!))
        assertFalse(sc.isIntendedOff())
    }

    @Test fun automaticSleepCannotAdoptOrWakeAManualOff() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))

        sc.sleep()

        assertEquals(null, sc.sleepAutomatically())
        assertTrue(sc.isIntendedOff())
    }

    @Test fun laterManualSleepInvalidatesAutomaticOwnershipWithoutWaking() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        val owned = sc.sleepAutomatically()!!

        sc.sleep()

        assertEquals(WakeOutcome.STALE_GENERATION, sc.wakeAutomaticallyIfOwned(owned))
        assertTrue(sc.isIntendedOff())
        assertEquals(0, power.pulses)
    }

    @Test fun laterWakeAndOffEpochRejectsOldAutomaticOwner() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        val old = sc.sleepAutomatically()!!
        sc.wake()
        val current = sc.sleepAutomatically()!!

        assertEquals(WakeOutcome.STALE_GENERATION, sc.wakeAutomaticallyIfOwned(old))
        assertEquals(WakeOutcome.WOKEN, sc.wakeAutomaticallyIfOwned(current))
    }

    @Test fun automaticWakeRevalidatesPolicyAdmissionInsideScreenLock() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        val owned = sc.sleepAutomatically()!!

        assertEquals(WakeOutcome.STALE_GENERATION, sc.wakeAutomaticallyIfOwned(owned) { false })
        assertTrue(sc.isIntendedOff())
        assertEquals(0, power.pulses)
    }

    @Test fun observedPhysicalWakeClearsIntentAndInvalidatesQueuedGestureWithoutActuation() {
        val daemon = FakeDaemon(mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        val sc = ScreenController(backlight, power, FakeRootShell(), daemon, wakeTap, ScreenOff.DAEMON_BLPOWER)
        var completed = 0
        sc.onWakeCompleted = { completed++ }
        sc.sleep()
        val queuedGesture = sc.currentOffGeneration()!!

        assertTrue(sc.reconcileObservedLit(queuedGesture))

        assertFalse(sc.isIntendedOff())
        assertFalse(wakeTap.armed)
        assertEquals(1, completed)
        assertEquals(0, power.pulses)
        assertEquals(WakeOutcome.ALREADY_ON, sc.wakeIfStillDark(queuedGesture))
        assertEquals(listOf("SCREEN OFF"), daemon.sent)
    }

    @Test fun staleLitObservationCannotClearANewerOffGeneration() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        sc.sleep()
        val old = sc.currentOffGeneration()!!
        assertTrue(sc.noteObservedDark(old))
        sc.wake()
        sc.sleep()

        assertFalse(sc.reconcileObservedLit(old))
        assertTrue(sc.isIntendedOff())
        assertTrue(wakeTap.armed)
    }

    // --- screen-off tier selection ---
    @Test fun sleepUsesDaemonTierWhenAvailable() {
        val (sc, root) = controller(daemon = mapOf("SCREEN OFF" to "OK"))
        sc.sleep()
        assertTrue("daemon tier won — no su", root.ran.isEmpty())
        assertTrue("daemon tier won — no brightness dim", backlight.calls.isEmpty())
    }

    @Test fun sleepFallsToSuWhenNoDaemon() {
        val (sc, root) = controller(suRuns = true) // no daemon reply -> null; su write succeeds
        sc.sleep()
        assertTrue("expected an su bl_power write, got ${root.ran}", root.ran.any { it.contains("bl_power") })
        assertTrue("su tier won — brightness untouched", backlight.calls.isEmpty())
    }

    @Test fun sleepFallsToBrightnessWhenNoSuNoDaemon() {
        val (sc, _) = controller(suRuns = false) // no daemon, su write fails
        sc.sleep()
        assertTrue("expected a raw dim to 0, got ${backlight.calls}", backlight.calls.contains("raw:0"))
    }

    @Test fun brightnessRouteDoesNotProbePrivilegedActuators() {
        val root = FakeRootShell(runResult = true)
        val daemon = FakeDaemon(mapOf("SCREEN OFF" to "OK"))
        val sc = ScreenController(backlight, power, root, daemon, wakeTap, ScreenOff.BRIGHTNESS_ZERO)

        sc.sleep()

        assertTrue(root.ran.isEmpty())
        assertTrue(daemon.sent.isEmpty())
        assertTrue(backlight.calls.contains("raw:0"))
    }

    @Test fun suRouteIsAttemptedBeforeDaemonFallback() {
        val root = FakeRootShell(runResult = true)
        val daemon = FakeDaemon(mapOf("SCREEN OFF" to "OK"))
        val sc = ScreenController(backlight, power, root, daemon, wakeTap, ScreenOff.SU_BLPOWER)

        sc.sleep()

        assertTrue(root.ran.any { it.contains("bl_power") })
        assertTrue(daemon.sent.isEmpty())
    }

    // --- wake always pulses the wakelock ---
    @Test fun wakePulsesOnDaemonTier() {
        val (sc, _) = controller(daemon = mapOf("SCREEN ON" to "OK"))
        sc.wake()
        assertEquals(1, power.pulses)
    }

    @Test fun wakeFallsToBrightnessAndPulses() {
        val (sc, _) = controller(suRuns = false) // no daemon, su fails -> brightness fallback
        sc.wake()
        assertTrue("expected a brightness set, got ${backlight.calls}", backlight.calls.any { it.startsWith("set:") })
        assertEquals(1, power.pulses)
    }

    @Test fun rendererIsNotifiedAfterDaemonWakeAndWakelockPulse() {
        val daemon = FakeDaemon(mapOf("SCREEN ON" to "OK"))
        val sc = ScreenController(backlight, power, FakeRootShell(), daemon, wakeTap, ScreenOff.DAEMON_BLPOWER)
        assertRendererWakeAfter(sc) { daemon.sent.contains("SCREEN ON") }
    }

    @Test fun rendererIsNotifiedAfterRootWakeAndWakelockPulse() {
        val root = FakeRootShell(runResult = true)
        val sc = ScreenController(backlight, power, root, FakeDaemon(), wakeTap, ScreenOff.SU_BLPOWER)
        assertRendererWakeAfter(sc) { root.ran.any { it.contains("bl_power") } }
    }

    @Test fun rendererIsNotifiedAfterBrightnessWakeAndWakelockPulse() {
        val sc = ScreenController(backlight, power, FakeRootShell(runResult = false), FakeDaemon(), wakeTap, ScreenOff.BRIGHTNESS_ZERO)
        assertRendererWakeAfter(sc) { backlight.calls.any { it.startsWith("set:") } }
    }

    // --- touch-to-wake: a real screen-off arms the tap; wake disarms it ---
    @Test fun sleepArmsWakeTapAndWakeDisarmsIt() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        sc.sleep()
        assertTrue("screen-off must arm touch-to-wake", wakeTap.armed)
        sc.wake()
        assertFalse("wake must disarm touch-to-wake", wakeTap.armed)
    }

    @Test fun tapWhileDarkWakesAndNotifies() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        var notified = false
        var proof: AutomaticOffEpoch? = AutomaticOffEpoch(-1L)
        sc.onWakeByTap = { epoch -> notified = true; proof = epoch }
        sc.sleep()
        assertTrue(sc.isIntendedOff())
        wakeTap.fireTap()                     // simulate a touch on the dark screen
        assertFalse("tap must wake (clear intendedOff)", sc.isIntendedOff())
        assertFalse("tap must disarm the tap", wakeTap.armed)
        assertTrue("tap must notify so HA tracks screen=ON", notified)
        assertNull("a manual OFF never produces automatic policy proof", proof)
    }

    @Test fun automaticTapReportsTheExactOwnedEpoch() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        var proof: AutomaticOffEpoch? = null
        sc.onWakeByTap = { proof = it }
        val epoch = checkNotNull(sc.sleepAutomatically())

        wakeTap.fireTap()

        assertEquals(epoch, proof)
        assertFalse(sc.isIntendedOff())
    }

    @Test fun delayedTapFromAnOlderEpochCannotWakeANewerManualOff() {
        val delayedTap = CapturingWakeTap()
        val daemon = FakeDaemon(mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        val sc = ScreenController(
            backlight,
            power,
            FakeRootShell(),
            daemon,
            delayedTap,
            ScreenOff.DAEMON_BLPOWER,
        )
        var notifications = 0
        sc.onWakeByTap = { notifications++ }
        sc.sleep()
        val oldWorker = delayedTap.callbacks.single()

        // A later manual OFF owns a new generation before the old observer's worker gets CPU time.
        sc.sleep()
        assertEquals(2, delayedTap.callbacks.size)
        oldWorker()

        assertTrue("old tap must not wake the replacement manual OFF", sc.isIntendedOff())
        assertEquals(0, power.pulses)
        assertEquals(0, notifications)
        delayedTap.callbacks.last().invoke()
        assertFalse(sc.isIntendedOff())
        assertEquals(1, power.pulses)
        assertEquals(1, notifications)
    }

    @Test fun delayedTapFromAnOlderEpochCannotWakeANewerAutomaticOff() {
        val delayedTap = CapturingWakeTap()
        val daemon = FakeDaemon(mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        val sc = ScreenController(
            backlight,
            power,
            FakeRootShell(),
            daemon,
            delayedTap,
            ScreenOff.DAEMON_BLPOWER,
        )
        var notifications = 0
        sc.onWakeByTap = { notifications++ }
        sc.sleepAutomatically()
        val oldWorker = delayedTap.callbacks.single()

        sc.wake()
        sc.sleepAutomatically()
        assertEquals(2, delayedTap.callbacks.size)
        oldWorker()

        assertTrue("old tap must not wake the replacement automatic OFF", sc.isIntendedOff())
        assertEquals("only the explicit intervening wake may pulse", 1, power.pulses)
        assertEquals(0, notifications)
        delayedTap.callbacks.last().invoke()
        assertFalse(sc.isIntendedOff())
        assertEquals(2, power.pulses)
        assertEquals(1, notifications)
    }

    // --- guaranteed-wake degradation: no overlay -> dim instead of a true (unwakeable) off ---
    @Test fun sleepDimsInsteadOfDarkWhenNoWakeGuarantee() {
        wakeTap.canArm = false
        val (sc, root) = controller(daemon = mapOf("SCREEN OFF" to "OK"))
        sc.sleep()
        assertTrue("must dim to a visible floor, got ${backlight.calls}", backlight.calls.any { it.startsWith("set:") })
        assertFalse("must NOT raw-dim to 0 (that's blank)", backlight.calls.contains("raw:0"))
        assertTrue("must NOT power the backlight off via daemon/su", root.ran.isEmpty())
        assertFalse("must not arm a tap it can't arm", wakeTap.armed)
        assertTrue("still an intended off", sc.isIntendedOff())
        assertFalse("visible safety dim is not an external wake", sc.reconcileObservedLit(sc.currentOffGeneration()))
        assertTrue(sc.isIntendedOff())
    }

    @Test fun sleepDimsWhenWakeWatcherCannotConfirmAttachment() {
        wakeTap.armSucceeds = false
        val daemon = FakeDaemon(mapOf("SCREEN OFF" to "OK"))
        val root = FakeRootShell(runResult = true)
        val sc = ScreenController(backlight, power, root, daemon, wakeTap, ScreenOff.DAEMON_BLPOWER)

        sc.sleep()

        assertEquals(listOf("set:10"), backlight.calls)
        assertTrue("an unacknowledged watcher must block daemon screen-off", daemon.sent.isEmpty())
        assertTrue("an unacknowledged watcher must block su screen-off", root.ran.isEmpty())
        assertFalse(wakeTap.armed)
    }

    @Test fun closeRestoresAnIntentionallyDarkScreenBeforeReleasingWakeTap() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        sc.sleep()
        assertTrue(wakeTap.armed)

        sc.close()

        assertFalse(sc.isIntendedOff())
        assertFalse(wakeTap.armed)
        assertEquals(1, power.pulses)
    }

    @Test fun closeAdmissionRejectsLateScreenOffAndBrightnessMutation() {
        val daemon = FakeDaemon(mapOf("SCREEN OFF" to "OK"))
        val sc = ScreenController(backlight, power, FakeRootShell(), daemon, wakeTap, ScreenOff.DAEMON_BLPOWER)

        sc.closeAdmission()
        sc.sleep()

        assertFalse(sc.isIntendedOff())
        assertTrue(daemon.sent.isEmpty())
        assertFalse(sc.actuateBrightnessIfOn { backlight.setBrightness(0) })
        assertEquals(160, backlight.level)
    }

    /** Retains callbacks after disarm to model an OverlayWakeTap worker already queued on another thread. */
    private class CapturingWakeTap : WakeTap {
        val callbacks = mutableListOf<() -> Unit>()
        override fun canArm() = true
        override fun arm(onTap: () -> Unit): Boolean {
            callbacks += onTap
            return true
        }
        override fun disarm() = Unit
    }

    @Test fun exitRecoveryRetriesWakeUntilPrivilegedReadbackIsAffirmativelyLit() {
        val daemon = ExitRecoveryDaemon()
        val sc = ScreenController(
            backlight,
            power,
            FakeRootShell(runResult = false),
            daemon,
            wakeTap,
            ScreenOff.DAEMON_BLPOWER,
        )
        backlight.level = 120

        assertFalse(sc.restoreAndEstablishExitSafety())
        daemon.wakeSucceeds = true
        assertTrue(sc.restoreAndEstablishExitSafety())
        assertEquals(2, daemon.sent.count { it == "SCREEN ON" })
    }

    @Test fun concurrentWakeWaitsForTheInFlightSleepTransition() {
        val daemon = BlockingScreenDaemon()
        val sc = ScreenController(backlight, power, FakeRootShell(), daemon, wakeTap, ScreenOff.DAEMON_BLPOWER)
        val sleep = Thread({ sc.sleep() }, "test-screen-sleep").apply { start() }
        assertTrue(daemon.offEntered.await(1, TimeUnit.SECONDS))
        val wakeStarted = CountDownLatch(1)
        val wake = Thread({ wakeStarted.countDown(); sc.wake() }, "test-screen-wake").apply { start() }
        assertTrue(wakeStarted.await(1, TimeUnit.SECONDS))

        assertFalse("wake actuator must not overlap the blocked off actuator", daemon.onEntered.await(100, TimeUnit.MILLISECONDS))
        daemon.releaseOff.countDown()
        sleep.join(1_000)
        wake.join(1_000)

        assertFalse(sleep.isAlive)
        assertFalse(wake.isAlive)
        assertEquals(listOf("SCREEN OFF", "SCREEN ON"), daemon.sent.toList())
        assertFalse(sc.isIntendedOff())
    }

    private class BlockingScreenDaemon : Daemon {
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val offEntered = CountDownLatch(1)
        val onEntered = CountDownLatch(1)
        val releaseOff = CountDownLatch(1)

        override fun available() = true

        override fun send(cmd: String): String? {
            sent += cmd
            if (cmd == "SCREEN OFF") {
                offEntered.countDown()
                assertTrue(releaseOff.await(1, TimeUnit.SECONDS))
            } else if (cmd == "SCREEN ON") {
                onEntered.countDown()
            }
            return "OK"
        }

        override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult = DaemonLongResult.NotSubmitted
        override fun sendBytes(cmd: String): ByteArray? = null
    }

    private class ExitRecoveryDaemon : Daemon {
        val sent = mutableListOf<String>()
        var wakeSucceeds = false
        private var blPower = 4

        override fun available() = true

        override fun send(cmd: String): String? {
            sent += cmd
            return when (cmd) {
                "BLPOWER" -> blPower.toString()
                "SCREEN ON" -> if (wakeSucceeds) {
                    blPower = 0
                    "OK"
                } else {
                    null
                }
                else -> null
            }
        }

        override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult = DaemonLongResult.NotSubmitted
        override fun sendBytes(cmd: String): ByteArray? = null
    }

    /**
     * `recoverUnexpectedDark` checks interactivity twice: once cheaply before the potentially slow
     * privileged read, and again inside the transition monitor. The inner check is what makes the
     * decision, so the outer one changes no outcome and only the ABSENCE of the probe distinguishes
     * it — without this, removing it is unfalsifiable and the whole guard is unproven.
     */
    @Test fun neverBlankDoesNotProbeHardwareOnANonInteractiveDevice() {
        power.interactive = false
        val root = FakeRootShell(outputs = mapOf("bl_power" to "4"))
        val sc = ScreenController(backlight, power, root, FakeDaemon(), wakeTap, ScreenOff.SU_BLPOWER)
        assertFalse("Android's own sleep is not ha-paneld's to undo", sc.recoverUnexpectedDark())
        assertTrue("a sleeping device must not be probed at all, got ${root.outputRan}", root.outputRan.isEmpty())
    }

    // --- ScreenOff.KEYEVENT: Android's own sleep, for panels with no backlight class -------------
    //
    // This route differs from bl_power in kind, not degree: it leaves Android noninteractive, so the
    // state read-back, the never-blank guarantee and the watchdog's reach all change with it.

    /**
     * Build a KEYEVENT-route controller. [rootInjects]/[daemonInjects] say what that transport
     * ANSWERED; [rootEffective]/[daemonEffective] say what it actually DID to Android. The two axes
     * are fully independent, and every one of the four combinations is reachable on real hardware:
     * `input` is an app_process wrapper that has been reported exiting zero without acting, and the
     * daemon's reply is routinely abandoned by the client's own read timeout while the injection it
     * asked for goes on to succeed.
     */
    private fun keyeventController(
        rootInjects: Boolean = true,
        rootEffective: Boolean = true,
        daemonInjects: Boolean = false,
        daemonEffective: Boolean = true,
        nap: (Long) -> Unit = {},
    ): Triple<ScreenController, FakeRootShell, FakeDaemon> {
        val apply = { cmd: String, effective: Boolean ->
            if (effective) {
                if (cmd.contains("223") || cmd.endsWith("SLEEP")) power.interactive = false
                if (cmd.contains("224") || cmd.endsWith("WAKEUP")) power.interactive = true
            }
        }
        val root = FakeRootShell(
            runResult = rootInjects,
            onRun = { cmd -> apply(cmd, rootEffective) },
        )
        val daemon = FakeDaemon(
            replies = if (daemonInjects) mapOf("KEYEVENT SLEEP" to "OK", "KEYEVENT WAKEUP" to "OK") else emptyMap(),
            onSend = { cmd -> apply(cmd, daemonEffective) },
        )
        val sc = ScreenController(
            backlight, power, root, daemon, wakeTap, ScreenOff.KEYEVENT, nap = nap,
        )
        return Triple(sc, root, daemon)
    }

    @Test fun keyeventSleepInjectsThroughRootAndProvesAndroidWentNoninteractive() {
        val (sc, root, daemon) = keyeventController()
        sc.sleep()
        assertEquals(listOf("input keyevent 223"), root.ran)
        assertTrue("a working root injection must not also consult the daemon", daemon.sent.isEmpty())
        assertTrue(sc.isIntendedOff())
        assertTrue("noninteractive IS this route's dark", sc.looksDark())
        assertTrue("a confirmed off must not degrade to the dim floor", backlight.calls.isEmpty())
    }

    @Test fun keyeventSleepFallsToTheDaemonWhenRootCannotInject() {
        val (sc, root, daemon) = keyeventController(
            rootInjects = false, rootEffective = false, daemonInjects = true,
        )
        sc.sleep()
        assertEquals(listOf("input keyevent 223"), root.ran)
        assertEquals(listOf("KEYEVENT SLEEP"), daemon.sent)
        assertTrue(sc.looksDark())
    }

    /** A submitted request is not a state change. An injector that reports success while Android stays
     *  interactive must be treated as failed, so the next transport is tried. */
    @Test fun keyeventSleepRejectsAnInjectorThatOnlyClaimedToWork() {
        val (sc, root, daemon) = keyeventController(rootEffective = false, daemonInjects = true)
        sc.sleep()
        assertEquals(listOf("input keyevent 223"), root.ran)
        assertEquals(listOf("KEYEVENT SLEEP"), daemon.sent)
        assertFalse("the daemon actually slept the panel", power.interactive)
    }

    /**
     * The mirror case, and the one that actually bites on a daemon-only panel: the transport reports
     * failure while its injection lands anyway. A lost reply is indistinguishable from a refusal, so
     * the answer is read for nothing and the state is read after every submitted attempt. Getting this
     * wrong dims a panel that is already asleep and records the wrong route to undo.
     */
    @Test fun keyeventSleepIsConfirmedWhenTheInjectorActedButItsAnswerWasLost() {
        backlight.level = 200
        val (sc, root, daemon) = keyeventController(
            rootInjects = false, rootEffective = true,
            daemonInjects = false, daemonEffective = false,
        )
        sc.sleep()
        assertEquals(listOf("input keyevent 223"), root.ran)
        assertTrue("a confirmed sleep must not be re-submitted anywhere", daemon.sent.isEmpty())
        assertTrue(sc.isIntendedOff())
        assertTrue("Android is noninteractive, whatever the transport claimed", sc.looksDark())
        assertTrue("an actuated sleep must not be recorded as a failed one", backlight.calls.isEmpty())
    }

    /**
     * A confirmation window must be at least as long as its transport's actuation bound. The helper
     * lets a wedged `input` run for four seconds before killing it, so a daemon injection can still
     * land long after this client abandoned its half-second read. Confirming for any less would dim a
     * panel that was about to sleep on its own, then record a route that never ran.
     */
    @Test fun keyeventDaemonConfirmationOutlastsTheHelperInjectionDeadline() {
        backlight.level = 200
        var naps = 0
        val (sc, _, daemon) = keyeventController(
            rootInjects = false, rootEffective = false,
            daemonInjects = false, daemonEffective = false,
            nap = { naps++; if (naps == 25) power.interactive = false },
        )
        sc.sleep()
        assertEquals(listOf("KEYEVENT SLEEP"), daemon.sent)
        assertTrue("root's shorter window must not be the one that gave up on the daemon", naps > 10)
        assertTrue("a sleep that landed inside the helper's own deadline is still a sleep", sc.looksDark())
        assertTrue("no dim floor is warranted for a slow but successful sleep", backlight.calls.isEmpty())
    }

    /** When no transport can prove the state changed, the panel must end visibly lit at the floor
     *  rather than recorded as dark — never-blank, and never a claimed off nobody can see. */
    @Test fun keyeventSleepDegradesToTheDimFloorWhenNothingProvesTheStateChanged() {
        backlight.level = 200
        val (sc, _, _) = keyeventController(
            rootEffective = false, daemonInjects = true, daemonEffective = false,
        )
        sc.sleep()
        assertEquals(listOf("set:10"), backlight.calls)
        assertTrue("the panel is still interactive, so it is not dark", power.interactive)
        assertFalse(sc.looksDark())
    }

    /**
     * The overlay must never be armed on this route. A noninteractive device does not dispatch touches
     * to windows, so an armed overlay would be a never-blank guarantee that cannot fire — and, worse,
     * a reason to go dark that was never true.
     */
    @Test fun keyeventSleepNeverArmsTheTouchOverlay() {
        wakeTap.canArm = true
        val (sc, _, _) = keyeventController()
        sc.sleep()
        assertFalse("this route's way back is the wakelock pulse, not a touch overlay", wakeTap.armed)
    }

    /** ...and its absence must not block the off either: an unavailable overlay is irrelevant here. */
    @Test fun keyeventSleepProceedsWithoutAnyOverlayPermission() {
        wakeTap.canArm = false
        val (sc, _, _) = keyeventController()
        sc.sleep()
        assertTrue(sc.looksDark())
        assertTrue("no dim-floor degradation is warranted", backlight.calls.isEmpty())
    }

    /** Waking into a credential screen strands a wall panel exactly as a locked reboot does, so a
     *  secured device is refused rather than slept. */
    @Test fun keyeventSleepRefusesASecuredDeviceAndDimsInstead() {
        backlight.level = 200
        power.deviceSecure = true
        val (sc, root, daemon) = keyeventController()
        sc.sleep()
        assertTrue("no key may be injected on a secured device", root.ran.isEmpty())
        assertTrue(daemon.sent.isEmpty())
        assertEquals(listOf("set:10"), backlight.calls)
        assertTrue("the device stays interactive", power.interactive)
    }

    @Test fun keyeventWakeInjectsWakeupAndPulses() {
        val (sc, root, _) = keyeventController()
        sc.sleep()
        sc.wake()
        assertEquals(listOf("input keyevent 223", "input keyevent 224"), root.ran)
        assertTrue(power.interactive)
        assertEquals(1, power.pulses)
        assertFalse(sc.isIntendedOff())
    }

    /**
     * The guarantee that this route can always undo its own off: the wakelock pulse needs no privilege,
     * so a wake still happens when root and the helper have both gone. It must also leave brightness
     * alone — this route never changed it, and restoring a remembered level would move it for nothing.
     */
    @Test fun keyeventWakeStillPulsesWhenEveryPrivilegedInjectorIsGone() {
        val (sc, root, _) = keyeventController()
        sc.sleep()
        assertTrue(sc.looksDark())
        root.ran.clear()
        backlight.calls.clear()
        root.runResult = false          // root and the helper have both gone since the panel slept
        sc.wake()
        assertEquals(listOf("input keyevent 224"), root.ran)
        assertEquals("the unprivileged wakelock pulse is what guarantees this wake", 1, power.pulses)
        assertTrue("wake must not rewrite a brightness this route never touched", backlight.calls.isEmpty())
        assertFalse(sc.isIntendedOff())
    }

    /** A degraded off DID move the brightness, so the wake after it has to move it back — the dim floor
     *  must not be a one-way trip on the route that returns before the brightness restore. */
    @Test fun keyeventDimFloorIsRestoredByTheNextWake() {
        backlight.level = 200
        val (sc, _, _) = keyeventController(
            rootEffective = false, daemonInjects = true, daemonEffective = false,
        )
        sc.sleep()
        assertEquals(listOf("set:10"), backlight.calls)
        backlight.calls.clear()
        sc.wake()
        assertEquals(listOf("set:200"), backlight.calls)
    }

    /** The never-blank watchdog exists to undo a backlight ha-paneld blanked behind the framework's
     *  back. On this route "dark" IS Android's own sleep, so the guard must stay out of it entirely. */
    @Test fun neverBlankGuardDoesNotFightAndroidSleepOnTheKeyeventRoute() {
        val (sc, root, daemon) = keyeventController()
        sc.sleep()
        root.ran.clear()
        daemon.sent.clear()
        assertFalse("an intended off is left alone", sc.recoverUnexpectedDark())
        // ...and so is a noninteractive state ha-paneld never asked for.
        val fresh = keyeventController().first
        power.interactive = false
        assertFalse(fresh.recoverUnexpectedDark())
        assertEquals(0, power.pulses)
    }

    @Test fun keyeventRouteReadsAndroidInteractivityRatherThanProbingBacklightPower() {
        val (sc, root, daemon) = keyeventController()
        backlight.level = 0
        power.interactive = true
        assertFalse("a zero brightness is not this route's off state", sc.looksDark())
        assertEquals(true, sc.observedLit())
        power.interactive = false
        assertTrue(sc.looksDark())
        assertEquals(false, sc.observedLit())
        assertTrue("no bl_power probe belongs on a panel with no backlight class",
            root.outputRan.isEmpty() && daemon.sent.isEmpty())
    }

    /**
     * A wake nobody here performed has to be adopted without a broker.
     *
     * On this route Android can be woken by its power key or a wake-capable touchscreen with ha-paneld
     * uninvolved, and until this seam existed the only place that adopted such a wake was the MQTT
     * sync tick. This test constructs no bridge, no broker and no publisher at all: if the recovery
     * needed any of them, the renderer would still be frozen at the end of it, which on a lit panel
     * means a stale dashboard nobody can tap.
     */
    @Test fun physicalWakeIsAdoptedWithNoMqttInvolvement() {
        val awakeStates = mutableListOf<Boolean>()
        val listener: (Boolean) -> Unit = { awakeStates.add(it) }
        val (sc, _, _) = keyeventController()
        BuiltinDashboard.setScreenListener(listener)
        try {
            sc.sleep()
            assertEquals("the renderer freezes with the panel", listOf(false), awakeStates)
            power.interactive = true          // a person pressed the power key
            assertTrue(sc.reconcilePhysicalWake())
            assertEquals("and must thaw again the moment the panel is lit", listOf(false, true), awakeStates)
        } finally {
            BuiltinDashboard.clearScreenListener(listener)
        }
        assertFalse("the off intent dies with the wake that ended it", sc.isIntendedOff())
        assertNull("nothing is left owning an off epoch", sc.currentOffGeneration())
    }

    /** The adoption is evidence-led, not signal-led: a screen-on announcement for a panel that is
     *  still observably dark must not clear the off intent and strand it dark-but-not-intended. */
    @Test fun physicalWakeIsRefusedWhileThePanelIsStillObservablyDark() {
        val (sc, _, _) = keyeventController()
        sc.sleep()
        assertFalse("still noninteractive, so nothing was woken", sc.reconcilePhysicalWake())
        assertTrue(sc.isIntendedOff())
    }

    /** And with no deliberate off in flight there is nothing to adopt, so a spurious announcement is
     *  inert rather than a generation bump. */
    @Test fun physicalWakeIsInertWhenNothingIsIntentionallyOff() {
        val (sc, _, _) = keyeventController()
        assertFalse(sc.reconcilePhysicalWake())
        assertFalse(sc.isIntendedOff())
    }

    /** Epoch ownership is route-independent, so an automatic off on this route must still refuse a
     *  wake owned by an older generation. */
    @Test fun keyeventAutomaticOffKeepsItsEpochOwnership() {
        val (sc, _, _) = keyeventController()
        val epoch = requireNonNull(sc.sleepAutomatically())
        sc.wake()
        power.interactive = false
        sc.sleep()
        assertEquals(WakeOutcome.STALE_GENERATION, sc.wakeAutomaticallyIfOwned(epoch))
        assertTrue("the newer manual off still owns the panel", sc.isIntendedOff())
    }

    private fun <T : Any> requireNonNull(value: T?): T = value ?: throw AssertionError("expected an epoch")

    private class PrivilegeLossAfterOffDaemon : Daemon {
        val sent = mutableListOf<String>()
        var privilegeLost = false

        override fun available() = !privilegeLost

        override fun send(cmd: String): String? {
            sent += cmd
            if (privilegeLost) return null
            return when (cmd) {
                "SCREEN OFF" -> "OK"
                "BLPOWER" -> "4"
                else -> null
            }
        }

        override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult = DaemonLongResult.NotSubmitted
        override fun sendBytes(cmd: String): ByteArray? = null
    }
}
