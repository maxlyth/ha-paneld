package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRegistryPresentationTest {
    @Test fun networkAdbIsLastSystemSettingAndRetainsSafeDefaults() {
        val spec = SettingsRegistry.spec("network_adb")!!
        assertEquals("network_adb", SettingsRegistry.SPECS.last { it.group == "System" }.key)
        assertEquals("false", spec.default)
        assertEquals(Tier.ADVANCED, spec.tier)
        assertFalse(spec.haExposedByDefault)
        assertEquals(
            "Security risk: keeps classic ADB listening on TCP port 5555 across boots and reconnects. " +
                "Enable only during active maintenance on a trusted network. If ADB was enabled outside " +
                "ha-paneld, it must also be disabled there.",
            spec.help,
        )
    }

    @Test fun launcherHelpExplainsAutomaticAndEnforcedHomeModes() {
        val help = SettingsRegistry.spec("launcher_package")!!.help
        assertTrue(help.contains("Blank auto-picks"))
        assertTrue(help.contains("Panel admin (ha-paneld)"))
        assertTrue(help.contains("Android Home app"))
    }

    @Test fun lastBootTimeRenamesPresentationWithoutChangingIdentity() {
        val spec = SettingsRegistry.spec("diag_boot")!!
        assertEquals("Last boot time", spec.label)
        assertEquals("sensor", spec.ha!!.component)
        assertEquals("diag_boot", spec.ha!!.objectSuffix)
        assertEquals("Last boot time", spec.ha!!.name)
        assertTrue(spec.ha!!.body.contains("\"device_class\":\"timestamp\""))
        assertTrue(spec.ha!!.readOnly)
    }

    @Test fun sensorsCardImmediatelyPrecedesDiagnosticsAndContainsOnlySensorReadings() {
        val groups = SettingsRegistry.SPECS.map { it.group }.distinct()
        assertEquals(groups.indexOf("Sensors") + 1, groups.indexOf("Diagnostics"))
        assertEquals(
            listOf("screen", "volume", "illuminance", "proximity", "proximity_level", "temperature", "humidity", "room_temp", "room_humidity", "room_temp_offset"),
            SettingsRegistry.SPECS.filter { it.group == "Sensors" }.map { it.key },
        )
        val screen = SettingsRegistry.spec("screen")!!
        val volume = SettingsRegistry.spec("volume")!!
        assertTrue(screen.readOnly)
        assertTrue(volume.readOnly)
        assertTrue(screen.haExposedByDefault)
        assertTrue(volume.haExposedByDefault)
        assertEquals("light", screen.ha!!.component)
        assertEquals("screen", screen.ha!!.objectSuffix)
        assertEquals("number", volume.ha!!.component)
        assertEquals("volume", volume.ha!!.objectSuffix)
        assertTrue(SettingsRegistry.spec("illuminance")!!.availableWhen(Capabilities(hasLight = true)))
        assertFalse(SettingsRegistry.spec("illuminance")!!.availableWhen(Capabilities()))
        assertTrue(SettingsRegistry.spec("proximity")!!.availableWhen(Capabilities(hasRangedProximity = true)))
        assertFalse(SettingsRegistry.spec("proximity")!!.availableWhen(Capabilities(hasProximity = true)))
        listOf("illuminance", "proximity", "proximity_level", "temperature", "humidity").forEach {
            assertTrue(SettingsRegistry.spec(it)!!.haExposedByDefault)
            assertTrue(SettingsRegistry.spec(it)!!.ha!!.readOnly)
        }
    }

    @Test fun wifiReadingsRemainOptInDiagnosticsAndUseNativeSignalUnits() {
        val ssid = SettingsRegistry.spec("diag_wifi_ssid")!!
        val rssi = SettingsRegistry.spec("diag_wifi_rssi")!!
        assertEquals("Diagnostics", ssid.group)
        assertEquals("Diagnostics", rssi.group)
        assertFalse(ssid.haExposedByDefault)
        assertFalse(rssi.haExposedByDefault)
        assertFalse(ssid.availableWhen(Capabilities()))
        assertFalse(ssid.availableWhen(Capabilities(hasWifi = true)))
        assertTrue(ssid.availableWhen(Capabilities(hasWifi = true, hasWifiSsid = true)))
        assertTrue(rssi.availableWhen(Capabilities(hasWifi = true)))
        assertTrue(rssi.ha!!.body.contains("\"device_class\":\"signal_strength\""))
        assertTrue(rssi.ha!!.body.contains("\"unit_of_measurement\":\"dBm\""))
        assertTrue(rssi.ha!!.body.contains("\"entity_category\":\"diagnostic\""))
    }
}
