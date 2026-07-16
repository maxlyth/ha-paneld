package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.device.Generic
import io.github.maxlyth.hapaneld.device.ShellyWallDisplay
import io.github.maxlyth.hapaneld.device.Smt1019
import io.github.maxlyth.hapaneld.device.Tpa10
import io.github.maxlyth.hapaneld.device.Wf1589t
import io.github.maxlyth.hapaneld.device.ZxSmt156
import io.github.maxlyth.hapaneld.provisioning.requiresProvisioningHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningServiceAdapterTest {
    @Test fun helperRequirementComesFromSelectedDriversRatherThanRootAvailabilityAlone() {
        assertTrue(Tpa10.requiresProvisioningHelper())
        assertTrue(Smt1019.requiresProvisioningHelper())
        assertTrue(Wf1589t.requiresProvisioningHelper())

        assertFalse(Generic.requiresProvisioningHelper())
        assertFalse(ShellyWallDisplay.requiresProvisioningHelper())
        assertFalse(ZxSmt156.requiresProvisioningHelper())
    }
}
