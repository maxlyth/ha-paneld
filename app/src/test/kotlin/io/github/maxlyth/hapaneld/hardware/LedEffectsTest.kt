package io.github.maxlyth.hapaneld.hardware

import io.github.maxlyth.hapaneld.hardware.LedEffects.Effect
import io.github.maxlyth.hapaneld.hardware.LedEffects.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedEffectsTest {

    @Test fun effectNameMapping() {
        assertEquals(Effect.STROBE, Effect.from("strobe"))
        assertEquals(Effect.BLINK, Effect.from("BLINK"))    // case-insensitive
        assertEquals(Effect.PULSE, Effect.from(" pulse "))  // trimmed
        assertNull(Effect.from("none"))
        assertNull(Effect.from(""))
        assertNull(Effect.from(null))
        assertNull(Effect.from("rainbow"))
    }

    @Test fun strobeAndBlinkAlternateLitAndOff() {
        for (fx in listOf(Effect.STROBE, Effect.BLINK)) {
            assertEquals(Frame.Rgb(255, 0, 0), LedEffects.frame(fx, 0, 255, 0, 0, 255))
            assertEquals(Frame.Off, LedEffects.frame(fx, 1, 255, 0, 0, 255))
            assertEquals(Frame.Rgb(255, 0, 0), LedEffects.frame(fx, 2, 255, 0, 0, 255))
            assertEquals(Frame.Off, LedEffects.frame(fx, 3, 255, 0, 0, 255))
        }
    }

    @Test fun litFramesBakeBrightnessIntoColour() {
        // half brightness scales each channel (255 * 128 / 255 = 128)
        assertEquals(Frame.Rgb(128, 0, 0), LedEffects.frame(Effect.STROBE, 0, 255, 0, 0, 128))
    }

    @Test fun pulseRampsUpThenDown() {
        val half = LedEffects.PULSE_STEPS / 2
        assertEquals(0, brightnessOf(LedEffects.frame(Effect.PULSE, 0, 0, 0, 255, 255)))            // trough
        assertEquals(255, brightnessOf(LedEffects.frame(Effect.PULSE, half, 0, 0, 255, 255)))       // peak
        assertEquals(0, brightnessOf(LedEffects.frame(Effect.PULSE, LedEffects.PULSE_STEPS, 0, 0, 255, 255))) // back to trough
        // non-decreasing over the first half of the cycle
        var prev = -1
        for (s in 0..half) {
            val v = brightnessOf(LedEffects.frame(Effect.PULSE, s, 0, 0, 255, 255))
            assertTrue("pulse should rise to the peak", v >= prev)
            prev = v
        }
    }

    @Test fun pulseCycleIsPeriodic() {
        val a = LedEffects.frame(Effect.PULSE, 5, 10, 20, 30, 200)
        val b = LedEffects.frame(Effect.PULSE, 5 + LedEffects.PULSE_STEPS, 10, 20, 30, 200)
        assertEquals(a, b)
    }

    @Test fun stepPeriodsAreSane() {
        assertTrue(Effect.STROBE.stepMs in 50..150)
        assertTrue(Effect.BLINK.stepMs >= 300)
        assertTrue(Effect.PULSE.stepMs in 60..120)   // keeps daemon-panel writes ≲ 16/s
    }

    private fun brightnessOf(f: Frame): Int = when (f) {
        is Frame.Rgb -> maxOf(f.r, f.g, f.b)
        Frame.Off -> 0
    }
}
