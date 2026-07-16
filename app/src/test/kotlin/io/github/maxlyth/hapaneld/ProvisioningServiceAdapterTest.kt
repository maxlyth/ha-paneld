package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.device.Generic
import io.github.maxlyth.hapaneld.device.ShellyWallDisplay
import io.github.maxlyth.hapaneld.device.Smt1019
import io.github.maxlyth.hapaneld.device.Tpa10
import io.github.maxlyth.hapaneld.device.Wf1589t
import io.github.maxlyth.hapaneld.device.ZxSmt156
import io.github.maxlyth.hapaneld.device.profile.DataDeviceProfile
import io.github.maxlyth.hapaneld.device.profile.ProfileMetadata
import io.github.maxlyth.hapaneld.device.profile.ProfileMaturity
import io.github.maxlyth.hapaneld.device.profile.ProfileOrigin
import io.github.maxlyth.hapaneld.device.profile.ProfileRef
import io.github.maxlyth.hapaneld.device.profile.ProfileSummary
import io.github.maxlyth.hapaneld.device.profile.ProfileYaml
import io.github.maxlyth.hapaneld.device.profile.ResolvedProfile
import io.github.maxlyth.hapaneld.device.profile.ShizukuRecommendation
import io.github.maxlyth.hapaneld.provisioning.requiresProvisioningHelper
import io.github.maxlyth.hapaneld.provisioning.toProvisioningProfile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertTrue(ZxSmt156.requiresProvisioningHelper())
    }

    @Test fun everyCoreDriverHasAnExplicitHelperAuthorityDemand() {
        assertEquals(ProfileMetadata.drivers.map { it.id }.toSet(), ProfileMetadata.helperAuthorityDemand.keys)
    }

    @Test fun bundledZxProfileRequiresHelperForItsSandboxedIoctlFallback() {
        val assetsDir = listOf(
            File("src/main/assets/device-profiles"),
            File("app/src/main/assets/device-profiles"),
            File("../app/src/main/assets/device-profiles"),
        ).firstOrNull(File::isDirectory) ?: error("Bundled profile assets directory not found")
        val parsed = ProfileYaml.parse(File(assetsDir, "zx-smt156.yaml").readText())
        assertNotNull(parsed.document)

        val profile = DataDeviceProfile(parsed.document!!, productVersion = "")
        assertFalse(profile.appCanSu)
        assertTrue(profile.requiresProvisioningHelper())
    }

    @Test fun optimisticAppCanSuHintIsNotAdaptedAsObservedRootReadiness() {
        assertTrue(Generic.appCanSu)
        val adapted = ResolvedProfile(
            profile = Generic,
            summary = ProfileSummary(
                ref = ProfileRef("generic", REVISION),
                displayName = "Generic Android panel",
                origin = ProfileOrigin.BUNDLED,
                schema = 2,
                minCoreVersion = null,
                matchesThisDevice = true,
                active = true,
                selected = true,
                shizukuRecommendation = ShizukuRecommendation.NONE,
                contentVersion = "1.0.0",
                maturity = ProfileMaturity.VERIFIED,
            ),
        ).toProvisioningProfile()

        assertFalse(adapted.directRootExpected)
    }

    private companion object {
        const val REVISION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
