package io.github.maxlyth.hapaneld

import android.content.SharedPreferences
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Guard for the "collapse the typed Config accessors onto SettingSpec defaults" consolidation: every
 * typed accessor that used to re-declare its own literal default now resolves that default from the
 * [SettingsRegistry] SettingSpec for its key. Two invariants are pinned:
 *
 *  1. On an empty store each accessor resolves to the byte-identical default it produced before the
 *     refactor (the literal-regression lock). A wrong default silently changes runtime behaviour on
 *     every panel, so these historical values must never move by accident.
 *  2. Each accessor's resolved default equals the value the registry-authoritative [Config.getRaw]
 *     path resolves from the same key — proving the SettingSpec is the single source of the default
 *     and the accessor cannot drift from it.
 */
class ConfigAccessorDefaultCoherenceTest {

    @Test fun px30VendorNavbarOverrideWinsOverAndroidResourceDefault() {
        assertEquals("Swipe reveal", defaultNavbarMode(androidResourceShowsNavbar = true, vendorShowsNavbar = "false"))
        assertEquals("Off", defaultNavbarMode(androidResourceShowsNavbar = false, vendorShowsNavbar = "true"))
        assertEquals("Swipe reveal", defaultNavbarMode(androidResourceShowsNavbar = false, vendorShowsNavbar = ""))
        assertEquals("Swipe reveal", defaultNavbarMode(androidResourceShowsNavbar = true, vendorShowsNavbar = "", profileId = "nspanel-pro"))
    }

    @Test fun behaviourOrderKeepsIdleDimAboveKeepResponsive() {
        val behaviour = SettingsRegistry.SPECS.filter { it.group == "Behaviour" }.map { it.key }
        assertTrue(behaviour.indexOf("navbar_mode") == behaviour.indexOf("auto_sleep") + 1)
        assertTrue(behaviour.indexOf("wake_on_wave") == behaviour.indexOf("navbar_mode") + 1)
        assertTrue(behaviour.indexOf("watchdog_enabled") == behaviour.indexOf("kiosk_lock") + 1)
        assertTrue(behaviour.indexOf("touch_sound") < behaviour.indexOf("silence_boot_chime"))
        assertTrue(behaviour.indexOf("prevent_idle_dim") < behaviour.indexOf("keep_awake"))
    }

    private fun emptyConfig(): Config = Config(readOnlyPreferences())

    /** Intended product default of each migrated accessor, on a fresh (empty) store. */
    @Test fun accessorDefaultsMatchProductDefaults() {
        val c = emptyConfig()

        // Strings that historically defaulted to "".
        listOf(
            c.mqttBroker, c.mqttUser, c.mqttPassword, c.dashboardPackage, c.haUrl, c.haToken,
            c.haRefreshToken, c.haClientId, c.launcherPackage, c.tameVendorPackagesRaw, c.homeDashboard,
            c.autoBrightnessHaEntity, c.logShipHost, c.manufacturerRaw, c.modelRaw,
        ).forEach { assertEquals("", it) }

        // Non-empty string defaults.
        assertEquals("Off", c.navbarMode)
        assertEquals("stable", c.companionUpdateChannel)
        assertEquals("stable", c.updateChannel)
        assertEquals("syslog-tcp", c.logShipProtocol)
        assertEquals("720p", c.cameraMaxResolution.wire)

        // Booleans that default true.
        listOf(
            c.darkMode, c.preventIdleDim, c.keepAwake, c.dashboardFullscreen,
            c.dashboardEntityAutoStatic, c.dashboardEntityAutoRuntime, c.dashboardNativeKiosk,
            c.silenceBootChime, c.selfUpdate,
        ).forEach { assertTrue(it) }

        // Booleans that default false.
        listOf(
            c.wakeOnWave, c.autoSleep, c.watchdogEnabled, c.kioskLock, c.companionAutoUpdate,
            c.webViewAutoUpdate, c.dashboardOverscroll,
            c.dashboardEntityLearningEnabled, c.dashboardEntityLearningApplied, c.logShipEnabled,
            c.autoBrightness, c.cameraEnabled,
        ).forEach { assertEquals(false, it) }

        // Numeric defaults.
        assertEquals(0, c.dashboardIdleReturnMin)
        assertEquals(100, c.dashboardZoom)
        assertEquals(50, c.autoBrightnessResponsePercent)
        assertEquals(4, c.autoBrightnessMinimumPercent)
        assertEquals(514, c.logShipPort)
        assertEquals(0L, c.haTokenExpiry)
        assertEquals(0f, c.roomTempOffsetC, 0f)
        assertEquals(15, c.cameraMaxFps)
        assertEquals(2000, c.cameraMaxKbps)

        // Compound accessors whose inner default read was migrated.
        assertEquals(emptyList<String>(), c.tameVendorPackages)
        assertTrue(c.dashboardEntityOverrides.isEmpty())
    }

    /**
     * Each scalar accessor resolves to exactly the default the registry path resolves from the same
     * key, so the SettingSpec is the sole authority. Every listed key must exist in the registry.
     */
    @Test fun scalarAccessorDefaultsResolveFromSettingSpec() {
        val c = emptyConfig()
        fun raw(key: String): String {
            val spec = SettingsRegistry.spec(key)
            assertNotNull("no SettingSpec registered for '$key'", spec)
            return c.getRaw(spec!!)
        }

        val rendered: Map<String, String> = mapOf(
            "mqtt_broker" to c.mqttBroker,
            "mqtt_user" to c.mqttUser,
            "mqtt_password" to c.mqttPassword,
            "mqtt_address_family" to c.mqttAddressFamily,
            "dashboard_package" to c.dashboardPackage,
            "ha_url" to c.haUrl,
            "ha_token" to c.haToken,
            "ha_refresh_token" to c.haRefreshToken,
            "ha_client_id" to c.haClientId,
            "launcher_package" to c.launcherPackage,
            "tame_vendor_packages" to c.tameVendorPackagesRaw,
            "home_dashboard" to c.homeDashboard,
            "auto_brightness_ha_entity" to c.autoBrightnessHaEntity,
            "log_ship_host" to c.logShipHost,
            "manufacturer" to c.manufacturerRaw,
            "model" to c.modelRaw,
            "navbar_mode" to c.navbarMode,
            "companion_update_channel" to c.companionUpdateChannel,
            "update_channel" to c.updateChannel,
            "log_ship_protocol" to c.logShipProtocol,
            "dark_mode" to c.darkMode.toString(),
            "wake_on_wave" to c.wakeOnWave.toString(),
            "prevent_idle_dim" to c.preventIdleDim.toString(),
            "keep_awake" to c.keepAwake.toString(),
            "dashboard_fullscreen" to c.dashboardFullscreen.toString(),
            "dashboard_entity_auto_static" to c.dashboardEntityAutoStatic.toString(),
            "dashboard_entity_auto_runtime" to c.dashboardEntityAutoRuntime.toString(),
            "auto_sleep" to c.autoSleep.toString(),
            "watchdog_enabled" to c.watchdogEnabled.toString(),
            "kiosk_lock" to c.kioskLock.toString(),
            "companion_auto_update" to c.companionAutoUpdate.toString(),
            "self_update" to c.selfUpdate.toString(),
            "webview_auto_update" to c.webViewAutoUpdate.toString(),
            "dashboard_native_kiosk" to c.dashboardNativeKiosk.toString(),
            "dashboard_overscroll" to c.dashboardOverscroll.toString(),
            "dashboard_entity_learning" to c.dashboardEntityLearningEnabled.toString(),
            "log_ship_enabled" to c.logShipEnabled.toString(),
            "auto_brightness" to c.autoBrightness.toString(),
            "silence_boot_chime" to c.silenceBootChime.toString(),
            "dashboard_idle_return_min" to c.dashboardIdleReturnMin.toString(),
            "dashboard_zoom" to c.dashboardZoom.toString(),
            "auto_brightness_response_percent" to c.autoBrightnessResponsePercent.toString(),
            "auto_brightness_minimum_percent" to c.autoBrightnessMinimumPercent.toString(),
            "log_ship_port" to c.logShipPort.toString(),
            "ha_token_expiry" to c.haTokenExpiry.toString(),
            "room_temp_offset" to c.roomTempOffsetC.toString(),
            "camera_enabled" to c.cameraEnabled.toString(),
            "camera_max_resolution" to c.cameraMaxResolution.wire,
            "camera_max_fps" to c.cameraMaxFps.toString(),
            "camera_max_kbps" to c.cameraMaxKbps.toString(),
        )

        rendered.forEach { (key, accessorValue) ->
            assertEquals("accessor default for '$key' must match its SettingSpec", raw(key), accessorValue)
        }
    }

    /** Read-only SharedPreferences proxy: every key is absent, so every read returns its default. */
    private fun readOnlyPreferences(): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> emptyMap<String, Any?>()
                "getString" -> args!![1]
                "getStringSet" -> args!![1]
                "getInt" -> args!![1]
                "getLong" -> args!![1]
                "getFloat" -> args!![1]
                "getBoolean" -> args!![1]
                "contains" -> false
                "registerOnSharedPreferenceChangeListener",
                "unregisterOnSharedPreferenceChangeListener",
                -> null
                "toString" -> "ReadOnlyEmptyPreferences"
                else -> error("unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences
}
