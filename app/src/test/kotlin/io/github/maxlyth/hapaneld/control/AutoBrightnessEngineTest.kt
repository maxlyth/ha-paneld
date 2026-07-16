package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class AutoBrightnessEngineTest {
    @Test fun firstSampleSnapsAndUsesTheActuatorVisibleFloor() {
        val sample = AutoBrightnessEngine().submit(lux = 0f, enabled = true, writable = true, bias = 0)!!

        assertEquals(0f, sample.smoothed)
        assertEquals(BrightnessController.MIN_VISIBLE, sample.target)
        assertEquals(BrightnessController.MIN_VISIBLE, sample.applied)
        assertEquals(BrightnessController.MIN_VISIBLE, sample.toSet)
    }

    @Test fun aLargeStepUsesFastAttack() {
        val engine = AutoBrightnessEngine()
        engine.submit(lux = 10f, enabled = true, writable = true, bias = 0)

        val sample = engine.submit(lux = 100f, enabled = true, writable = true, bias = 0)!!

        assertEquals(64f, sample.smoothed)
    }

    @Test fun aSmallDriftUsesSlowSmoothingAndDeadband() {
        val engine = AutoBrightnessEngine()
        val first = engine.submit(lux = 100f, enabled = true, writable = true, bias = 0)!!

        val sample = engine.submit(lux = 101f, enabled = true, writable = true, bias = 0)!!

        assertTrue(sample.smoothed!! in 100f..101f)
        assertEquals(first.applied, sample.applied)
        assertNull(sample.toSet)
    }

    @Test fun disablingResetsStateSoReenableSnaps() {
        val engine = AutoBrightnessEngine()
        engine.submit(lux = 10f, enabled = true, writable = true, bias = 0)
        engine.submit(lux = 10f, enabled = false, writable = false, bias = 0)

        val sample = engine.submit(lux = 100f, enabled = true, writable = true, bias = 0)!!

        assertEquals(100f, sample.smoothed)
    }

    @Test fun idleAndInvalidSamplesDoNotProduceCommands() {
        val engine = AutoBrightnessEngine()

        assertNull(engine.submit(Float.NaN, enabled = true, writable = true, bias = 0))
        assertNull(engine.submit(Float.POSITIVE_INFINITY, enabled = true, writable = true, bias = 0))
        assertNull(engine.submit(-1f, enabled = true, writable = true, bias = 0))
        assertNull(engine.submit(10f, enabled = true, writable = false, bias = 0)!!.toSet)
    }

    @Test fun biasStillClampsToTheSharedVisibleRange() {
        val low = AutoBrightnessEngine().submit(lux = 0f, enabled = true, writable = true, bias = -255)!!
        val high = AutoBrightnessEngine().submit(lux = 1000f, enabled = true, writable = true, bias = 255)!!

        assertEquals(BrightnessController.MIN_VISIBLE, low.toSet)
        assertEquals(255, high.toSet)
    }

    @Test fun computeAndHardwareActuationRemainOrderedAcrossConcurrentSources() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<Int>()
        val actuator = AutoBrightnessActuator(
            enabled = { true }, writable = { true }, bias = { 0 },
            actuationGate = { action -> action(); true },
            applyBrightness = { value ->
                writes += value
                if (writes.size == 1) {
                    firstEntered.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
            },
        )
        val first = Thread { actuator.submitLux(10f) }.apply { start() }
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        val second = Thread { actuator.submitLux(100f) }.apply { start() }
        Thread.sleep(30)
        assertEquals(1, writes.size)
        releaseFirst.countDown()
        first.join(2_000)
        second.join(2_000)

        val reference = AutoBrightnessEngine()
        val expected = listOf(
            reference.submit(10f, true, true, 0)!!.toSet,
            reference.submit(100f, true, true, 0)!!.toSet,
        )
        assertEquals(expected, writes)
    }

    @Test fun screenIntentGateSuppressesActuationButRetainsLatestLux() {
        val writes = mutableListOf<Int>()
        val actuator = AutoBrightnessActuator(
            enabled = { true }, writable = { true }, bias = { 0 },
            actuationGate = { false }, applyBrightness = writes::add,
        )
        assertEquals(AutoBrightnessSample(), actuator.submitLux(321f))
        assertTrue(writes.isEmpty())
        assertEquals(321f, actuator.latestLux())
    }
}
