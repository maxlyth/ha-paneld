package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Test

class FleetScopePolicyTest {
    @Test fun `panel-specific identities routes credentials and tuning are not fleet portable`() {
        val deviceKeys = setOf(
            "mqtt_user",
            "mqtt_password",
            "auto_brightness",
            "brightness_bias",
            "cpu_governor",
            "dashboard_package",
            "dashboard_entity_learning",
            "home_dashboard",
            "ha_token",
            "ha_refresh_token",
            "ha_client_id",
            "dashboard_entity_overrides",
            "dashboard_entity_learning_applied",
            "dashboard_zoom",
            "launcher_package",
        )

        deviceKeys.forEach { key ->
            assertEquals("$key must not be cloned by a fleet import", Scope.DEVICE, spec(key).scope)
        }
    }

    @Test fun `shared endpoints remain intentionally fleet portable`() {
        setOf("mqtt_broker", "ha_url").forEach { key ->
            assertEquals("$key should remain cloneable across a site", Scope.PORTABLE, spec(key).scope)
        }
    }

    private fun spec(key: String): SettingSpec =
        requireNotNull(SettingsRegistry.spec(key)) { "missing setting: $key" }
}
