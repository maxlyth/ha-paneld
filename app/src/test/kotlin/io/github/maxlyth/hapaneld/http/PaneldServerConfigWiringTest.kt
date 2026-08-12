package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.MqttBridge
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneldServerConfigWiringTest {
    @Test fun homeDashboardPickerUsesHaDashboardListWithoutStartingEntityLearning() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val configure = File("src/main/assets/configure.js").readText()
        assertTrue(source.contains("get(\"/config/home-dashboards\")"))
        assertTrue(source.contains("entityLearning.homeDashboardCatalog()"))
        assertTrue(configure.contains("fetch(\"/api/v1/config/home-dashboards\""))
        assertTrue(configure.contains("if (f.picker === \"ha_dashboard\")"))
        // Deliberately a NATIVE select on this form (a custom popup was tried and was a bust on hardware
        // review — see the branch comment); HA's grouping survives as optgroups, Auto stays first and
        // says what it actually does.
        assertFalse("the Configure form must not render the wizard's custom picker",
            configure.contains("window.haDashboardPicker("))
        assertTrue(configure.contains("el(\"optgroup\""))
        assertTrue(configure.contains("Auto — follow this account’s default"))
        assertTrue(configure.contains("Auto — no default set for this account"))
    }

    @Test fun httpRoutesEveryApplicableLiveSettingThroughTheSharedDispatcher() {
        val registryKeys = SettingsRegistry.liveApplyKeys()
        val expectedKeys = setOf(
            "auto_brightness", "auto_brightness_ha_entity", "auto_brightness_minimum_percent",
            "auto_brightness_response_percent", "auto_sleep", "companion_auto_update",
            "companion_update_channel", "cpu_governor", "ha_area", "home_dashboard", "kiosk_lock",
            "navbar_mode", "network_adb", "prevent_idle_dim", "self_update", "silence_boot_chime",
            "touch_sound", "update_channel", "wake_on_wave", "watchdog_enabled", "webview_auto_update",
            "zigbee_router",
        )

        assertEquals(expectedKeys, registryKeys.toSet())
        assertEquals(registryKeys, PaneldServer.HTTP_LIVE_KEYS)
        assertEquals(expectedKeys, MqttBridge.APPLY_SETTING_KEYS)
        assertTrue(registryKeys.all { SettingsRegistry.spec(it)?.readOnly == false })
    }
}
