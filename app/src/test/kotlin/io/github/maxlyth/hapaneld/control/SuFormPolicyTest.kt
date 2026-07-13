package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuFormPolicyTest {
    @Test fun unprobedAndPriorNegativeTryBothDialects() {
        val both = intArrayOf(SuFormPolicy.TOOLBOX, SuFormPolicy.ANDROID)

        assertArrayEquals(both, SuFormPolicy.candidates(SuFormPolicy.UNPROBED))
        assertArrayEquals(both, SuFormPolicy.candidates(SuFormPolicy.NONE_LAST_PROBE))
    }

    @Test fun successfulDialectIsTheOnlyCandidate() {
        assertArrayEquals(intArrayOf(SuFormPolicy.TOOLBOX), SuFormPolicy.candidates(SuFormPolicy.TOOLBOX))
        assertArrayEquals(intArrayOf(SuFormPolicy.ANDROID), SuFormPolicy.candidates(SuFormPolicy.ANDROID))
        assertTrue(SuFormPolicy.working(SuFormPolicy.TOOLBOX))
        assertTrue(SuFormPolicy.working(SuFormPolicy.ANDROID))
        assertFalse(SuFormPolicy.working(SuFormPolicy.NONE_LAST_PROBE))
    }
}
