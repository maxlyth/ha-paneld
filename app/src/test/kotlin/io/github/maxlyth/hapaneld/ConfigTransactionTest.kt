package io.github.maxlyth.hapaneld

import android.content.SharedPreferences
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.control.fakeProfile
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.util.HaLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConfigTransactionTest {
    @Test fun cachedHaVersionIsScopedToTheExactCurrentRendererEndpoint() {
        val prefs = fakePreferences(initial = mapOf("ha_url" to "https://ha.example"))
        val config = Config(prefs.instance)

        assertTrue(config.setHaServerVersionIfOwned("https://ha.example/", "2026.4.2"))
        assertEquals("2026.4.2", config.cachedHaServerVersion("https://ha.example"))
        assertNull(config.cachedHaServerVersion("https://other.example"))
        assertFalse(config.setHaServerVersionIfOwned("https://other.example", "2027.1.0"))
        assertEquals("2026.4.2", config.cachedHaServerVersion("https://ha.example"))
    }

    @Test fun generatedPanelIdentityIsPersistedOnce() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertEquals("px30_evb_b818", config.ensurePanelId { "px30_evb_b818" })
        assertEquals("px30_evb_b818", prefs.values["panel_id"])
        assertEquals("px30_evb_b818", config.ensurePanelId { "changed_device_name" })
        assertEquals("px30_evb_b818", prefs.values["panel_id"])
    }

    @Test fun explicitPanelIdentityIsNeverReplacedByGeneration() {
        val prefs = fakePreferences(initial = mapOf("panel_id" to "office_panel"))
        val config = Config(prefs.instance)

        assertEquals("office_panel", config.ensurePanelId { "generated_panel" })
        assertEquals("office_panel", prefs.values["panel_id"])
    }

    @Test fun failedGeneratedIdentityCommitStillReturnsTheGeneratedValue() {
        val prefs = fakePreferences(commitSucceeds = false)
        val config = Config(prefs.instance)

        assertEquals("px30_evb_b818", config.ensurePanelId { "px30_evb_b818" })
        assertFalse(prefs.values.containsKey("panel_id"))
    }

    @Test fun nativeKioskDefaultsOnAndPersistsExplicitOverrides() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertTrue(config.dashboardNativeKiosk)
        assertFalse(prefs.values.containsKey("dashboard_native_kiosk"))
        config.setDashboardNativeKiosk(false)
        assertFalse(config.dashboardNativeKiosk)
        assertEquals(false, prefs.values["dashboard_native_kiosk"])
        config.setDashboardNativeKiosk(true)
        assertTrue(config.dashboardNativeKiosk)
        assertEquals(true, prefs.values["dashboard_native_kiosk"])
    }

    @Test fun autoSleepDefaultsOffAndPersistsBeforeRuntimeRefresh() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertFalse(config.autoSleep)

        assertEquals(AutoSleepWriteResult.COMMITTED, config.setAutoSleep(true))
        val enabledGeneration = config.autoSleepGeneration
        assertTrue(Config(prefs.instance).autoSleep)
        assertEquals(AutoSleepWriteResult.UNCHANGED, config.setAutoSleep(true))
        assertEquals(null, config.setAutoSleepIf(
            expected = false, expectedGeneration = enabledGeneration, on = false,
        ))
        assertTrue(config.autoSleep)
        assertEquals(AutoSleepWriteResult.COMMITTED, config.setAutoSleepIf(
            expected = true, expectedGeneration = enabledGeneration, on = false,
        ))
        assertFalse(config.autoSleep)
        assertTrue(config.autoSleepGeneration > enabledGeneration)
    }

    @Test fun autoSleepSourceExclusionsPersistByHaInstallationAreaAndEntityId() {
        val prefs = fakePreferences(initial = mapOf("ha_url" to "https://ha.example"))
        val config = Config(prefs.instance)

        assertTrue(config.setAutoSleepSourceIncluded("office", "binary_sensor.office_motion", false))
        assertEquals(setOf("binary_sensor.office_motion"),
            Config(prefs.instance).autoSleepExcludedEntityIds("office"))
        assertTrue(Config(prefs.instance).autoSleepExcludedEntityIds("kitchen").isEmpty())

        assertTrue(config.setAutoSleepSourceIncluded("office", "binary_sensor.office_motion", true))
        assertTrue(Config(prefs.instance).autoSleepExcludedEntityIds("office").isEmpty())
    }

    @Test fun autoSleepSourceExclusionsDoNotCrossHomeAssistantInstallations() {
        val prefs = fakePreferences(initial = mapOf("ha_url" to "https://ha-one.example"))
        val config = Config(prefs.instance)
        assertTrue(config.setAutoSleepSourceIncluded("office", "binary_sensor.office_motion", false))

        config.setHaConnection("https://ha-two.example", null)

        assertTrue(config.autoSleepExcludedEntityIds("office").isEmpty())
        assertEquals(
            null,
            config.setAutoSleepSourceIncludedIfScope(
                "origin:https://ha-one.example",
                "office",
                "binary_sensor.office_motion",
                false,
            ),
        )
        assertTrue(config.autoSleepExcludedEntityIds("office").isEmpty())
    }

    @Test fun autoSleepSourceExclusionsSurviveAsynchronousHaUuidAdoption() {
        val prefs = fakePreferences(initial = mapOf("ha_url" to "https://ha.example"))
        val config = Config(prefs.instance)
        assertTrue(config.setAutoSleepSourceIncluded("office", "binary_sensor.office_motion", false))

        prefs.instance.edit().putString("dashboard_entity_instance_uuid", "core-uuid").commit()

        assertEquals(setOf("binary_sensor.office_motion"), config.autoSleepExcludedEntityIds("office"))
        assertTrue(config.setAutoSleepSourceIncluded("office", "binary_sensor.office_motion", true))
        config.setHaConnection("https://ha-alias.example", null)
        assertTrue(config.autoSleepExcludedEntityIds("office").isEmpty())
    }

    @Test fun failedAutoSleepCommitDoesNotChangeTheStoredValue() {
        val prefs = fakePreferences(commitSucceeds = false)
        val config = Config(prefs.instance)

        assertEquals(AutoSleepWriteResult.FAILED, config.setAutoSleep(true))
        assertFalse(config.autoSleep)
    }

    @Test fun launchScreenVersionAcknowledgementIsExactAndDurable() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertNull(config.lastLaunchScreenVersionCode)
        assertTrue(config.commitLaunchScreenVersionShown(323L))
        assertEquals(323L, Config(prefs.instance).lastLaunchScreenVersionCode)
        assertTrue(config.commitLaunchScreenVersionShown(322L))
        assertEquals(322L, Config(prefs.instance).lastLaunchScreenVersionCode)
    }

    @Test fun failedLaunchScreenAcknowledgementRemainsUnseen() {
        val prefs = fakePreferences(commitSucceeds = false)
        val config = Config(prefs.instance)

        assertFalse(config.commitLaunchScreenVersionShown(323L))
        assertNull(config.lastLaunchScreenVersionCode)
    }

    @Test fun powerSafetyAcknowledgementIsExactReplaceableAndDurable() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        val first = "a".repeat(64)
        val second = "b".repeat(64)

        assertEquals("", config.powerSafetyAcknowledgementFingerprint)
        assertFalse(config.commitPowerSafetyAcknowledgement("not-a-fingerprint"))
        assertTrue(config.commitPowerSafetyAcknowledgement(first))
        assertEquals(first, Config(prefs.instance).powerSafetyAcknowledgementFingerprint)
        assertTrue(config.commitPowerSafetyAcknowledgement(second))
        assertEquals(second, Config(prefs.instance).powerSafetyAcknowledgementFingerprint)
    }

    @Test fun failedPowerSafetyAcknowledgementRemainsUnseen() {
        val prefs = fakePreferences(commitSucceeds = false)
        val config = Config(prefs.instance)

        assertFalse(config.commitPowerSafetyAcknowledgement("a".repeat(64)))
        assertEquals("", config.powerSafetyAcknowledgementFingerprint)
    }

    @Test fun automaticBrightnessMinimumDefaultsAndPersistsWithinPublicBounds() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertEquals(4, config.autoBrightnessMinimumPercent)
        config.setAutoBrightnessMinimumPercent(30)
        assertEquals(30, config.autoBrightnessMinimumPercent)
        assertEquals(30, prefs.values["auto_brightness_minimum_percent"])
        config.setAutoBrightnessMinimumPercent(0)
        assertEquals(4, config.autoBrightnessMinimumPercent)
        config.setAutoBrightnessMinimumPercent(100)
        assertEquals(95, config.autoBrightnessMinimumPercent)
    }

    @Test fun hardenedSecurityAndOwnedNetworkAdbAreDurablyMutuallyExclusive() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertTrue(config.setNetworkAdbEnabled(true))
        assertFalse(config.setSecurityMode(Config.SecurityMode.HARDENED))
        assertEquals(Config.SecurityMode.RELAXED, config.securityMode)

        assertTrue(config.setNetworkAdbEnabled(false))
        assertTrue(config.setSecurityMode(Config.SecurityMode.HARDENED))
        assertFalse(config.setNetworkAdbEnabled(true))
        assertFalse(config.networkAdbEnabled)

        assertTrue(config.setSecurityMode(Config.SecurityMode.RELAXED))
        assertTrue(config.setNetworkAdbEnabled(true))
    }

    @Test fun roomTemperatureOffsetRejectsNonFiniteFormValues() {
        val prefs = fakePreferences(initial = mapOf("room_temp_offset" to 1.5f))
        val config = Config(prefs.instance)

        listOf("NaN", "Infinity", "+Infinity", "-Infinity").forEach(config::setRoomTempOffset)

        assertEquals(1.5f, config.roomTempOffsetC, 0f)
        assertEquals(1.5f, prefs.values["room_temp_offset"])
    }

    @Test fun adaptiveLearnerConsumesCurrentProfileCaptureOnceAndRetiresAllOldCalibration() {
        val prefs = fakePreferences()
        val calibration = fakePreferences(initial = mapOf(
            "profile_calibration.first-panel.revision-a.prox_near_raw" to 2f,
            "profile_calibration.first-panel.revision-a.prox_far_raw" to 10f,
            "profile_calibration.first-panel.revision-a.prox_threshold" to 6f,
            "profile_calibration.first-panel.revision-a.prox_near_below" to true,
            "profile_calibration.first-panel.revision-a.prox_sensitivity" to "LOW",
            "profile_calibration.other.revision.prox_near_raw" to 20f,
            "profile_calibration.other.revision.prox_far_raw" to 40f,
            "proximity_profile_calibration_migrated" to true,
            "unrelated" to 7,
        ))
        val config = Config(prefs.instance, calibration.instance)
        config.attachProfile(profile("first-panel"))

        assertEquals(ProximityLearningSeed(2f, 10f), config.consumeLegacyProximityLearningSeed())
        assertEquals(null, config.consumeLegacyProximityLearningSeed())
        assertEquals(7, calibration.values["unrelated"])
        assertFalse(calibration.values.keys.any(::isLegacyProximityKey))
    }

    @Test fun adaptiveLearnerCanConsumeUnscopedCaptureAndPurgesIncompleteStateFromBothStores() {
        val prefs = fakePreferences(initial = mapOf(
            "prox_near_raw" to 3f,
            "prox_far_raw" to 9f,
            "prox_threshold" to 6f,
            "prox_near_below" to true,
            "prox_sensitivity" to "HIGH",
        ))
        val calibration = fakePreferences(initial = mapOf(
            "profile_calibration.legacy-panel.revision-a.prox_near_raw" to Float.NaN,
            "profile_calibration.legacy-panel.revision-a.prox_threshold" to 12f,
        ))
        val config = Config(prefs.instance, calibration.instance)

        config.attachProfile(profile("legacy-panel"))
        assertEquals(ProximityLearningSeed(3f, 9f), config.consumeLegacyProximityLearningSeed())
        assertFalse(prefs.values.keys.any(::isLegacyProximityKey))
        assertFalse(calibration.values.keys.any(::isLegacyProximityKey))
    }

    @Test fun adaptiveLearnerFindsCaptureFromTheProfilesPreviousRevision() {
        val prefs = fakePreferences()
        val calibration = fakePreferences(initial = mapOf(
            "profile_calibration.panel.previous-revision.prox_near_raw" to 4f,
            "profile_calibration.panel.previous-revision.prox_far_raw" to 14f,
        ))
        val config = Config(prefs.instance, calibration.instance)
        config.attachProfile(profile("panel", "new-revision"))

        assertEquals(ProximityLearningSeed(4f, 14f), config.consumeLegacyProximityLearningSeed())
        assertFalse(calibration.values.keys.any(::isLegacyProximityKey))
    }

    private fun isLegacyProximityKey(key: String): Boolean =
        key == "proximity_profile_calibration_migrated" || listOf(
            "prox_near_raw", "prox_far_raw", "prox_threshold", "prox_near_below", "prox_sensitivity",
        ).any { key == it || key.endsWith(".$it") }

    @Test fun buttonBacklightReplayIsScopedToARevisionThatDeclaresTheCapability() {
        val prefs = fakePreferences(initial = mapOf("last_button_backlight" to 41))
        val calibration = fakePreferences()
        val config = Config(prefs.instance, calibration.instance)
        config.attachProfile(profile("panel", "revision-a", hasButtonBacklight = true))
        assertEquals(41, config.lastButtonBacklight)
        assertFalse(prefs.values.containsKey("last_button_backlight"))
        config.lastButtonBacklight = 73
        assertEquals(73, config.lastButtonBacklight)

        config.attachProfile(profile("panel", "revision-b", hasButtonBacklight = false))
        assertEquals(-1, config.lastButtonBacklight)
        config.lastButtonBacklight = 200

        config.attachProfile(profile("panel", "revision-a", hasButtonBacklight = true))
        assertEquals(73, config.lastButtonBacklight)
    }

    @Test fun defaultResolverUpgradeInvalidatesOldAutomaticFilterButPreservesItsIds() {
        val target = dashboardEntityTargetKey("url-key", "/")
        val prefs = fakePreferences(initial = mapOf(
            "ha_url" to "http://ha.local:8123",
            "home_dashboard" to "",
            "dashboard_entity_instance" to "url-key",
            "dashboard_entity_instance_origin" to "http://ha.local:8123",
            "dashboard_entity_dashboard_path" to "/",
            "dashboard_entity_filter_instance" to target,
            "dashboard_entity_applied_instance" to target,
            "dashboard_entity_learning" to true,
            "dashboard_entity_learning_applied" to true,
            "dashboard_entity_filter_enabled" to true,
            "dashboard_entity_filter_ids" to "light.old\nsensor.old",
        ))
        val config = Config(prefs.instance)

        assertFalse("the suspect stream must fail closed before the migration commits", config.dashboardEntityFilterEnabled)
        assertEquals(DashboardEntityDefaultResolverMigration.REBOOTSTRAP,
            config.migrateDashboardEntityDefaultResolver())

        assertFalse(config.dashboardEntityFilterEnabled)
        assertFalse(config.dashboardEntityLearningApplied)
        assertEquals(listOf("light.old", "sensor.old"), config.dashboardEntityFilterIds)
        assertEquals(1, prefs.values["dashboard_entity_default_resolver_version"])
        assertEquals(target, prefs.values["dashboard_entity_default_resolver_target"])
        assertEquals(target, prefs.values["dashboard_entity_default_resolver_pending"])
        assertEquals(DashboardEntityDefaultResolverMigration.NOT_NEEDED,
            config.migrateDashboardEntityDefaultResolver())

        // A process restart must retain both the native hold and the obligation to replace the set.
        val restarted = Config(prefs.instance)
        assertTrue(restarted.dashboardEntityDefaultResolverRebootstrapPending)
        assertFalse(restarted.dashboardEntityFilterEnabled)
        assertTrue(restarted.commitDashboardEntitySubscription(true, listOf("light.new"), applied = true))
        assertFalse("the replacement cannot activate before its latch clears", restarted.dashboardEntityFilterEnabled)
        assertTrue(restarted.clearDashboardEntityDefaultResolverRebootstrapPending())
        assertTrue(restarted.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.new"), restarted.dashboardEntityFilterIds)
    }

    @Test fun defaultResolverUpgradeDoesNotInvalidateExplicitDashboardFilter() {
        val target = dashboardEntityTargetKey("url-key", "/lovelace/kiosk")
        val prefs = fakePreferences(initial = mapOf(
            "ha_url" to "http://ha.local:8123",
            "home_dashboard" to "/lovelace/kiosk",
            "dashboard_entity_instance" to "url-key",
            "dashboard_entity_instance_origin" to "http://ha.local:8123",
            "dashboard_entity_dashboard_path" to "/lovelace/kiosk",
            "dashboard_entity_filter_instance" to target,
            "dashboard_entity_applied_instance" to target,
            "dashboard_entity_learning" to true,
            "dashboard_entity_learning_applied" to true,
            "dashboard_entity_filter_enabled" to true,
            "dashboard_entity_filter_ids" to "light.kiosk",
        ))
        val config = Config(prefs.instance)

        assertEquals(DashboardEntityDefaultResolverMigration.NOT_NEEDED,
            config.migrateDashboardEntityDefaultResolver())

        assertTrue(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityLearningApplied)
        assertEquals(listOf("light.kiosk"), config.dashboardEntityFilterIds)
        assertFalse(prefs.values.containsKey("dashboard_entity_default_resolver_version"))
    }

    @Test fun failedDefaultResolverUpgradeKeepsOldAutomaticFilterFailClosed() {
        val target = dashboardEntityTargetKey("url-key", "/")
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/",
                "dashboard_entity_filter_instance" to target,
                "dashboard_entity_applied_instance" to target,
                "dashboard_entity_learning" to true,
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.old",
            ),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertEquals(DashboardEntityDefaultResolverMigration.PERSIST_FAILED,
            config.migrateDashboardEntityDefaultResolver())

        assertFalse(config.dashboardEntityFilterEnabled)
        assertEquals(true, prefs.values["dashboard_entity_filter_enabled"])
        assertEquals(true, prefs.values["dashboard_entity_learning_applied"])
        assertFalse(prefs.values.containsKey("dashboard_entity_default_resolver_version"))
        assertFalse(prefs.values.containsKey("dashboard_entity_default_resolver_pending"))
    }

    @Test fun disablingAndReenablingLearningRestoresThePreservedNarrowStream() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        assertTrue(config.commitDashboardEntitySubscription(true, listOf("light.kitchen"), applied = true))

        assertTrue(config.commitDashboardEntityLearningMode(enabled = false, clearApplied = true))
        assertFalse(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.kitchen"), config.dashboardEntityFilterIds)

        assertTrue(config.commitDashboardEntityLearningMode(enabled = true, clearApplied = true))
        assertTrue(config.dashboardEntityLearningEnabled)
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.kitchen"), config.dashboardEntityFilterIds)
        assertFalse(config.dashboardEntityLearningApplied)
    }

    @Test fun entityPromotionSourcesDefaultOnAndPersistIndependently() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertTrue(config.dashboardEntityAutoStatic)
        assertTrue(config.dashboardEntityAutoRuntime)
        assertTrue(config.setDashboardEntityAutoPolicy(staticRefs = false, runtimeRefs = true))
        assertFalse(config.dashboardEntityAutoStatic)
        assertTrue(config.dashboardEntityAutoRuntime)
    }

    @Test fun livePreferenceSettersRemainStagedUntilTheBatchCommits() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        val committed = config.applyBatch {
            config.setWakeOnWave(true)
            config.setCompanionUpdateChannel("pre-release")
            assertFalse(config.wakeOnWave)
            assertEquals("stable", config.companionUpdateChannel)
        }

        assertTrue(committed)
        assertTrue(config.wakeOnWave)
        assertEquals("prerelease", config.companionUpdateChannel)
    }

    @Test fun explicitWakeOnWaveChoicesOverrideTheOptInDefault() {
        assertTrue(Config(fakePreferences(initial = mapOf("wake_on_wave" to true)).instance).wakeOnWave)
        assertFalse(Config(fakePreferences(initial = mapOf("wake_on_wave" to false)).instance).wakeOnWave)
    }

    @Test fun schemaThreeUpgradePreservesImplicitWakeAndHaExposureDefaults() {
        val prefs = fakePreferences(initial = mapOf("config_schema" to 3))
        val config = Config(prefs.instance)

        config.migrateLiveStore()

        assertTrue(config.wakeOnWave)
        SettingsRegistry.LEGACY_DEFAULT_ON_HA_EXPOSURES.forEach { key ->
            assertTrue(config.haExposed(key, false))
        }
        assertEquals(SettingsRegistry.SCHEMA, prefs.values["config_schema"])
    }

    @Test fun freshStoreReceivesNewOptInDefaultsWhileExplicitLegacyChoicesWin() {
        val fresh = fakePreferences()
        val freshConfig = Config(fresh.instance)
        freshConfig.migrateLiveStore()
        assertFalse(freshConfig.wakeOnWave)
        SettingsRegistry.LEGACY_DEFAULT_ON_HA_EXPOSURES.forEach { key ->
            assertFalse(freshConfig.haExposed(key, false))
        }

        val explicit = fakePreferences(initial = mapOf(
            "config_schema" to 3,
            "wake_on_wave" to false,
            "ha_expose_auto_sleep" to false,
        ))
        val explicitConfig = Config(explicit.instance)
        explicitConfig.migrateLiveStore()
        assertFalse(explicitConfig.wakeOnWave)
        assertFalse(explicitConfig.haExposed("auto_sleep", true))
    }

    @Test fun everyWakeOnWaveWriteInvalidatesAlreadyAdmittedGestureWork() {
        val config = Config(fakePreferences().instance)
        val initial = config.wakeOnWaveGeneration

        config.setWakeOnWave(false)
        val disabled = config.wakeOnWaveGeneration
        config.setWakeOnWave(true)

        assertTrue(disabled > initial)
        assertTrue(config.wakeOnWaveGeneration > disabled)
    }

    @Test fun failedBatchCommitDoesNotPartiallyPublishStagedSetters() {
        val prefs = fakePreferences(commitSucceeds = false)
        val config = Config(prefs.instance)

        val committed = config.applyBatch {
            config.setPanelId("new_panel")
            config.setMqtt("tcp://broker:1883", "user", "secret")
        }

        assertFalse(committed)
        assertFalse(prefs.values.containsKey("panel_id"))
        assertFalse(prefs.values.containsKey("mqtt_broker"))
    }

    @Test fun registryStagesOauthExpiryAsLongWithoutTruncation() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        val spec = SettingsRegistry.spec("ha_token_expiry")!!
        val expiry = Int.MAX_VALUE.toLong() + 86_400L
        val editor = config.editor()

        config.stage(editor, spec, expiry.toString())

        assertTrue(editor.commit())
        assertEquals(expiry, config.haTokenExpiry)
        assertEquals(expiry.toString(), config.getRaw(spec))
    }

    @Test fun registryStagesVendorPackageSelectionsInTheExistingConfigOwner() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        val spec = SettingsRegistry.spec("tame_vendor_packages")!!
        val normalized = "com.vendor.one com.vendor.two"
        val editor = config.editor()

        config.stage(editor, spec, normalized)

        assertTrue(editor.commit())
        assertEquals(normalized, config.getRaw(spec))
        assertEquals(normalized, config.tameVendorPackagesRaw)
        assertEquals(listOf("com.vendor.one", "com.vendor.two"), config.tameVendorPackages)
    }

    @Test fun legacyCredentialRestoreClearsUnrelatedTargetExpiry() {
        val prefs = fakePreferences(initial = mapOf(
            "ha_token" to "target-access",
            "ha_refresh_token" to "target-refresh",
            "ha_token_expiry" to 9_999_999_999L,
        ))
        val config = Config(prefs.instance)
        val accepted = mapOf(
            "ha_token" to "restored-access",
            "ha_refresh_token" to "restored-refresh",
        )
        val editor = config.editor()
            .putString("ha_token", accepted.getValue("ha_token"))
            .putString("ha_refresh_token", accepted.getValue("ha_refresh_token"))

        config.stageImportDependencies(editor, accepted)

        assertTrue(editor.commit())
        assertEquals("restored-access", config.haToken)
        assertEquals("restored-refresh", config.haRefreshToken)
        assertEquals(0L, config.haTokenExpiry)
    }

    @Test fun credentialRestorePreservesItsMatchingExplicitExpiryAtomically() {
        val prefs = fakePreferences(initial = mapOf("ha_token_expiry" to 111L))
        val config = Config(prefs.instance)
        val expiry = 2_345_678_901L
        val accepted = mapOf(
            "ha_token" to " restored access ",
            "ha_refresh_token" to " restored refresh ",
            "ha_token_expiry" to expiry.toString(),
        )
        val editor = config.editor()
        accepted.forEach { (key, value) ->
            config.stage(editor, SettingsRegistry.spec(key)!!, value)
        }
        config.stageImportDependencies(editor, accepted)

        assertTrue(editor.commit())
        assertEquals(" restored access ", config.haToken)
        assertEquals(" restored refresh ", config.haRefreshToken)
        assertEquals(expiry, config.haTokenExpiry)
    }

    @Test fun panelIdentityAndDependentLinkInvalidationShareOneCommit() {
        val prefs = fakePreferences(
            initial = mapOf("panel_id" to "old_panel", "ha_device_url" to "http://ha/device/old"),
        )
        val config = Config(prefs.instance)
        val editor = config.editor()

        config.stagePanelId(editor, "new_panel")

        assertEquals("old_panel", prefs.values["panel_id"])
        assertEquals("http://ha/device/old", prefs.values["ha_device_url"])
        assertTrue(editor.commit())
        assertEquals("new_panel", prefs.values["panel_id"])
        assertFalse(prefs.values.containsKey("ha_device_url"))
    }

    @Test fun nativeHaTargetChangeInvalidatesDeviceLinkResolvedOnAnotherServer() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "https://renderer-a.example",
                "ha_device_url" to "https://mqtt-b.example/config/devices/device/stale-id",
                "ha_link_at" to 1234L,
                "ha_link_target" to HaLink.resolutionTarget("https://mqtt-b.example", "panel-id"),
            ),
        )
        val config = Config(prefs.instance)

        config.setHaConnection("https://renderer-c.example/", "native-token")

        assertEquals("https://renderer-c.example", prefs.values["ha_url"])
        assertFalse("a device id belongs to one HA instance", prefs.values.containsKey("ha_device_url"))
        assertFalse("the replacement target must resolve immediately", prefs.values.containsKey("ha_link_at"))
        assertFalse("the stale cache owner must be discarded", prefs.values.containsKey("ha_link_target"))
    }

    @Test fun equivalentNativeHaUrlPreservesResolvedDeviceLink() {
        val existing = "https://ha.example/config/devices/device/current-id"
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "https://ha.example",
                "ha_device_url" to existing,
                "ha_link_at" to 1234L,
                "ha_link_target" to HaLink.resolutionTarget("https://ha.example", "panel-id"),
            ),
        )
        val config = Config(prefs.instance)

        config.setHaConnection("https://ha.example/", "replacement-token")

        assertEquals(existing, prefs.values["ha_device_url"])
        assertEquals(1234L, prefs.values["ha_link_at"])
        assertEquals(HaLink.resolutionTarget("https://ha.example", "panel-id"), prefs.values["ha_link_target"])
    }

    @Test fun openInHaNeverExposesDeviceIdOwnedByAnotherNativeServer() {
        val panel = "wall-panel"
        val native = "https://native-a.example/ha"
        val staleDevice = "https://mqtt-b.example/config/devices/device/stale-id"
        val prefs = fakePreferences(
            initial = mapOf(
                "panel_id" to panel,
                "ha_url" to native,
                "ha_base_url" to "https://companion-c.example",
                "ha_device_url" to staleDevice,
                "ha_link_target" to HaLink.resolutionTarget("https://mqtt-b.example", panel),
                "ha_link_at" to 1234L,
            ),
        )
        val config = Config(prefs.instance)

        assertEquals("the native renderer target is safe while its device id resolves", native, config.haLinkUrl)
        assertFalse(config.haDeviceLinkIsFresh(
            HaLink.resolutionTarget(native, panel),
            nowMs = 2000L,
            ttlMs = 10_000L,
        ))
    }

    @Test fun openInHaUsesDeviceIdOnlyWhenItsNativeTargetOwnsIt() {
        val panel = "wall-panel"
        val native = "https://native-a.example/ha"
        val device = "$native/config/devices/device/current-id"
        val prefs = fakePreferences(
            initial = mapOf(
                "panel_id" to panel,
                "ha_url" to native,
                "ha_device_url" to device,
                "ha_link_target" to HaLink.resolutionTarget(native, panel),
                "ha_link_at" to 1234L,
            ),
        )
        val config = Config(prefs.instance)

        assertEquals(device, config.haLinkUrl)
        assertTrue(config.haDeviceLinkIsFresh(
            HaLink.resolutionTarget("$native/", panel),
            nowMs = 2000L,
            ttlMs = 10_000L,
        ))
    }

    @Test fun legacyDeviceLinkWithoutTargetOwnershipIsNeverFresh() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_device_url" to "https://old.example/config/devices/device/legacy-id",
                "ha_link_at" to 1999L,
            ),
        )
        val config = Config(prefs.instance)

        assertFalse(config.haDeviceLinkIsFresh(
            HaLink.resolutionTarget("https://old.example", "panel"),
            nowMs = 2000L,
            ttlMs = 10_000L,
        ))
    }

    @Test fun importClearingCredentialIdentitiesClearsDependentSecretsInTheSameCommit() {
        val prefs = fakePreferences(
            initial = mapOf(
                "mqtt_user" to "old-user", "mqtt_password" to "old-password",
                "ha_url" to "http://ha:8123", "ha_token" to "old-access",
                "ha_refresh_token" to "old-refresh", "ha_client_id" to "old-client",
                "ha_token_expiry" to 1234L,
            ),
        )
        val config = Config(prefs.instance)
        val editor = config.editor()
        editor.putString("mqtt_user", "").putString("ha_url", "")

        config.stageImportDependencies(editor, mapOf("mqtt_user" to "", "ha_url" to ""))

        assertEquals("old-password", prefs.values["mqtt_password"])
        assertTrue(editor.commit())
        assertEquals("", prefs.values["mqtt_password"])
        assertEquals("", prefs.values["ha_token"])
        assertEquals("", prefs.values["ha_refresh_token"])
        assertEquals("", prefs.values["ha_client_id"])
        assertEquals(0L, prefs.values["ha_token_expiry"])
    }

    @Test fun importReplacementAccessTokenSupersedesAnUnreplacedRefreshSession() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha:8123", "ha_token" to "old-access",
                "ha_refresh_token" to "old-refresh", "ha_token_expiry" to 1234L,
            ),
        )
        val config = Config(prefs.instance)
        val editor = config.editor().putString("ha_token", "new-access")

        config.stageImportDependencies(editor, mapOf("ha_token" to "new-access"))

        assertTrue(editor.commit())
        assertEquals("new-access", prefs.values["ha_token"])
        assertEquals("", prefs.values["ha_refresh_token"])
        assertEquals(0L, prefs.values["ha_token_expiry"])
    }

    @Test fun importExplicitAccessTokenReassertionStillDropsTheOldRefreshSession() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_token" to "same-access", "ha_refresh_token" to "old-refresh",
                "ha_token_expiry" to 1234L,
            ),
        )
        val config = Config(prefs.instance)
        val editor = config.editor().putString("ha_token", "same-access")

        config.stageImportDependencies(editor, mapOf("ha_token" to "same-access"))

        assertTrue(editor.commit())
        assertEquals("", prefs.values["ha_refresh_token"])
        assertEquals(0L, prefs.values["ha_token_expiry"])
    }

    @Test fun importNewRefreshSessionAndHaUrlKeepTheirCanonicalValues() {
        val prefs = fakePreferences(
            initial = mapOf("ha_token" to "old-access", "ha_refresh_token" to "old-refresh"),
        )
        val config = Config(prefs.instance)
        val editor = config.editor()
            .putString("ha_url", "http://ha:8123/")
            .putString("ha_token", "new-access")
            .putString("ha_refresh_token", "new-refresh")

        config.stageImportDependencies(
            editor,
            mapOf(
                "ha_url" to "http://ha:8123/",
                "ha_token" to "new-access",
                "ha_refresh_token" to "new-refresh",
            ),
        )

        assertTrue(editor.commit())
        assertEquals("http://ha:8123", prefs.values["ha_url"])
        assertEquals("new-access", prefs.values["ha_token"])
        assertEquals("new-refresh", prefs.values["ha_refresh_token"])
    }

    @Test fun borrowedRendererConnectionAndZoomPublishInOneCommit() {
        val prefs = fakePreferences(initial = mapOf("dashboard_zoom" to 100))
        val config = Config(prefs.instance)

        assertTrue(config.setBorrowedRendererSettings(
            url = "http://ha:8123/",
            accessToken = "access",
            refreshToken = "refresh",
            tokenExpiry = 1234L,
            clientId = "client",
            zoom = 125,
        ))

        assertEquals("http://ha:8123", prefs.values["ha_url"])
        assertEquals("access", prefs.values["ha_token"])
        assertEquals("refresh", prefs.values["ha_refresh_token"])
        assertEquals(1234L, prefs.values["ha_token_expiry"])
        assertEquals("client", prefs.values["ha_client_id"])
        assertEquals(125, prefs.values["dashboard_zoom"])
        assertEquals(true, prefs.values["renderer_launch_pending"])
    }

    @Test fun failedBorrowedRendererCommitPublishesNothing() {
        val prefs = fakePreferences(
            initial = mapOf("dashboard_zoom" to 100),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertFalse(config.setBorrowedRendererSettings(
            url = "http://ha:8123",
            accessToken = "access",
            refreshToken = "refresh",
            tokenExpiry = 1234L,
            clientId = "client",
            zoom = 125,
        ))

        assertFalse(prefs.values.containsKey("ha_url"))
        assertFalse(prefs.values.containsKey("ha_token"))
        assertFalse(prefs.values.containsKey("ha_refresh_token"))
        assertEquals(100, prefs.values["dashboard_zoom"])
        assertFalse(prefs.values.containsKey("renderer_launch_pending"))
    }

    @Test fun rendererSwitchAndLaunchHandoffAreDurable() {
        val prefs = fakePreferences(initial = mapOf("dashboard_package" to "foreign.renderer"))
        val config = Config(prefs.instance)

        assertTrue(config.applyBatch {
            config.setDashboardPackage("builtin")
            assertFalse(config.rendererLaunchPending)
        })
        assertEquals("builtin", config.dashboardPackage)
        assertTrue(config.rendererLaunchPending)

        assertTrue(config.completeRendererLaunch())
        assertFalse(config.rendererLaunchPending)
    }

    @Test fun importedRendererSwitchStagesLaunchHandoffInTheSameCommit() {
        val prefs = fakePreferences(initial = mapOf("dashboard_package" to "foreign.renderer"))
        val config = Config(prefs.instance)
        val editor = config.editor().putString("dashboard_package", "builtin")

        config.stageImportDependencies(editor, mapOf("dashboard_package" to "builtin"))

        assertTrue(editor.commit())
        assertEquals("builtin", config.dashboardPackage)
        assertTrue(config.rendererLaunchPending)
    }

    @Test fun entityFilterListAndEnableFlagPublishAtomically() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertTrue(config.setDashboardEntityFilter(true, listOf("sensor.b", "light.a", "sensor.b")))

        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.a", "sensor.b"), config.dashboardEntityFilterIds)
        assertEquals("light.a\nsensor.b", prefs.values["dashboard_entity_filter_ids"])
    }

    @Test fun failedEntityFilterCommitPublishesNeitherListNorFlag() {
        val prefs = fakePreferences(commitSucceeds = false)
        val config = Config(prefs.instance)

        assertFalse(config.setDashboardEntityFilter(true, listOf("sensor.one")))

        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
    }

    @Test fun firstIdentityBindingPreservesLegacyStateForTheConfiguredDashboard() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.office\nsensor.temperature",
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_overrides" to "+person.lise",
            ),
        )
        val config = Config(prefs.instance)

        assertEquals(
            "url-key",
            config.prepareDashboardEntityInstance("http://HA.local:8123/", "/lovelace/kiosk", "url-key"),
        )

        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.office", "sensor.temperature"), config.dashboardEntityFilterIds)
        assertTrue(config.dashboardEntityLearningApplied)
        assertEquals(mapOf("person.lise" to "pinned"), config.dashboardEntityOverrides)
        assertEquals(dashboardEntityTargetKey("url-key", "/lovelace/kiosk"), config.dashboardEntityTargetKey)
    }

    @Test fun reassertingTheLegacyDashboardBeforeMigrationStillPreservesItsList() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.office",
            ),
        )
        val config = Config(prefs.instance)

        config.setHomeDashboard("/lovelace/kiosk/")
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))

        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.office"), config.dashboardEntityFilterIds)
    }

    @Test fun endpointChangeBeforeFirstMigrationHidesUnownedLegacyState() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://old-ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.old",
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_overrides" to "+person.old",
            ),
        )
        val config = Config(prefs.instance)

        config.setHaConnection("http://new-ha.local:8123", null)

        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
        assertFalse(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityOverrides.isEmpty())
        assertEquals("url-new", config.prepareDashboardEntityInstance(
            "http://new-ha.local:8123", "/lovelace/kiosk", "url-new",
        ))
        assertFalse(config.dashboardEntityFilterEnabled)
    }

    @Test fun verifiedUuidPreservesStateAcrossUrlAliasesButNotBeforeAdoption() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.office",
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_overrides" to "+person.lise",
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-a", config.prepareDashboardEntityInstance("http://ha.local:8123", "/lovelace/kiosk", "url-a"))
        assertTrue(config.adoptDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "00112233445566778899aabbccddeeff", "url-a", "uuid-key",
        ))

        config.setHaConnection("https://ha.example.net", null)
        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
        assertFalse(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityOverrides.isEmpty())

        assertEquals("url-alias", config.prepareDashboardEntityInstance(
            "https://ha.example.net", "/lovelace/kiosk", "url-alias",
        ))
        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.adoptDashboardEntityInstance(
            "https://ha.example.net", "/lovelace/kiosk", "00112233445566778899aabbccddeeff", "url-alias", "uuid-key",
        ))
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.office"), config.dashboardEntityFilterIds)
        assertTrue(config.dashboardEntityLearningApplied)
        assertEquals(mapOf("person.lise" to "pinned"), config.dashboardEntityOverrides)
    }

    @Test fun dashboardChangeImmediatelyHidesStateEvenOnTheSameVerifiedInstance() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/office",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.office",
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_overrides" to "+person.lise",
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/office", "url-key",
        ))
        assertTrue(config.adoptDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/office", "00112233445566778899aabbccddeeff", "url-key", "uuid-key",
        ))

        config.setHomeDashboard("/lovelace/bedroom")

        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
        assertFalse(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityOverrides.isEmpty())
        assertEquals("uuid-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/bedroom", "url-key",
        ))
        assertFalse(config.dashboardEntityFilterEnabled)
    }

    @Test fun differentHaInstanceNeverInheritsThePreviousTargetState() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha-a.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.office",
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_overrides" to "+person.lise",
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-a", config.prepareDashboardEntityInstance(
            "http://ha-a.local:8123", "/lovelace/kiosk", "url-a",
        ))
        assertTrue(config.adoptDashboardEntityInstance(
            "http://ha-a.local:8123", "/lovelace/kiosk", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "url-a", "uuid-a",
        ))

        config.setHaConnection("http://ha-b.local:8123", null)
        assertEquals("url-b", config.prepareDashboardEntityInstance(
            "http://ha-b.local:8123", "/lovelace/kiosk", "url-b",
        ))
        assertTrue(config.adoptDashboardEntityInstance(
            "http://ha-b.local:8123", "/lovelace/kiosk", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "url-b", "uuid-b",
        ))

        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
        assertFalse(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityOverrides.isEmpty())
    }

    @Test fun failedIdentityPreferenceCommitPublishesNoPartialSelection() {
        val prefs = fakePreferences(
            initial = mapOf("ha_url" to "http://ha.local:8123", "home_dashboard" to "/lovelace/kiosk"),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertEquals(null, config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))
        assertEquals("", config.dashboardEntityInstanceKey)
        assertEquals("", config.dashboardEntityTargetKey)
        assertFalse(prefs.values.containsKey("dashboard_entity_instance_origin"))
        assertFalse(prefs.values.containsKey("dashboard_entity_dashboard_path"))
    }

    @Test fun staleUuidAdoptionCannotCrossAConcurrentDashboardChange() {
        val prefs = fakePreferences(
            initial = mapOf("ha_url" to "http://ha.local:8123", "home_dashboard" to "/lovelace/office"),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/office", "url-key",
        ))

        config.setHomeDashboard("/lovelace/bedroom")

        assertFalse(config.adoptDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/office", "00112233445566778899aabbccddeeff", "url-key", "uuid-key",
        ))
        assertEquals("url-key", config.dashboardEntityInstanceKey)
        assertEquals("", config.dashboardEntityInstanceUuid)
    }

    @Test fun manualFilterCutoverPublishesModeLatchAndListInOneCommit() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_learning" to true,
                "dashboard_entity_learning_applied" to true,
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))

        assertTrue(config.commitDashboardManualEntityFilter(
            enabled = true, entityIds = listOf("sensor.b", "light.a", "sensor.b"),
        ))

        assertFalse(config.dashboardEntityLearningEnabled)
        assertFalse(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.a", "sensor.b"), config.dashboardEntityFilterIds)
    }

    @Test fun invalidManualFilterCutoverDoesNotDisableLearning() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_learning" to true,
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))

        assertFalse(config.commitDashboardManualEntityFilter(enabled = true, entityIds = listOf(" ")))

        assertTrue(config.dashboardEntityLearningEnabled)
        assertFalse(prefs.values.containsKey("dashboard_entity_filter_enabled"))
    }

    @Test fun disablingLearningAfterTargetSwitchDoesNotClaimPreviousAllowList() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/office",
                "dashboard_entity_learning" to true,
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/office", "url-key",
        ))
        assertTrue(config.setDashboardEntityFilter(true, listOf("light.office")))

        config.setHomeDashboard("/lovelace/bedroom")
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/bedroom", "url-key",
        ))
        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())

        assertTrue(config.commitDashboardEntityLearningMode(enabled = false, clearApplied = true))

        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
        assertEquals("light.office", prefs.values["dashboard_entity_filter_ids"])

        config.setHomeDashboard("/lovelace/office")
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/office", "url-key",
        ))
        assertEquals(listOf("light.office"), config.dashboardEntityFilterIds)
        assertFalse("disabled learning must not reactivate the old target's interceptor", config.dashboardEntityFilterEnabled)
    }

    @Test fun failedManualFilterCutoverPublishesNothing() {
        val target = dashboardEntityTargetKey("url-key", "/lovelace/kiosk")
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/lovelace/kiosk",
                "dashboard_entity_filter_instance" to target,
                "dashboard_entity_applied_instance" to target,
                "dashboard_entity_learning" to true,
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.old",
            ),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertFalse(config.commitDashboardManualEntityFilter(true, listOf("light.new")))

        assertTrue(config.dashboardEntityLearningEnabled)
        assertTrue(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.old"), config.dashboardEntityFilterIds)
    }

    @Test fun activationPublishesFilterAndAppliedLatchAtomically() {
        val prefs = fakePreferences(
            initial = mapOf("ha_url" to "http://ha.local:8123", "home_dashboard" to "/lovelace/kiosk"),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))

        assertTrue(config.commitDashboardEntitySubscription(true, listOf("sensor.b", "light.a"), applied = true))

        assertTrue(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.a", "sensor.b"), config.dashboardEntityFilterIds)
    }

    @Test fun failedActivationCommitPublishesNeitherFilterNorAppliedLatch() {
        val target = dashboardEntityTargetKey("url-key", "/lovelace/kiosk")
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/lovelace/kiosk",
                "dashboard_entity_filter_instance" to target,
                "dashboard_entity_applied_instance" to target,
                "dashboard_entity_filter_ids" to "light.old",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_learning_applied" to false,
            ),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertFalse(config.commitDashboardEntitySubscription(true, listOf("light.new"), applied = true))

        assertFalse(config.dashboardEntityLearningApplied)
        assertEquals(listOf("light.old"), config.dashboardEntityFilterIds)
    }

    @Test fun cleanSlateEvidenceResetAtomicallyClearsFilterWithoutDisablingLearning() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_learning" to true,
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))
        assertTrue(config.commitDashboardEntitySubscription(true, listOf("light.old"), applied = true))
        assertTrue(config.setDashboardEntityOverrides(mapOf("light.old" to "pinned")))

        assertTrue(config.commitDashboardEntityEvidenceReset(clearFilter = true))

        assertTrue(config.dashboardEntityLearningEnabled)
        assertFalse(config.dashboardEntityLearningApplied)
        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
        assertTrue(config.dashboardEntityOverrides.isEmpty())
    }

    @Test fun initialEntityActivationLatchSurvivesUntilSuccessButResetCannotRecreateIt() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_learning" to false,
            ),
        )
        val config = Config(prefs.instance)

        assertTrue(config.commitDashboardEntityLearningMode(enabled = true, clearApplied = true))
        assertTrue(config.dashboardEntityInitialActivationPending)
        assertTrue(config.commitDashboardEntityLearningMode(enabled = false, clearApplied = true))
        assertTrue("ordinary disable must preserve unfinished first activation", config.dashboardEntityInitialActivationPending)
        assertTrue(config.commitDashboardEntityLearningMode(enabled = true, clearApplied = true))
        assertTrue(config.dashboardEntityInitialActivationPending)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))

        assertTrue(config.commitDashboardEntityEvidenceReset(clearFilter = true))
        assertFalse(config.dashboardEntityInitialActivationPending)
        assertTrue(config.commitDashboardEntityLearningMode(enabled = false, clearApplied = true))
        assertTrue(config.commitDashboardEntityLearningMode(enabled = true, clearApplied = true))
        assertFalse("reset history must prevent first-run defaults from returning", config.dashboardEntityInitialActivationPending)

        assertTrue(config.commitDashboardEntitySubscription(true, emptyList(), applied = true))
        assertFalse(config.dashboardEntityInitialActivationPending)
    }

    @Test fun defaultEvidenceResetPreservesKnownGoodFilter() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_learning" to true,
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))
        assertTrue(config.commitDashboardEntitySubscription(true, listOf("light.old"), applied = true))

        assertTrue(config.commitDashboardEntityEvidenceReset(clearFilter = false))

        assertTrue(config.dashboardEntityLearningEnabled)
        assertFalse(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.old"), config.dashboardEntityFilterIds)
    }

    @Test fun failedCleanSlateEvidenceResetPublishesNoPartialState() {
        val target = dashboardEntityTargetKey("url-key", "/lovelace/kiosk")
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/lovelace/kiosk",
                "dashboard_entity_filter_instance" to target,
                "dashboard_entity_applied_instance" to target,
                "dashboard_entity_override_instance" to target,
                "dashboard_entity_learning" to true,
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.old",
                "dashboard_entity_overrides" to "+light.old",
            ),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertFalse(config.commitDashboardEntityEvidenceReset(clearFilter = true))

        assertTrue(config.dashboardEntityLearningEnabled)
        assertTrue(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.old"), config.dashboardEntityFilterIds)
        assertEquals(mapOf("light.old" to "pinned"), config.dashboardEntityOverrides)
    }

    @Test fun failedStoreResetCanRestoreTheExactOwnerScopedEvidencePreferences() {
        val target = dashboardEntityTargetKey("url-key", "/lovelace/kiosk")
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/lovelace/kiosk",
                "dashboard_entity_filter_instance" to target,
                "dashboard_entity_applied_instance" to target,
                "dashboard_entity_override_instance" to target,
                "dashboard_entity_learning" to true,
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "sensor.b\nlight.a",
                "dashboard_entity_overrides" to "+sensor.b\n-light.a",
            ),
        )
        val config = Config(prefs.instance)
        val original = config.dashboardEntityBackupState()

        assertTrue(config.commitDashboardEntityEvidenceReset(clearFilter = true))
        assertTrue(config.restoreDashboardEntityEvidencePreferences(original))

        assertEquals(original, config.dashboardEntityBackupState())
        assertTrue(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.a", "sensor.b"), config.dashboardEntityFilterIds)
        assertEquals(
            mapOf("light.a" to "forced_exclude", "sensor.b" to "pinned"),
            config.dashboardEntityOverrides,
        )
    }

    @Test fun disabledCleanSlateResetClearsEvidencePreferencesWithoutEnablingLearning() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_learning" to false,
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))
        assertTrue(config.commitDashboardEntitySubscription(true, listOf("light.old"), applied = true))
        assertTrue(config.setDashboardEntityOverrides(mapOf("light.old" to "pinned")))

        assertTrue(config.commitDashboardEntityEvidenceReset(clearFilter = true))

        assertFalse(config.dashboardEntityLearningEnabled)
        assertFalse(config.dashboardEntityLearningApplied)
        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
        assertTrue(config.dashboardEntityOverrides.isEmpty())
    }

    @Test fun promotionPolicyAndResultingSubscriptionPublishInOneCommit() {
        val prefs = fakePreferences(
            initial = mapOf("ha_url" to "http://ha.local:8123", "home_dashboard" to "/lovelace/kiosk"),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))

        assertTrue(config.commitDashboardEntityPromotionPolicy(
            staticRefs = false,
            runtimeRefs = true,
            activeEntityIds = listOf("sensor.runtime"),
            applied = true,
        ))

        assertFalse(config.dashboardEntityAutoStatic)
        assertTrue(config.dashboardEntityAutoRuntime)
        assertTrue(config.dashboardEntityLearningApplied)
        assertEquals(listOf("sensor.runtime"), config.dashboardEntityFilterIds)
    }

    @Test fun failedPromotionPolicyCommitLeavesPolicyAndSubscriptionUntouched() {
        val target = dashboardEntityTargetKey("url-key", "/lovelace/kiosk")
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/lovelace/kiosk",
                "dashboard_entity_filter_instance" to target,
                "dashboard_entity_applied_instance" to target,
                "dashboard_entity_auto_static" to true,
                "dashboard_entity_auto_runtime" to false,
                "dashboard_entity_filter_ids" to "light.old",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_learning_applied" to true,
            ),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertFalse(config.commitDashboardEntityPromotionPolicy(
            staticRefs = false,
            runtimeRefs = true,
            activeEntityIds = listOf("light.new"),
            applied = true,
        ))

        assertTrue(config.dashboardEntityAutoStatic)
        assertFalse(config.dashboardEntityAutoRuntime)
        assertTrue(config.dashboardEntityLearningApplied)
        assertEquals(listOf("light.old"), config.dashboardEntityFilterIds)
    }

    @Test fun credentialGroupsAreReadFromOneImmutablePreferenceSnapshot() {
        val prefs = fakePreferences(initial = mapOf(
            "mqtt_broker" to "ssl://broker.local:8883",
            "mqtt_user" to "panel",
            "mqtt_password" to "secret",
            "ha_url" to "https://ha.local",
            "ha_token" to "access",
            "ha_refresh_token" to "refresh",
            "ha_token_expiry" to 1234L,
            "ha_client_id" to "client",
        ))
        val config = Config(prefs.instance)

        assertEquals(
            MqttCredentialsSnapshot("ssl://broker.local:8883", "panel", "secret"),
            config.mqttCredentialsSnapshot(),
        )
        assertEquals(
            HaAuthSnapshot("https://ha.local", "access", "refresh", 1234L, "client"),
            config.haAuthSnapshot(),
        )
    }

    @Test fun refreshedHaTokenCommitsOnlyWhileTheBorrowedAuthGenerationIsStillOwned() {
        val prefs = fakePreferences(initial = mapOf(
            "ha_url" to "https://ha.local",
            "ha_token" to "old-access",
            "ha_refresh_token" to "refresh",
            "ha_token_expiry" to 1234L,
            "ha_client_id" to "client",
        ))
        val config = Config(prefs.instance)
        val borrowed = config.haAuthSnapshot()

        assertTrue(config.setHaRefreshedTokenIfOwned(borrowed, "new-access", 5678L))
        assertEquals("new-access", prefs.values["ha_token"])
        assertEquals(5678L, prefs.values["ha_token_expiry"])

        config.setHaConnection("https://other.local", "admin-access")
        assertFalse(config.setHaRefreshedTokenIfOwned(borrowed, "late-access", 9999L))
        assertEquals("admin-access", prefs.values["ha_token"])
        assertEquals("https://other.local", prefs.values["ha_url"])
        assertEquals(5678L, prefs.values["ha_token_expiry"])
    }

    @Test fun newestBrowserLoginSupersedesAnAlreadyClaimedAttempt() {
        val config = Config(fakePreferences(initial = mapOf("ha_url" to "https://ha.local")).instance)

        val first = config.beginHaOAuthAttempt()
        assertTrue(config.isHaOAuthAttemptCurrent(first.epoch))
        val second = config.beginHaOAuthAttempt()

        assertFalse(config.isHaOAuthAttemptCurrent(first.epoch))
        assertTrue(config.isHaOAuthAttemptCurrent(second.epoch))
        assertEquals(first.owner, second.owner)
    }

    @Test fun separateConfigWrappersShareOneProcessWideTransactionLock() {
        val prefs = fakePreferences(initial = mapOf("ha_url" to "https://ha.local"))
        val first = Config(prefs.instance)
        val second = Config(prefs.instance)
        val writerStarted = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        lateinit var writer: Thread

        first.synchronizedTransaction {
            writer = Thread {
                writerStarted.countDown()
                second.setHaConnection("https://other.local", "new-access")
                writerFinished.countDown()
            }.also(Thread::start)
            assertTrue(writerStarted.await(2, TimeUnit.SECONDS))
            assertFalse("a second wrapper must not enter during the transaction", writerFinished.await(100, TimeUnit.MILLISECONDS))
            assertEquals("https://ha.local", first.haAuthSnapshot().url)
        }

        assertTrue(writerFinished.await(2, TimeUnit.SECONDS))
        writer.join(2_000)
        assertEquals("https://other.local", first.haAuthSnapshot().url)
    }

    @Test fun ownerScopedEntityBackupStateRoundTripsInOneEditorCommit() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        val state = DashboardEntityBackupState(
            instanceKey = "uuid-key",
            instanceOrigin = "https://ha.local",
            instanceUuid = "core-uuid",
            dashboardPath = "/lovelace/wall",
            filterIds = "light.one\nsensor.two",
            filterEnabled = true,
            filterOwner = "uuid-key|/lovelace/wall",
            learningApplied = true,
            appliedOwner = "uuid-key|/lovelace/wall",
            overrides = "+light.one\n-sensor.two",
            overrideOwner = "uuid-key|/lovelace/wall",
            initialActivationPending = true,
        )
        val editor = config.editor()
        config.stageDashboardEntityBackupState(editor, state)
        assertTrue(editor.commit())

        assertEquals(state, config.dashboardEntityBackupState())
    }

    @Test fun rawRegistryCommitValidatesAndDurablyPersistsOneValue() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        val zoom = requireNotNull(SettingsRegistry.spec("dashboard_zoom"))

        assertTrue(config.commitRaw(zoom, "125"))
        assertEquals(125, prefs.values["dashboard_zoom"])
        assertFalse(config.commitRaw(zoom, "not-a-number"))
        assertEquals(125, prefs.values["dashboard_zoom"])
    }

    @Test fun rawWakeOnWaveCommitInvalidatesAlreadyAdmittedGestureWork() {
        val config = Config(fakePreferences().instance)
        val wakeOnWave = requireNotNull(SettingsRegistry.spec("wake_on_wave"))
        val initial = config.wakeOnWaveGeneration

        assertTrue(config.commitRaw(wakeOnWave, "false"))

        assertFalse(config.wakeOnWave)
        assertTrue(config.wakeOnWaveGeneration > initial)
    }

    @Test fun rawHomeDashboardCommitPreservesItsOwnerScopedPathDependency() {
        val prefs = fakePreferences(
            mutableMapOf(
                "home_dashboard" to "/lovelace/old",
                "dashboard_entity_dashboard_path" to "/lovelace/old",
            ),
        )
        val config = Config(prefs.instance)
        val home = requireNotNull(SettingsRegistry.spec("home_dashboard"))

        assertTrue(config.commitRaw(home, "/lovelace/new"))

        assertEquals("/lovelace/new", prefs.values["home_dashboard"])
        assertEquals("/lovelace/new", prefs.values["dashboard_entity_dashboard_path"])
    }

    @Test fun mqttFamilyPreferenceIsOneBrokerScopedAtomicTuple() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertNull(config.mqttFamilyPreference("tcp://broker-a:1883"))
        assertTrue(config.rememberMqttFamilyPreference("tcp://broker-a:1883", preferIpv4 = true))
        assertEquals(true, config.mqttFamilyPreference("tcp://broker-a:1883"))
        assertNull(config.mqttFamilyPreference("tcp://broker-b:1883"))

        assertTrue(config.rememberMqttFamilyPreference("tcp://broker-b:1883", preferIpv4 = false))
        assertEquals(false, config.mqttFamilyPreference("tcp://broker-b:1883"))
        assertNull(config.mqttFamilyPreference("tcp://broker-a:1883"))
        assertEquals("tcp://broker-b:1883", prefs.values["device_local_mqtt_family_broker"])
        assertEquals(false, prefs.values["device_local_mqtt_family_ipv4"])

        assertTrue(config.forgetMqttFamilyPreference())
        assertNull(config.mqttFamilyPreference("tcp://broker-b:1883"))
        assertFalse("device_local_mqtt_family_broker" in prefs.values)
        assertFalse("device_local_mqtt_family_ipv4" in prefs.values)
    }

    @Test fun mqttFamilyPreferenceDoesNotClaimFailedCommit() {
        val prefs = fakePreferences(
            initial = mapOf(
                "device_local_mqtt_family_broker" to "tcp://broker-old:1883",
                "device_local_mqtt_family_ipv4" to true,
            ),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertFalse(config.rememberMqttFamilyPreference("tcp://broker-a:1883", preferIpv4 = true))
        assertNull(config.mqttFamilyPreference("tcp://broker-a:1883"))
        assertFalse(config.forgetMqttFamilyPreference())
        assertEquals("tcp://broker-old:1883", prefs.values["device_local_mqtt_family_broker"])
    }

    @Test fun mqttAnnouncementBoundaryIsOneDurableConfigurationScopedToken() {
        val prefs = fakePreferences()
        val firstProcess = Config(prefs.instance)

        assertTrue(firstProcess.mqttAnnouncementBoundaryAvailable("identity-a"))
        assertTrue(firstProcess.consumeMqttAnnouncementBoundary("identity-a"))
        assertFalse(firstProcess.mqttAnnouncementBoundaryAvailable("identity-a"))
        assertFalse(firstProcess.consumeMqttAnnouncementBoundary("identity-a"))

        // Recreating Config models the process boundary: CONNACK/heartbeat have no API that clears it.
        val replacementProcess = Config(prefs.instance)
        assertFalse(replacementProcess.mqttAnnouncementBoundaryAvailable("identity-a"))

        // Relevant MQTT identity change opens a new one-shot; exact readiness clears that identity only.
        assertTrue(replacementProcess.mqttAnnouncementBoundaryAvailable("identity-b"))
        assertTrue(replacementProcess.consumeMqttAnnouncementBoundary("identity-b"))
        assertTrue(replacementProcess.clearMqttAnnouncementBoundary("identity-b"))
        assertTrue(replacementProcess.mqttAnnouncementBoundaryAvailable("identity-b"))
    }

    @Test fun mqttAnnouncementBoundaryFailsClosedWhenDurabilityCannotBeProved() {
        val prefs = fakePreferences(commitSucceeds = false)
        val config = Config(prefs.instance)

        assertFalse(config.consumeMqttAnnouncementBoundary("identity-a"))
        assertTrue(config.mqttAnnouncementBoundaryAvailable("identity-a"))
    }

    @Test fun mqttAnnouncementBoundaryRemainsConsumedWhenReadinessClearIsNotDurable() {
        val prefs = fakePreferences(
            initial = mapOf("device_local_mqtt_announcement_boundary_consumed" to "identity-a"),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertFalse(config.clearMqttAnnouncementBoundary("identity-a"))
        assertFalse(config.mqttAnnouncementBoundaryAvailable("identity-a"))
    }

    @Test fun mqttLateReadinessCannotRearmACommittedPendingProcessBoundary() {
        val prefs = fakePreferences()
        val runningProcess = Config(prefs.instance)
        val identity = "identity-a"

        assertTrue(runningProcess.consumeMqttAnnouncementBoundary(identity))
        assertEquals(
            MqttAnnouncementReadinessBudgetResult.PRESERVED_PENDING_BOUNDARY,
            reconcileMqttAnnouncementReadinessBudget(consumedHere = true) {
                runningProcess.clearMqttAnnouncementBoundary(identity)
            },
        )

        // The scheduled restart may be delayed after readiness. Its replacement must still inherit an
        // exhausted token, so another announcement wedge cannot cross another process boundary.
        val delayedRestart = Config(prefs.instance)
        assertFalse(delayedRestart.mqttAnnouncementBoundaryAvailable(identity))

        // Only exact readiness in a genuinely recreated process may clear the inherited spent token.
        assertEquals(
            MqttAnnouncementReadinessBudgetResult.REARMED,
            reconcileMqttAnnouncementReadinessBudget(consumedHere = false) {
                delayedRestart.clearMqttAnnouncementBoundary(identity)
            },
        )
        assertTrue(delayedRestart.mqttAnnouncementBoundaryAvailable(identity))
    }

    @Test fun completedInstallGetsTheVersionedHomeDashboardMigrationAfterV1WasConsumed() {
        val prefs = fakePreferences(
            initial = mapOf(
                "device_local_setup_questions_migrated_v1" to true,
                "device_local_setup_identity_confirmed" to true,
                "device_local_setup_ever_completed" to true,
            ),
        )
        val config = Config(prefs.instance)

        config.migrateSetupQuestionsForExistingInstall()

        assertTrue(config.setupHomeDashboardChosen)
        assertEquals(true, prefs.values["device_local_setup_home_dashboard_migrated_v2"])
    }

    @Test fun freshGuidedSetupKeepsTheHomeDashboardQuestionOpen() {
        val prefs = fakePreferences(
            initial = mapOf(
                "device_local_setup_questions_migrated_v1" to true,
                "device_local_setup_identity_confirmed" to true,
            ),
        )
        val config = Config(prefs.instance)

        config.migrateSetupQuestionsForExistingInstall()

        assertFalse(config.setupHomeDashboardChosen)
        assertEquals(true, prefs.values["device_local_setup_home_dashboard_migrated_v2"])
    }

    @Test fun freshConfigTakesTcpDefaultWithoutMaterialisingAnOldTransport() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        config.migrateLogShipTcpDefault()

        assertEquals("syslog-tcp", config.logShipProtocol)
        assertFalse(prefs.values.containsKey("log_ship_protocol"))
        assertEquals(true, prefs.values["device_local_log_ship_tcp_default_migrated_v1"])
    }

    @Test fun upgradedAbsentProtocolKeepsItsPreviousEffectiveUdpTransport() {
        val prefs = fakePreferences(initial = mapOf("panel_id" to "existing-panel"))
        val config = Config(prefs.instance)

        config.migrateLogShipTcpDefault()
        config.migrateLogShipTcpDefault()

        assertEquals("syslog-udp", config.logShipProtocol)
        assertEquals("syslog-udp", prefs.values["log_ship_protocol"])
    }

    @Test fun migrationNeverRewritesExplicitLogShipProtocols() {
        listOf("syslog-udp", "syslog-tcp", "http", "syslog").forEach { protocol ->
            val prefs = fakePreferences(initial = mapOf("panel_id" to "existing", "log_ship_protocol" to protocol))
            val config = Config(prefs.instance)

            config.migrateLogShipTcpDefault()

            assertEquals(protocol, prefs.values["log_ship_protocol"])
            assertEquals(if (protocol == "syslog") "syslog-tcp" else protocol, config.logShipProtocol)
        }
    }

    @Test fun failedDefaultMigrationKeepsUdpInMemoryThenRetriesDurablyAndIdempotently() {
        val prefs = fakePreferences(initial = mapOf("panel_id" to "existing"), commitSucceeds = false)
        val config = Config(prefs.instance)

        config.migrateLogShipTcpDefault()

        assertFalse(prefs.values.containsKey("device_local_log_ship_tcp_default_migrated_v1"))
        assertFalse(prefs.values.containsKey("log_ship_protocol"))
        assertEquals("syslog-udp", config.logShipProtocol)

        prefs.commitsSucceed.set(true)
        config.migrateLogShipTcpDefault()
        assertEquals(true, prefs.values["device_local_log_ship_tcp_default_migrated_v1"])
        assertEquals("syslog-udp", prefs.values["log_ship_protocol"])
        assertEquals("syslog-udp", config.logShipProtocol)

        val afterRetry = prefs.values.toMap()
        config.migrateLogShipTcpDefault()
        assertEquals(afterRetry, prefs.values)
    }

    private data class FakePreferences(
        val instance: SharedPreferences,
        val values: MutableMap<String, Any?>,
        val commitsSucceed: java.util.concurrent.atomic.AtomicBoolean,
    )

    private fun profile(
        id: String,
        revision: String = "revision-a",
        hasButtonBacklight: Boolean = false,
    ): DeviceProfile = fakeProfile(
        id = id,
        revision = revision,
        displayName = id,
        hasButtonBacklight = hasButtonBacklight,
    )

    private fun fakePreferences(
        initial: Map<String, Any?> = emptyMap(),
        commitSucceeds: Boolean = true,
    ): FakePreferences {
        val values = initial.toMutableMap()
        val commitsSucceed = java.util.concurrent.atomic.AtomicBoolean(commitSucceeds)
        lateinit var prefs: SharedPreferences
        prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getString" -> values[args!![0]] as? String ?: args[1]
                "getStringSet" -> values[args!![0]] as? Set<*> ?: args[1]
                "getInt" -> values[args!![0]] as? Int ?: args[1]
                "getLong" -> values[args!![0]] as? Long ?: args[1]
                "getFloat" -> values[args!![0]] as? Float ?: args[1]
                "getBoolean" -> values[args!![0]] as? Boolean ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "edit" -> fakeEditor(values) { commitsSucceed.get() }
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                "toString" -> "FakeSharedPreferences"
                else -> error("unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences
        return FakePreferences(prefs, values, commitsSucceed)
    }

    private fun fakeEditor(
        values: MutableMap<String, Any?>,
        commitSucceeds: () -> Boolean,
    ): SharedPreferences.Editor {
        val writes = LinkedHashMap<String, Any?>()
        val removals = LinkedHashSet<String>()
        var clear = false
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when {
                method.name.startsWith("put") -> editor.also {
                    writes[args!![0] as String] = args[1]
                    removals.remove(args[0])
                }
                method.name == "remove" -> editor.also {
                    val key = args!![0] as String
                    writes.remove(key)
                    removals.add(key)
                }
                method.name == "clear" -> editor.also { clear = true; writes.clear(); removals.clear() }
                method.name == "commit" -> {
                    val succeeds = commitSucceeds()
                    if (succeeds) {
                        if (clear) values.clear()
                        removals.forEach(values::remove)
                        values.putAll(writes)
                    }
                    succeeds
                }
                method.name == "apply" -> {
                    if (commitSucceeds()) {
                        if (clear) values.clear()
                        removals.forEach(values::remove)
                        values.putAll(writes)
                    }
                    null
                }
                method.name == "toString" -> "FakeEditor"
                else -> error("unexpected Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        return editor
    }
}
