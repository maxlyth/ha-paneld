package io.github.maxlyth.hapaneld.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuPolicyTest {
    @Test fun acceptsOnlyExpectedShellIdentityAndProtocol() {
        assertTrue(ShizukuPolicy.usable(2000, 1))
        assertFalse(ShizukuPolicy.usable(0, 1))
        assertFalse(ShizukuPolicy.usable(2000, 2))
    }

    @Test fun typedArgumentsAreBoundedBeforeCrossingBinder() {
        assertTrue(ShizukuPolicy.validDensity(80))
        assertTrue(ShizukuPolicy.validDensity(640))
        assertFalse(ShizukuPolicy.validDensity(79))
        assertFalse(ShizukuPolicy.validCoordinate(-1))
        assertFalse(ShizukuPolicy.validFontScale(Float.NaN))
        assertFalse(ShizukuPolicy.validApkLength(0))
        assertFalse(ShizukuPolicy.validApkLength(ShizukuPolicy.MAX_APK_BYTES + 1))
    }

    @Test fun managerStatusTextExplainsEveryLifecycleState() {
        ShizukuState.entries.forEach { state ->
            assertTrue("missing text for $state", ShizukuSetupDialog.description(state).isNotBlank())
        }
        assertEquals(ShizukuManagerIdentity.PACKAGE, ShizukuPolicy.MANAGER_PACKAGE)
    }

    @Test fun staleDisabledOrUntrustedBindingCannotBecomeReady() {
        assertFalse(ShizukuPolicy.canAcceptBinding(1, 2, true, true, true, true))
        assertFalse(ShizukuPolicy.canAcceptBinding(2, 2, true, false, true, true))
        assertFalse(ShizukuPolicy.canAcceptBinding(2, 2, true, true, false, true))
        assertFalse(ShizukuPolicy.canAcceptBinding(2, 2, false, true, true, true))
        assertTrue(ShizukuPolicy.canAcceptBinding(2, 2, true, true, true, true))
    }

    @Test fun explicitPermissionRetrySurvivesDenialRationale() {
        assertTrue(ShizukuPolicy.shouldRequestPermission(true, rationaleRequired = true))
        assertTrue(ShizukuPolicy.shouldRequestPermission(true, rationaleRequired = false))
        assertFalse(ShizukuPolicy.shouldRequestPermission(false, rationaleRequired = true))
    }

    @Test fun outerIpcDeadlineExceedsServiceDeadlineAndReaderJoin() {
        assertTrue(ShizukuPolicy.clientDeadline(10_000) > 11_000)
        assertTrue(ShizukuPolicy.clientDeadline(180_000) > 181_000)
    }

    @Test fun missingOrReplacedManagerNeverTrapsLocalConsent() {
        assertTrue(ShizukuSetupDialog.disableAvailable(true, ShizukuState.MANAGER_MISSING))
        assertTrue(ShizukuSetupDialog.disableAvailable(true, ShizukuState.MANAGER_UNTRUSTED))
        assertFalse(ShizukuSetupDialog.disableAvailable(false, ShizukuState.MANAGER_MISSING))
    }
}
