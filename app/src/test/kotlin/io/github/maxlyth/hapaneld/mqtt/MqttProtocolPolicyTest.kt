package io.github.maxlyth.hapaneld.mqtt

import io.github.maxlyth.hapaneld.mqttAcceptsCommand
import io.github.maxlyth.hapaneld.mqttButtonEventTypes
import io.github.maxlyth.hapaneld.mqttDiscoveryCleanupMarker
import io.github.maxlyth.hapaneld.mqttDiscoveryRetain
import io.github.maxlyth.hapaneld.mqttIsHaOnline
import io.github.maxlyth.hapaneld.shouldRepublishDiscoveryAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttProtocolPolicyTest {
    @Test fun `profile button events extend the discovery event type set`() {
        val types = mqttButtonEventTypes(setOf("KEYCODE_CUSTOM_PANEL", "KEYCODE_POWER"))
        assertTrue("KEYCODE_CUSTOM_PANEL" in types)
        assertEquals(1, types.count { it == "KEYCODE_POWER" })
        assertEquals(types.sorted(), types)
    }

    @Test fun onlyFreshCommandsReachHardware() {
        assertTrue(mqttAcceptsCommand(stopped = false, retained = false))
        assertFalse(mqttAcceptsCommand(stopped = false, retained = true))
        assertFalse(mqttAcceptsCommand(stopped = true, retained = false))
        assertFalse(mqttAcceptsCommand(stopped = true, retained = true))
    }

    @Test fun onlyHomeAssistantOnlineBirthReannounces() {
        assertTrue(mqttIsHaOnline(" online\n".toByteArray()))
        assertTrue(mqttIsHaOnline("ONLINE".toByteArray()))
        assertFalse(mqttIsHaOnline("offline".toByteArray()))
        assertFalse(mqttIsHaOnline(byteArrayOf()))
    }

    @Test fun onlyDiscoveryTombstonesAreRetained() {
        assertFalse(mqttDiscoveryRetain("{\"name\":\"Screen\"}"))
        assertTrue(mqttDiscoveryRetain(""))
    }

    @Test fun discoveryCleanupMarkerChangesAcrossCoreAndProfileRevisions() {
        val first = mqttDiscoveryCleanupMarker("0.9.3", "panel.example@aaa")

        assertTrue(first != mqttDiscoveryCleanupMarker("0.9.4", "panel.example@aaa"))
        assertTrue(first != mqttDiscoveryCleanupMarker("0.9.3", "panel.example@bbb"))
        assertTrue(first != mqttDiscoveryCleanupMarker("0.9.3", "other.panel@aaa"))
    }

    @Test fun discoveryCleanupMarkerIsStableAndKeepsLegacyDefault() {
        assertTrue(
            mqttDiscoveryCleanupMarker("0.9.3", "panel.example@aaa") ==
                mqttDiscoveryCleanupMarker("0.9.3", "panel.example@aaa"),
        )
        assertTrue(mqttDiscoveryCleanupMarker("0.9.3", "") == "0.9.3")
    }

    @Test fun connectedPanelReannouncesOnlyWhenItsLiveConfigurationAddressChanges() {
        val old = "http://192.0.2.10:8888/"
        val replacement = "http://192.0.2.11:8888/"

        assertFalse(shouldRepublishDiscoveryAddress(connected = true, old, old))
        assertTrue(shouldRepublishDiscoveryAddress(connected = true, old, replacement))
        assertTrue(shouldRepublishDiscoveryAddress(connected = true, old, null))
        assertFalse(shouldRepublishDiscoveryAddress(connected = false, old, replacement))
    }
}
