package io.github.maxlyth.hapaneld.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuPolicyTest {
    @Test fun acceptsOnlyExpectedShellIdentityAndProtocol() {
        assertTrue(ShizukuPolicy.usable(2000, ShizukuPolicy.PROTOCOL_VERSION))
        assertFalse(ShizukuPolicy.usable(0, ShizukuPolicy.PROTOCOL_VERSION))
        assertFalse(ShizukuPolicy.usable(2000, ShizukuPolicy.PROTOCOL_VERSION - 1))
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

    @Test fun deniedPermissionRequiresManualRecoveryInsteadOfAnotherPrompt() {
        assertFalse(ShizukuPolicy.shouldRequestPermission(true, rationaleRequired = true))
        assertTrue(ShizukuPolicy.shouldRequestPermission(true, rationaleRequired = false))
        assertFalse(ShizukuPolicy.shouldRequestPermission(false, rationaleRequired = true))
        assertFalse(ShizukuPolicy.shouldRequestPermission(false, rationaleRequired = false))
    }

    @Test fun deniedPermissionOffersActionableShizukuManagerRecovery() {
        assertEquals(
            ShizukuSetupDialog.PrimaryAction.OPEN_MANAGER,
            ShizukuSetupDialog.primaryAction(
                consented = true,
                state = ShizukuState.MANUAL_GRANT_REQUIRED,
                managerRunning = true,
            ),
        )
        val text = ShizukuSetupDialog.description(ShizukuState.MANUAL_GRANT_REQUIRED)
        assertTrue(text.contains("Authorized applications"))
        assertTrue(text.contains("grant access manually"))
    }

    @Test fun freshPermissionRequestRemainsDistinctFromManualRecovery() {
        assertEquals(
            ShizukuSetupDialog.PrimaryAction.REQUEST_PERMISSION,
            ShizukuSetupDialog.primaryAction(
                consented = true,
                state = ShizukuState.PERMISSION_REQUIRED,
                managerRunning = true,
            ),
        )
    }

    @Test fun outerIpcDeadlineExceedsServiceDeadlineAndReaderJoin() {
        assertEquals(15_000L, ShizukuPolicy.clientDeadline(10_000))
        assertEquals(185_000L, ShizukuPolicy.clientDeadline(180_000))
        assertEquals(Long.MAX_VALUE, ShizukuPolicy.clientDeadline(Long.MAX_VALUE))
    }

    @Test fun installDeadlineIsPositiveAndCappedBeforeCrossingBinder() {
        assertEquals(null, ShizukuPolicy.installServiceDeadline(0))
        assertEquals(42L, ShizukuPolicy.installServiceDeadline(42))
        assertEquals(
            ShizukuPolicy.MAX_INSTALL_DEADLINE_MS,
            ShizukuPolicy.installServiceDeadline(Long.MAX_VALUE),
        )
    }

    @Test fun missingOrReplacedManagerNeverTrapsLocalConsent() {
        assertTrue(ShizukuSetupDialog.disableAvailable(true, ShizukuState.MANAGER_MISSING))
        assertTrue(ShizukuSetupDialog.disableAvailable(true, ShizukuState.MANAGER_UNTRUSTED))
        assertFalse(ShizukuSetupDialog.disableAvailable(false, ShizukuState.MANAGER_MISSING))
    }
}
