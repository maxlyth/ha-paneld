package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    // --- looksDark polarity ---
    @Test fun looksDarkTrueWhenBlPowerOff() = assertTrue(controller(blPower = "4").first.looksDark())

    @Test fun looksDarkFalseWhenBlPowerOn() = assertFalse(controller(blPower = "0").first.looksDark())

    @Test fun looksDarkFallsBackToBrightnessWhenBlPowerUnreadable() {
        backlight.level = 0
        assertTrue("brightness 0 with unknown bl_power reads dark", controller(blPower = null).first.looksDark())
        backlight.level = 120
        assertFalse("brightness >0 with unknown bl_power reads lit", controller(blPower = null).first.looksDark())
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
}
