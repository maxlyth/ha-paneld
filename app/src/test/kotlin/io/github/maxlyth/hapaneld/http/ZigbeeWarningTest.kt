package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.ZigbeeHealthSnapshot
import io.github.maxlyth.hapaneld.control.ZigbeeHealthState
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZigbeeWarningTest {
    @Test fun explicitlyEnabledUnjoinedGatewayWarnsToJoinOrDisable() {
        val warning = zigbeeWarningText(
            ZigbeeHealthSnapshot(state = ZigbeeHealthState.DEGRADED_UNJOINED, joined = false),
            configuredOn = true,
        )

        assertTrue(warning!!.contains("enabled but not joined"))
        assertTrue(warning.contains("consume substantial CPU"))
        assertTrue(warning.contains("coordinator"))
        assertTrue(warning.contains("switch OFF"))
        assertTrue(warning.contains("/configure#cfg-zigbee_join"))
    }

    @Test fun unconfiguredVendorGatewayDoesNotReceiveConfigurationNag() {
        assertNull(
            zigbeeWarningText(
                ZigbeeHealthSnapshot(state = ZigbeeHealthState.DEGRADED_UNJOINED, joined = false),
                configuredOn = false,
            ),
        )
    }
}
