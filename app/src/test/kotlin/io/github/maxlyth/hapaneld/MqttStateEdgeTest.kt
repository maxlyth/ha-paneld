package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttStateEdgeTest {
    @Test fun unavailableClearsTheRetainedValueWithAnEmptyPayload() {
        assertEquals("", mqttStatePayload(Observation.Unavailable))
        assertEquals("""{"state":"ON","brightness":73}""", mqttStatePayload(Observation.Known("""{"state":"ON","brightness":73}""")))
        assertEquals("", mqttStatePayload(Observation.Known("")))
        assertTrue(
            "state channels retain by default",
            mqttStateChannel("relay1", "ha-paneld/p/relay1/state", observe = { Observation.Unavailable }).route.retain,
        )
    }

    @Test fun stateCommandsConflateByTheirChannelLeaf() {
        assertEquals("screen", mqttCommandChannel("p", "ha-paneld/p/screen/set"))
        assertEquals("relay3", mqttCommandChannel("p", "ha-paneld/p/relay3/set"))
        assertEquals("button_led3", mqttCommandChannel("p", "ha-paneld/p/button_led3/set"))
        assertEquals("update_paneld", mqttCommandChannel("office", "ha-paneld/office/update_paneld/set"))
    }

    @Test fun topicsOutsideTheCommandShapeHaveNoChannel() {
        listOf(
            "ha-paneld/p/set",
            "ha-paneld/p//set",
            "ha-paneld/p/screen/state",
            "ha-paneld/q/screen/set",
            "ha-paneld/p/a/b/set",
            "ha-paneld/p/Screen/set",
            "xha-paneld/p/screen/set",
        ).forEach { assertNull(it, mqttCommandChannel("p", it)) }
    }

    @Test fun anMqttChannelKeyCanNeverCollideWithAnHttpLiveSettingKey() {
        val http = "http:screen"
        assertEquals(null, mqttCommandChannel("p", "ha-paneld/p/$http/set"))
        assertTrue(mqttCommandChannel("p", "ha-paneld/p/screen/set") != http)
    }
}
