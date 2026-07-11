package io.github.maxlyth.hapaneld.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rk3576 4-bit LED scale: truncation keeps a dimming colour hue-dominant, never flashing white. */
class Led4bitScaleTest {

    @Test fun endpointsMapToTheFullRange() {
        assertEquals(0, Led4bitScale.to4bit(0))
        assertEquals(15, Led4bitScale.to4bit(255))
    }

    @Test fun clampsOutOfRangeInput() {
        assertEquals(0, Led4bitScale.to4bit(-5))
        assertEquals(15, Led4bitScale.to4bit(999))
    }

    @Test fun truncatesTowardsZero() {
        // (v*15)/255 floored: 128 -> 7, 165 -> 9, 17 -> exactly 1.
        assertEquals(7, Led4bitScale.to4bit(128))
        assertEquals(9, Led4bitScale.to4bit(165))
        assertEquals(1, Led4bitScale.to4bit(17))
    }

    @Test fun subLevelChannelsFallToZero() {
        // A channel below one 4-bit step (v <= 16) truncates to 0, so the weakest channels of a dimming
        // colour drop out first (toward the dominant hue) instead of all surviving as an equal grey/white.
        for (v in 0..16) assertEquals("sub-level $v must be 0", 0, Led4bitScale.to4bit(v))
        assertEquals("first level at v=17", 1, Led4bitScale.to4bit(17))
    }

    @Test fun aDimmingTriChannelColourNeverCollapsesToWhite() {
        // The muted blue (65,89,132) the user pulsed: as brightness falls it must never become an equal-
        // channel grey/white (r == g == b, all nonzero) — it should shed its weak channels toward the
        // dominant blue and then go off. Regression guard for the white-flash-at-the-trough report.
        for (br in 0..255) {
            val r = Led4bitScale.to4bit(65 * br / 255)
            val g = Led4bitScale.to4bit(89 * br / 255)
            val b = Led4bitScale.to4bit(132 * br / 255)
            val whiteish = r == g && g == b && r > 0
            assertTrue("white flash at br=$br -> ($r,$g,$b)", !whiteish)
        }
    }

    @Test fun monotonicNonDecreasing() {
        var prev = 0
        for (v in 0..255) {
            val cur = Led4bitScale.to4bit(v)
            assertTrue("must not decrease at $v ($cur < $prev)", cur >= prev)
            prev = cur
        }
    }
}
