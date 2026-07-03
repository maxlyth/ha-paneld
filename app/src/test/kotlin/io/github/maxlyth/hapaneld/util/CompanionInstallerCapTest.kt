package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure version-cap decision for the Companion auto-updater (see CompanionInstaller.exceedsCap). */
class CompanionInstallerCapTest {
    @Test fun noCapNeverExceeds() {
        assertFalse(CompanionInstaller.exceedsCap("2026.6.5", null))
        assertFalse(CompanionInstaller.exceedsCap("9999.1.0", null))
    }

    @Test fun aboveCapExceeds() {
        assertTrue(CompanionInstaller.exceedsCap("2026.6.5", "2026.5.4"))
        assertTrue(CompanionInstaller.exceedsCap("2026.6.0", "2026.5.4"))
        assertTrue(CompanionInstaller.exceedsCap("2027.1.0", "2026.5.4"))
    }

    @Test fun atOrBelowCapDoesNotExceed() {
        assertFalse(CompanionInstaller.exceedsCap("2026.5.4", "2026.5.4"))   // equal
        assertFalse(CompanionInstaller.exceedsCap("2026.5.3", "2026.5.4"))
        assertFalse(CompanionInstaller.exceedsCap("2026.4.9", "2026.5.4"))
    }

    @Test fun stripsVariantSuffixBeforeComparing() {
        // The release version carries a "-minimal"/"-full" variant tag that must not defeat the compare.
        assertFalse(CompanionInstaller.exceedsCap("2026.5.4-minimal", "2026.5.4"))
        assertTrue(CompanionInstaller.exceedsCap("2026.6.5-full", "2026.5.4"))
    }
}
