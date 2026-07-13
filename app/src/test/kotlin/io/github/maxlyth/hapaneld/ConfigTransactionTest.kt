package io.github.maxlyth.hapaneld

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ConfigTransactionTest {
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
