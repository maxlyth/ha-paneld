package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests: the registry-driven discovery builder must emit payloads byte-identical to the
 * legacy hand-written `MqttBridge.publishDiscovery` JSON, so existing HA installs see no entity
 * churn when the discovery loop is switched to the registry (Stage C).
 */
class DiscoveryParityTest {
    @Test fun silenceBootChimeDefaultHasOneAuthority() {
        assertEquals(
            SettingsRegistry.DEFAULT_SILENCE_BOOT_CHIME.toString(),
            SettingsRegistry.spec("silence_boot_chime")?.default,
        )
    }

    @Test fun cpuGovernorIsExplicitlyLiveOnly() {
        assertTrue(SettingsRegistry.spec("cpu_governor")?.transient == true)
    }

    private val panel = "test"
    private val avail =
        """"availability_topic":"ha-paneld/test/availability","payload_available":"online","payload_not_available":"offline""""
    private val device =
        """"device":{"identifiers":["ha-paneld-test"],"name":"Bedroom","manufacturer":"Sonoff","model":"NSPanel Pro","sw_version":"9.9.9","hw_version":"hw","serial_number":"abc"}"""

    private fun build(key: String): String =
        SettingsRegistry.spec(key)!!.ha!!.buildDiscoveryJson(panel, avail, device)

    @Test fun wakeOnWaveMatchesLegacy() {
        assertEquals(
            """{"name":"Wake on wave","object_id":"test_wake_on_wave","unique_id":"test_wake_on_wave","command_topic":"ha-paneld/test/wake_on_wave/set","state_topic":"ha-paneld/test/wake_on_wave/state","icon":"mdi:gesture-tap","entity_category":"config",$avail,$device}""",
            build("wake_on_wave"),
        )
    }

    @Test fun watchdogMatchesLegacy() {
        assertEquals(
            """{"name":"App watchdog","object_id":"test_watchdog","unique_id":"test_watchdog","command_topic":"ha-paneld/test/watchdog/set","state_topic":"ha-paneld/test/watchdog/state","icon":"mdi:restart-alert","entity_category":"config",$avail,$device}""",
            build("watchdog_enabled"),
        )
    }

    @Test fun preventIdleDimMatchesLegacy() {
        assertEquals(
            """{"name":"Prevent idle dim","object_id":"test_prevent_idle_dim","unique_id":"test_prevent_idle_dim","command_topic":"ha-paneld/test/prevent_idle_dim/set","state_topic":"ha-paneld/test/prevent_idle_dim/state","icon":"mdi:brightness-7","entity_category":"config",$avail,$device}""",
            build("prevent_idle_dim"),
        )
    }

    @Test fun silenceBootChimeMatchesLegacy() {
        assertEquals(
            """{"name":"Silence boot chime","object_id":"test_silence_boot_chime","unique_id":"test_silence_boot_chime","command_topic":"ha-paneld/test/silence_boot_chime/set","state_topic":"ha-paneld/test/silence_boot_chime/state","icon":"mdi:volume-off","entity_category":"config",$avail,$device}""",
            build("silence_boot_chime"),
        )
    }

    @Test fun autoBrightnessMatchesLegacy() {
        assertEquals(
            """{"name":"Auto-brightness","object_id":"test_auto_brightness","unique_id":"test_auto_brightness","command_topic":"ha-paneld/test/auto_brightness/set","state_topic":"ha-paneld/test/auto_brightness/state","icon":"mdi:brightness-auto","entity_category":"config",$avail,$device}""",
            build("auto_brightness"),
        )
    }

    @Test fun ambientLightSensorMatchesLegacyDiscovery() {
        assertEquals(
            """{"name":"Illuminance","object_id":"test_illuminance","unique_id":"test_illuminance","state_topic":"ha-paneld/test/illuminance/state","device_class":"illuminance","unit_of_measurement":"lx","state_class":"measurement",$avail,$device}""",
            build("illuminance"),
        )
    }

    @Test fun physicalSensorDiscoveryComesFromRegistryDescriptors() {
        val proximityAvail =
            """"availability":[{"topic":"ha-paneld/test/availability","payload_available":"online","payload_not_available":"offline"},{"topic":"ha-paneld/test/proximity/availability","payload_available":"online","payload_not_available":"offline"}],"availability_mode":"all""""
        val expected = mapOf(
            "proximity" to
                """{"name":"Proximity","object_id":"test_proximity","unique_id":"test_proximity","state_topic":"ha-paneld/test/proximity/state","device_class":"occupancy","payload_on":"ON","payload_off":"OFF",$proximityAvail,$device}""",
            "proximity_level" to
                """{"name":"Proximity level","object_id":"test_proximity_level","unique_id":"test_proximity_level","state_topic":"ha-paneld/test/proximity_level/state","unit_of_measurement":"%","state_class":"measurement","icon":"mdi:hand-wave",$proximityAvail,$device}""",
            "temperature" to
                """{"name":"Temperature","object_id":"test_temperature","unique_id":"test_temperature","state_topic":"ha-paneld/test/temperature/state","device_class":"temperature","unit_of_measurement":"°C","state_class":"measurement",$avail,$device}""",
            "humidity" to
                """{"name":"Humidity","object_id":"test_humidity","unique_id":"test_humidity","state_topic":"ha-paneld/test/humidity/state","device_class":"humidity","unit_of_measurement":"%","state_class":"measurement",$avail,$device}""",
        )

        expected.forEach { (key, payload) ->
            val availability = if (key.startsWith("proximity")) proximityAvail else avail
            assertEquals(
                payload,
                SettingsRegistry.spec(key)!!.ha!!.buildDiscoveryJson(panel, availability, device),
            )
        }
    }

    @Test fun screenAndVolumeKeepTheirLegacyEntitiesAndNativeRanges() {
        assertEquals(
            """{"name":"Screen","object_id":"test_screen","unique_id":"test_screen","schema":"json","brightness":true,"supported_color_modes":["brightness"],"command_topic":"ha-paneld/test/screen/set","state_topic":"ha-paneld/test/screen/state",$avail,$device}""",
            build("screen"),
        )
        assertEquals(
            """{"name":"Volume","object_id":"test_volume","unique_id":"test_volume","command_topic":"ha-paneld/test/volume/set","state_topic":"ha-paneld/test/volume/state","min":0,"max":100,"step":1,"mode":"slider","unit_of_measurement":"%","icon":"mdi:volume-high",$avail,$device}""",
            build("volume"),
        )
    }

    @Test fun wifiSignalUsesHomeAssistantDiagnosticSignalSchema() {
        assertEquals(
            """{"name":"Wi-Fi signal strength","object_id":"test_diag_wifi_rssi","unique_id":"test_diag_wifi_rssi","state_topic":"ha-paneld/test/diag_wifi_rssi/state","device_class":"signal_strength","unit_of_measurement":"dBm","state_class":"measurement","icon":"mdi:wifi","entity_category":"diagnostic",$avail,$device}""",
            build("diag_wifi_rssi"),
        )
    }

    @Test fun adaptiveBrightnessTuningRemainsPanelLocal() {
        assertNull(SettingsRegistry.spec("auto_brightness_minimum_percent")!!.ha)
        assertNull(SettingsRegistry.spec("auto_brightness_sensitivity")!!.ha)
        assertNull(SettingsRegistry.spec("auto_brightness_ha_entity")!!.ha)
        assertNull(SettingsRegistry.spec("brightness_bias"))
        assertNull(SettingsRegistry.spec("ambient_lux"))
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
        assertTrue(s.availableWhen(Capabilities(hasProximity = true, hasLearnedProximity = true)))
        assertTrue(s.availableWhen(Capabilities(hasProximity = true, hasLearnedProximity = false)))
        assertTrue(!s.availableWhen(Capabilities(hasProximity = false, hasLearnedProximity = false)))
    }
}
