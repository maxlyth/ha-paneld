package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityThresholdValidationTest {
    @Test fun onlyFiniteNonNegativeConservativeRawValuesAreAccepted() {
        assertTrue(validProximityThreshold(0f))
        assertTrue(validProximityThreshold(9f))
        assertTrue(validProximityThreshold(1_000_000f))
        assertFalse(validProximityThreshold(null))
        assertFalse(validProximityThreshold(Float.NaN))
        assertFalse(validProximityThreshold(Float.POSITIVE_INFINITY))
        assertFalse(validProximityThreshold(Float.NEGATIVE_INFINITY))
        assertFalse(validProximityThreshold(-0.01f))
        assertFalse(validProximityThreshold(1_000_001f))
    }
}
