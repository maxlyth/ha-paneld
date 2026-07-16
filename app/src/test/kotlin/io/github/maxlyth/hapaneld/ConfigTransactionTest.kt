package io.github.maxlyth.hapaneld

import android.content.SharedPreferences
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.Generic
import io.github.maxlyth.hapaneld.util.HaLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConfigTransactionTest {
    @Test fun proximityCalibrationIsScopedToTheActiveProfileRevision() {
        val prefs = fakePreferences()
        val calibration = fakePreferences()
        val config = Config(prefs.instance, calibration.instance)
        config.attachProfile(profile("first-panel"))
        config.captureProximity("near", 2f)
        config.captureProximity("far", 10f)
        config.setProximitySensitivity("LOW")

        config.attachProfile(profile("first-panel", "revision-b"))
        assertFalse(config.proximityCalibrated)
        assertEquals(Config.ProxSensitivity.MEDIUM, config.proximitySensitivity)
        config.captureProximity("near", 20f)
        config.captureProximity("far", 40f)

        config.attachProfile(profile("first-panel", "revision-a"))
        assertEquals(2f, config.proximityNearRaw, 0f)
        assertEquals(10f, config.proximityFarRaw, 0f)
        assertEquals(6f, config.proximityThreshold, 0f)
        assertEquals(Config.ProxSensitivity.LOW, config.proximitySensitivity)
    }

    @Test fun legacyProximityCalibrationBindsOnlyToTheInitiallyAttachedProfile() {
        val prefs = fakePreferences(initial = mapOf(
            "prox_near_raw" to 3f,
            "prox_far_raw" to 9f,
            "prox_threshold" to 6f,
            "prox_near_below" to true,
            "prox_sensitivity" to "HIGH",
        ))
        val calibration = fakePreferences()
        val config = Config(prefs.instance, calibration.instance)

        config.attachProfile(profile("legacy-panel"))
        assertEquals(3f, config.proximityNearRaw, 0f)
        assertEquals(9f, config.proximityFarRaw, 0f)
        assertEquals(Config.ProxSensitivity.HIGH, config.proximitySensitivity)

        config.attachProfile(profile("community.other-panel"))
        assertFalse(config.proximityCalibrated)
        assertEquals(Config.ProxSensitivity.MEDIUM, config.proximitySensitivity)
        assertTrue(calibration.values.containsKey("profile_calibration.legacy-panel.revision-a.prox_threshold"))
        assertFalse(calibration.values.containsKey("profile_calibration.community.other-panel.revision-a.prox_threshold"))
        assertFalse("physical calibration must not be copied into backup-eligible config prefs",
            prefs.values.keys.any { it.startsWith("profile_calibration.") })
    }

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
            config.setWakeOnWave(false)
            config.setCompanionUpdateChannel("pre-release")
            assertTrue(config.wakeOnWave)
            assertEquals("stable", config.companionUpdateChannel)
        }

        assertTrue(committed)
        assertFalse(config.wakeOnWave)
        assertEquals("prerelease", config.companionUpdateChannel)
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

    private data class FakePreferences(
        val instance: SharedPreferences,
        val values: MutableMap<String, Any?>,
    )

    private fun profile(
        id: String,
        revision: String = "revision-a",
        hasButtonBacklight: Boolean = false,
    ): DeviceProfile = object : DeviceProfile by Generic {
        override val id = id
        override val revision = revision
        override val displayName = id
        override val hasButtonBacklight = hasButtonBacklight
    }

    private fun fakePreferences(
        initial: Map<String, Any?> = emptyMap(),
        commitSucceeds: Boolean = true,
    ): FakePreferences {
        val values = initial.toMutableMap()
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
                "edit" -> fakeEditor(values, commitSucceeds)
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                "toString" -> "FakeSharedPreferences"
                else -> error("unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences
        return FakePreferences(prefs, values)
    }

    private fun fakeEditor(values: MutableMap<String, Any?>, commitSucceeds: Boolean): SharedPreferences.Editor {
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
                    if (commitSucceeds) {
                        if (clear) values.clear()
                        removals.forEach(values::remove)
                        values.putAll(writes)
                    }
                    commitSucceeds
                }
                method.name == "apply" -> {
                    if (commitSucceeds) {
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
