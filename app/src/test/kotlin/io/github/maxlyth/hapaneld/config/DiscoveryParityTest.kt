package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests: the registry-driven discovery builder must emit payloads byte-identical to the
 * legacy hand-written `MqttBridge.publishDiscovery` JSON, so existing HA installs see no entity
 * churn when the discovery loop is switched to the registry (Stage C).
 */
class DiscoveryParityTest {

    private val panel = "test"
    private val avail =
        """"availability_topic":"ha-paneld/test/availability","payload_available":"online","payload_not_available":"offline""""
    private val device =
        """"device":{"identifiers":["ha-paneld-test"],"name":"Bedroom","manufacturer":"Sonoff","model":"NSPanel Pro","sw_version":"9.9.9","hw_version":"hw","serial_number":"abc"}"""

    private fun build(key: String): String =
        SettingsRegistry.spec(key)!!.ha!!.buildDiscoveryJson(panel, avail, device)

    @Test fun wakeOnWaveMatchesLegacy() {
        assertEquals(
            """{"name":"Wake on wave","unique_id":"test_wake_on_wave","command_topic":"ha-paneld/test/wake_on_wave/set","state_topic":"ha-paneld/test/wake_on_wave/state","icon":"mdi:gesture-tap","entity_category":"config",$avail,$device}""",
            build("wake_on_wave"),
        )
    }

    @Test fun watchdogMatchesLegacy() {
        assertEquals(
            """{"name":"App watchdog","unique_id":"test_watchdog","command_topic":"ha-paneld/test/watchdog/set","state_topic":"ha-paneld/test/watchdog/state","icon":"mdi:restart-alert","entity_category":"config",$avail,$device}""",
            build("watchdog_enabled"),
        )
    }

    @Test fun preventIdleDimMatchesLegacy() {
        assertEquals(
            """{"name":"Prevent idle dim","unique_id":"test_prevent_idle_dim","command_topic":"ha-paneld/test/prevent_idle_dim/set","state_topic":"ha-paneld/test/prevent_idle_dim/state","icon":"mdi:brightness-7","entity_category":"config",$avail,$device}""",
            build("prevent_idle_dim"),
        )
    }

    @Test fun silenceBootChimeMatchesLegacy() {
        assertEquals(
            """{"name":"Silence boot chime","unique_id":"test_silence_boot_chime","command_topic":"ha-paneld/test/silence_boot_chime/set","state_topic":"ha-paneld/test/silence_boot_chime/state","icon":"mdi:volume-off","entity_category":"config",$avail,$device}""",
            build("silence_boot_chime"),
        )
    }

    @Test fun autoBrightnessMatchesLegacy() {
        assertEquals(
            """{"name":"Auto-brightness","unique_id":"test_auto_brightness","command_topic":"ha-paneld/test/auto_brightness/set","state_topic":"ha-paneld/test/auto_brightness/state","icon":"mdi:brightness-auto","entity_category":"config",$avail,$device}""",
            build("auto_brightness"),
        )
    }

    @Test fun brightnessBiasNumberMatchesLegacy() {
        assertEquals(
            """{"name":"Brightness bias","unique_id":"test_brightness_bias","command_topic":"ha-paneld/test/brightness_bias/set","state_topic":"ha-paneld/test/brightness_bias/state","min":-100,"max":100,"step":5,"mode":"slider","icon":"mdi:brightness-6","entity_category":"config",$avail,$device}""",
            build("brightness_bias"),
        )
    }

    @Test fun softHideAppendsEnabledByDefaultFalse() {
        val out = SettingsRegistry.spec("watchdog_enabled")!!.ha!!
            .buildDiscoveryJson(panel, avail, device, enabledByDefault = false)
        assertTrue(out.contains(""","entity_category":"config","enabled_by_default":false,"availability_topic""""))
    }

    @Test fun stateTopicDerives() {
        assertEquals(
            "ha-paneld/test/wake_on_wave/state",
            SettingsRegistry.spec("wake_on_wave")!!.ha!!.stateTopic("test"),
        )
    }

    @Test fun proximityGatesEntityAvailability() {
        val s = SettingsRegistry.spec("wake_on_wave")!!
        assertTrue(s.availableWhen(Capabilities(hasProximity = true)))
        assertTrue(!s.availableWhen(Capabilities(hasProximity = false)))
    }
}
