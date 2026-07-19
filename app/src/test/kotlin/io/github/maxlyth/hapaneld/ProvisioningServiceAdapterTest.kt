package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.device.profile.BundledProfileFixtures
import io.github.maxlyth.hapaneld.device.profile.ProfileHelperAuthorityDemand
import io.github.maxlyth.hapaneld.device.profile.ProfileMetadata
import io.github.maxlyth.hapaneld.device.profile.ProfileOrigin
import io.github.maxlyth.hapaneld.device.profile.ProfileSummary
import io.github.maxlyth.hapaneld.device.profile.ResolvedProfile
import io.github.maxlyth.hapaneld.provisioning.requiresProvisioningHelper
import io.github.maxlyth.hapaneld.provisioning.toProvisioningProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningServiceAdapterTest {
    @Test fun bundledHelperRequirementsAreAnIndependentProvisioningContract() {
        val actual = BundledProfileFixtures.bundled
            .filter { it.profile().requiresProvisioningHelper() }
            .mapTo(mutableSetOf()) { it.document.id }

        assertEquals(
            setOf("s9e", "smt1019", "tpa10", "wf1589t", "zx-smt156"),
            actual,
        )
    }

    @Test fun everyCoreDriverHasThePinnedHelperAuthorityDemand() {
        val expected = mapOf(
            "access.android-su" to ProfileHelperAuthorityDemand.NONE,
            "access.toolbox-su" to ProfileHelperAuthorityDemand.NONE,
            "input.button-backlight" to ProfileHelperAuthorityDemand.REQUIRED,
            "input.evdev" to ProfileHelperAuthorityDemand.REQUIRED,
            "led.autodetect" to ProfileHelperAuthorityDemand.SANDBOX_FALLBACK,
            "led.rk3576-ioctl" to ProfileHelperAuthorityDemand.SANDBOX_FALLBACK,
            "led.rk3576-ioctl-daemon" to ProfileHelperAuthorityDemand.REQUIRED,
            "led.sysfs-daemon" to ProfileHelperAuthorityDemand.REQUIRED,
            "radio.siliconlabs-host" to ProfileHelperAuthorityDemand.NONE,
            "relay.sysfs" to ProfileHelperAuthorityDemand.NONE,
            "relay.gpio-button-led" to ProfileHelperAuthorityDemand.NONE,
            "screen.brightness-zero" to ProfileHelperAuthorityDemand.NONE,
            "screen.daemon-blpower" to ProfileHelperAuthorityDemand.REQUIRED,
            "screen.su-blpower" to ProfileHelperAuthorityDemand.SANDBOX_FALLBACK,
            "sensor.android" to ProfileHelperAuthorityDemand.NONE,
            "sensor.cht8305-daemon" to ProfileHelperAuthorityDemand.SHIZUKU_ALTERNATE,
            "sensor.gpio-proximity" to ProfileHelperAuthorityDemand.REQUIRED,
            "update.webview" to ProfileHelperAuthorityDemand.NONE,
        )

        assertEquals(ProfileMetadata.drivers.map { it.id }.toSet(), expected.keys)
        assertEquals(expected, ProfileMetadata.helperAuthorityDemand)
    }

    @Test fun optimisticAppCanSuHintIsNotAdaptedAsObservedRootReadiness() {
        val source = BundledProfileFixtures.bundled.single { it.document.match.fallback }
        val fallback = source.profile()
        assertTrue(fallback.appCanSu)
        val adapted = ResolvedProfile(
            profile = fallback,
            summary = ProfileSummary(
                ref = io.github.maxlyth.hapaneld.device.profile.ProfileRef(source.document.id, source.rawSha256),
                displayName = source.document.displayName,
                origin = ProfileOrigin.BUNDLED,
                schema = source.document.schema,
                minCoreVersion = source.document.requires.minCoreVersion,
                matchesThisDevice = true,
                active = true,
                selected = true,
                shizukuRecommendation = source.document.provisioning.access.shizuku,
                contentVersion = source.document.version,
                maturity = source.document.metadata.maturity,
            ),
        ).toProvisioningProfile()

        assertFalse(adapted.directRootExpected)
    }
}
