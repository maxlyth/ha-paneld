package io.github.maxlyth.hapaneld.sensors

import org.junit.Assert.*
import org.junit.Test

class ProximitySampleFreshnessTest {
    @Test fun onlyNewMonotonicTimestampWithin750MillisecondsIsFresh() {
        assertTrue(proximitySampleFresh(10_000_000_000, 10_000, 9_000_000_000))
        assertTrue(proximitySampleFresh(9_250_000_000, 10_000, 9_000_000_000))
        assertFalse(proximitySampleFresh(9_249_000_000, 10_000, 9_000_000_000))
        assertFalse(proximitySampleFresh(10_001_000_000, 10_000, 9_000_000_000))
        assertFalse(proximitySampleFresh(9_000_000_000, 10_000, 9_000_000_000))
        assertFalse(proximitySampleFresh(8_000_000_000, 10_000, 9_000_000_000))
        assertFalse(proximitySampleFresh(0, 0, 0))
        assertFalse(proximitySampleFresh(-1, 1, 0))
    }

    @Test fun retainedBackfillCanEstablishStateButCannotBecomeALiveWave() {
        val engine = ProximityCalibrationEngine(ProximityCalibrationEngine.Calibration(
            mode = ProximityCalibrationEngine.Mode.BINARY, clearRaw = 0f, nearRaw = 1f,
        ))
        engine.observe(0f, 10_000)
        engine.tick(10_200)
        val oldNearTimestamp = 9_000_000_000L
        engine.observe(1f, 10_800, live = proximitySampleFresh(oldNearTimestamp, 10_800, 8_000_000_000))
        engine.observe(0f, 11_100, live = proximitySampleFresh(11_100_000_000, 11_100, oldNearTimestamp))
        assertFalse(engine.tick(11_300).gesture)
    }
}
