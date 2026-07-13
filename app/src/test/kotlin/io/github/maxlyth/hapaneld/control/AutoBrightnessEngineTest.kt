package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
