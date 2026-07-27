package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the single recognized Companion package set on its one owner ([CompanionInstaller]). Formerly
 * this exact ordered pair lived re-copied in PanelInfo, CompanionDataOperationGate, TameController and
 * the diagnostics dump; those readers now share this constant, so this test guards the dashboard_entity and
 * order every one of them depends on.
 */
class CompanionPackageSetTest {
    @Test fun recognizedSetIsFullThenMinimal() {
        assertEquals("io.homeassistant.companion.android", CompanionInstaller.FULL_PKG)
        assertEquals("io.homeassistant.companion.android.minimal", CompanionInstaller.MINIMAL_PKG)
        assertEquals(
            listOf(CompanionInstaller.FULL_PKG, CompanionInstaller.MINIMAL_PKG),
            CompanionInstaller.SUPPORTED_PACKAGES,
        )
    }

    @Test fun membershipMatchesTheRecognizedSet() {
        assertTrue(CompanionInstaller.FULL_PKG in CompanionInstaller.SUPPORTED_PACKAGES)
        assertTrue(CompanionInstaller.MINIMAL_PKG in CompanionInstaller.SUPPORTED_PACKAGES)
        assertEquals(2, CompanionInstaller.SUPPORTED_PACKAGES.size)
    }
}
