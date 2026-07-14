package io.github.maxlyth.hapaneld

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ConfigTransactionTest {
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

    private data class FakePreferences(
        val instance: SharedPreferences,
        val values: MutableMap<String, Any?>,
    )

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
