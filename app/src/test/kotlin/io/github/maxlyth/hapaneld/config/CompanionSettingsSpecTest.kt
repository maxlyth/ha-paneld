package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the Companion auto-update settings' contract (user decisions, 2026-07-10): both are hidden —
 *  and their HA entities withdrawn — when no HA Companion is installed (the settings are meaningless
 *  without it), and both list AFTER the ha-paneld update settings in the System card. */
class CompanionSettingsSpecTest {
    private val keys = SettingsRegistry.SPECS.map { it.key }

    @Test fun gatedOnCompanionPresence() {
        for (k in listOf("companion_auto_update", "companion_update_channel")) {
            val spec = SettingsRegistry.spec(k)!!
            assertFalse("$k hidden without a Companion", spec.availableWhen(Capabilities()))
            assertTrue("$k shown with a Companion", spec.availableWhen(Capabilities(companionInstalled = true)))
        }
    }

    @Test fun listedAfterPaneldUpdateSettings() {
        assertTrue(keys.indexOf("companion_auto_update") > keys.indexOf("update_channel"))
        assertTrue(keys.indexOf("companion_update_channel") > keys.indexOf("companion_auto_update"))
        assertTrue(keys.indexOf("self_update") < keys.indexOf("update_channel"))
    }
}
