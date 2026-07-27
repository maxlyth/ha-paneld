package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The config-store downgrade floor ([SelfUpdater.crossesConfigFloor]). From v0.9.4 the panel's config
 * lives in a SQLite database an older build cannot read, so a manual/tag install must never cross below
 * that boundary and strand the config.
 */
class SelfUpdaterFloorTest {
    private fun crosses(candidate: String, current: String) = SelfUpdater.crossesConfigFloor(candidate, current)

    @Test fun downgradeBelowTheFloorFromAboveIsBlocked() {
        assertTrue(crosses("0.9.3", "0.9.6"))
        assertTrue(crosses("0.8.9", "0.9.4"))
        assertTrue(crosses("0.9.4-rc1", "0.9.6")) // an rc of 0.9.4 predates the 0.9.4 stable boundary
    }

    @Test fun theFloorVersionItselfAndAboveAreAllowed() {
        assertFalse(crosses("0.9.4", "0.9.6")) // 0.9.4 == floor
        assertFalse(crosses("0.9.5", "0.9.6"))
        assertFalse(crosses("0.9.6", "0.9.6")) // reinstall
        assertFalse(crosses("1.0.0", "0.9.6")) // an upgrade is never blocked
    }

    @Test fun aPanelAlreadyBelowTheFloorIsNotBlocked() {
        // Current build is already below the boundary, so moving among old builds does not "cross" it.
        assertFalse(crosses("0.9.2", "0.9.3"))
        assertFalse(crosses("0.9.3-rc1", "0.9.3-rc5"))
    }

    @Test fun anUnparseableVersionIsNotBlocked() {
        // Conservative: if either version cannot be parsed, do not block a legitimate install.
        assertFalse(crosses("not-a-version", "0.9.6"))
    }
}
