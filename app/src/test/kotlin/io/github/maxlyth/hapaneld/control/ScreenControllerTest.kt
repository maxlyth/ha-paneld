package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        return ScreenController(backlight, power, root, FakeDaemon(daemon), wakeTap) to root
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

    // --- intendedOff transitions (what the never-blank watchdog keys on) ---
    @Test fun intendedOffSetOnSleepClearedOnWake() {
        val (sc, _) = controller(daemon = mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK"))
        assertFalse(sc.isIntendedOff())
        sc.sleep(); assertTrue(sc.isIntendedOff())
        sc.wake(); assertFalse(sc.isIntendedOff())
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
        val sc = ScreenController(backlight, power, FakeRootShell(), daemon, wakeTap)
        assertRendererWakeAfter(sc) { daemon.sent.contains("SCREEN ON") }
    }

    @Test fun rendererIsNotifiedAfterRootWakeAndWakelockPulse() {
        val root = FakeRootShell(runResult = true)
        val sc = ScreenController(backlight, power, root, FakeDaemon(), wakeTap)
        assertRendererWakeAfter(sc) { root.ran.any { it.contains("bl_power") } }
    }

    @Test fun rendererIsNotifiedAfterBrightnessWakeAndWakelockPulse() {
        val sc = ScreenController(backlight, power, FakeRootShell(runResult = false), FakeDaemon(), wakeTap)
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
        sc.onWakeByTap = { notified = true }
        sc.sleep()
        assertTrue(sc.isIntendedOff())
        wakeTap.fireTap()                     // simulate a touch on the dark screen
        assertFalse("tap must wake (clear intendedOff)", sc.isIntendedOff())
        assertFalse("tap must disarm the tap", wakeTap.armed)
        assertTrue("tap must notify so HA tracks screen=ON", notified)
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
    }

    @Test fun sleepDimsWhenWakeWatcherCannotConfirmAttachment() {
        wakeTap.armSucceeds = false
        val daemon = FakeDaemon(mapOf("SCREEN OFF" to "OK"))
        val root = FakeRootShell(runResult = true)
        val sc = ScreenController(backlight, power, root, daemon, wakeTap)

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

    @Test fun concurrentWakeWaitsForTheInFlightSleepTransition() {
        val daemon = BlockingScreenDaemon()
        val sc = ScreenController(backlight, power, FakeRootShell(), daemon, wakeTap)
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
}
