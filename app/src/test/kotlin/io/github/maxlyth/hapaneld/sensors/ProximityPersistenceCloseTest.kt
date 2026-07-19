package io.github.maxlyth.hapaneld.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityPersistenceCloseTest {
    @Test fun finalPersistenceRetriesTheBatchAndRequiresStoreClose() {
        var writes = 0
        var closes = 0

        assertTrue(
            finishProximityPersistence(
                attempts = 3,
                write = { ++writes == 3 },
                close = { ++closes },
            ),
        )
        assertEquals(3, writes)
        assertEquals(1, closes)
    }

    @Test fun exhaustedWritesAndCloseFailureBothReportIncompletePersistence() {
        var failedWriteCloses = 0
        assertFalse(
            finishProximityPersistence(
                attempts = 3,
                write = { false },
                close = { ++failedWriteCloses },
            ),
        )
        assertEquals(1, failedWriteCloses)

        assertFalse(
            finishProximityPersistence(
                attempts = 3,
                write = { true },
                close = { error("close failed") },
            ),
        )
    }
}
