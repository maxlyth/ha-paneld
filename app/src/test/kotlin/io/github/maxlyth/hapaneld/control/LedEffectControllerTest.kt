package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.hardware.LedEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle invariants for [LedEffectController], driven on a REAL background dispatcher (the cancel-and-
 * join in start/stop runs under runBlocking, so a virtual-time TestScope would deadlock). The frame math
 * itself is covered by LedEffectsTest; here we prove the loop is a single, cleanly-stoppable owner:
 * stop() leaves no stale frame, and start() replaces (never orphans) a running loop.
 */
class LedEffectControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() {
        scope.cancel()
    }

    /** Records every HAL write so the test can reason about ordering + counts across threads. */
    private class RecordingLed : LedController {
        val writes = CopyOnWriteArrayList<String>()
        override fun available() = true
        override fun colorCapable() = true
        override fun setRgb(r: Int, g: Int, b: Int) { writes.add("rgb:$r,$g,$b") }
        override fun off() { writes.add("off") }
    }

    private fun awaitUntil(timeoutMs: Long = 2_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return
            Thread.sleep(5)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    @Test fun startDrivesFramesToTheLed() {
        val led = RecordingLed()
        val fx = LedEffectController(led, scope)
        fx.start(LedEffects.Effect.STROBE, r = 200, g = 0, b = 0, br = 255)
        awaitUntil { led.writes.any { it == "rgb:200,0,0" } } // a lit red frame reached the HAL
        assertTrue("effect is running", fx.running())
        fx.stop()
    }

    @Test fun stopHaltsWithNoFrameAfterItReturns() {
        val led = RecordingLed()
        val fx = LedEffectController(led, scope)
        fx.start(LedEffects.Effect.STROBE, r = 200, g = 0, b = 0, br = 255)
        awaitUntil { led.writes.size >= 2 }         // let a couple of frames run

        fx.stop()                                    // cancel-and-join: no frame may fire after this returns
        val countAtReturn = led.writes.size
        assertFalse("not running after stop", fx.running())

        // If a stale in-flight frame could survive the join, it would land within a few stepMs (80ms).
        Thread.sleep(300)
        assertEquals("no LED write after stop() returned", countAtReturn, led.writes.size)
    }

    @Test fun startReplacesTheRunningEffectWithoutOrphaningIt() {
        val led = RecordingLed()
        val fx = LedEffectController(led, scope)

        fx.start(LedEffects.Effect.STROBE, r = 200, g = 0, b = 0, br = 255)   // red
        awaitUntil { led.writes.any { it == "rgb:200,0,0" } }

        fx.start(LedEffects.Effect.STROBE, r = 0, g = 200, b = 0, br = 255)   // green replaces red
        awaitUntil { led.writes.any { it == "rgb:0,200,0" } }
        val firstGreen = led.writes.indexOfFirst { it == "rgb:0,200,0" }

        fx.stop()

        // Once the green loop is live, the red (old) loop must be gone — no red frame may appear after it.
        val redAfterGreen = led.writes.withIndex().any { (i, w) -> i > firstGreen && w == "rgb:200,0,0" }
        assertFalse("old effect loop was orphaned (red frame after the green switch)", redAfterGreen)
    }
}
