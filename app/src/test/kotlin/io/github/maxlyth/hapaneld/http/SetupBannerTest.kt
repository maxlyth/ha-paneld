package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupBannerTest {
    @Test fun connectedAndConfigured_noNeeds() {
        assertTrue(SetupBanner.needs("mqtt.example · connected", brokerConfigured = true, panelIdDefault = false).isEmpty())
    }

    /** Regression: a CONFIGURED broker that is merely mid-(re)connect must NOT be reported as missing. */
    @Test fun configuredButConnecting_noBrokerNeed() {
        assertTrue(SetupBanner.needs("host · connecting…", brokerConfigured = true, panelIdDefault = false).isEmpty())
    }

    /**
     * Regression for the reported config-change race symptom: while the bridge rebuilds, its status is
     * transient/blank ("disabled"); with a broker CONFIGURED that must not surface "needs the MQTT broker".
     */
    @Test fun configuredButBlankStatus_doesNotClaimMissingBroker() {
        val needs = SetupBanner.needs("disabled", brokerConfigured = true, panelIdDefault = false)
        assertFalse("must not falsely claim the broker is missing", needs.contains("the MQTT broker"))
        assertTrue(needs.isEmpty())
    }

    @Test fun unconfiguredAndBlank_needsBroker() {
        assertEquals(listOf("the MQTT broker"), SetupBanner.needs("disabled", brokerConfigured = false, panelIdDefault = false))
    }

    @Test fun authRejected_needsCredentials() {
        assertEquals(
            listOf("valid MQTT credentials (the broker rejected them)"),
            SetupBanner.needs("host · reachable, auth rejected — check username/password", brokerConfigured = true, panelIdDefault = false),
        )
    }

    @Test fun unreachable_needsReachableBroker() {
        assertEquals(
            listOf("a reachable MQTT broker"),
            SetupBanner.needs("host · unreachable", brokerConfigured = true, panelIdDefault = false),
        )
    }

    @Test fun defaultPanelId_isListed() {
        assertEquals(listOf("a panel id"), SetupBanner.needs("host · connected", brokerConfigured = true, panelIdDefault = true))
    }
}
