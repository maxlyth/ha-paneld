package io.github.maxlyth.hapaneld.mqtt

import io.github.maxlyth.hapaneld.mqttAcceptsCommand
import io.github.maxlyth.hapaneld.mqttDiscoveryRetain
import io.github.maxlyth.hapaneld.mqttIsHaOnline
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttProtocolPolicyTest {
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
}
