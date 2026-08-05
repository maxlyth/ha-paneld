package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.config.SettingType
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structural guard for a defect that shipped: a value formatter attached to a `BOOL` key, which
 * `settingRowHtml` resolves to `on`/`off` before it ever consults a formatter. The formatter never
 * ran, nothing warned, and the live log-shipping status reached no page at all.
 */
class SettingRowFormatterTest {

    @Test fun aFormatterCannotBeBuiltForABoolSetting() {
        // The exact historical defect: log_ship_enabled is BOOL, so its row can never reach a formatter.
        val failure = runCatching { SettingRowFormatter.of("log_ship_enabled") { "$it (live)" } }
            .exceptionOrNull()
        assertTrue("expected construction to be rejected", failure is IllegalArgumentException)
        assertTrue(failure!!.message!!, failure.message!!.contains("log_ship_enabled"))
        assertTrue(failure.message!!, failure.message!!.contains("BOOL"))
    }

    @Test fun aFormatterCannotBeBuiltForASecretSetting() {
        // A secret resolves to set/— for the same reason, and formatting one would risk rendering it.
        val failure = runCatching { SettingRowFormatter.of("mqtt_password") { it } }.exceptionOrNull()
        assertTrue("expected construction to be rejected", failure is IllegalArgumentException)
        assertTrue(failure!!.message!!, failure.message!!.contains("secret"))
    }

    @Test fun aFormatterCannotBeBuiltForAnUnregisteredKey() {
        val failure = runCatching { SettingRowFormatter.of("no_such_setting") { it } }.exceptionOrNull()
        assertTrue("expected construction to be rejected", failure is IllegalArgumentException)
    }

    @Test fun everyKeyProductionActuallyFormatsIsFormattable() {
        // The call sites in behaviourRowsHtml and displayRowsHtml. If a future row formats a new key,
        // add it here — this list failing is the signal that the key cannot carry a formatter at all.
        listOf("ha_area", "auto_brightness_minimum_percent", "auto_brightness_sensitivity").forEach { key ->
            val formatter = SettingRowFormatter.of(key) { raw -> "$raw!" }
            assertEquals(key, formatter.key)
            assertEquals("x!", formatter("x"))
        }
    }

    @Test fun formattableMatchesExactlyWhatSettingRowHtmlCanReach() {
        // Pin the classification against the whole registry rather than a sample, so a new SettingType
        // or a spec flipped to secret cannot drift away from the rendering rule this type encodes.
        val specs = SettingsRegistry.SPECS
        assertTrue("registry unexpectedly empty", specs.size > 20)
        specs.forEach { spec ->
            val reachable = !spec.secret && spec.type != SettingType.BOOL
            assertEquals(spec.key, reachable, SettingRowFormatter.formattable(spec))
        }
        assertTrue(specs.any { SettingRowFormatter.formattable(it) })
        assertTrue(specs.any { !SettingRowFormatter.formattable(it) })
    }

    @Test fun aFormatterKnowsWhichRowItBelongsTo() {
        // settingRowHtml rejects a formatter built for a different key; the binding is what lets it.
        val formatter = SettingRowFormatter.of("ha_area") { raw -> "$raw (local override)" }
        assertEquals("ha_area", formatter.key)
        assertFalse(formatter.key == "home_dashboard")
    }
}
