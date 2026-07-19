package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBrightnessSettingTest {
    @Test fun `ambient source precedes the controls that depend on it`() {
        val displayKeys = SettingsRegistry.settable().filter { it.group == "Display" }.map { it.key }

        assertTrue(
            displayKeys.indexOf("auto_brightness_ha_entity") < displayKeys.indexOf("auto_brightness"),
        )
        assertTrue(
            displayKeys.indexOf("auto_brightness") < displayKeys.indexOf("auto_brightness_sensitivity"),
        )
    }

    @Test fun `sensitivity is bounded with a neutral default`() {
        val spec = SettingsRegistry.spec("auto_brightness_sensitivity")!!

        assertEquals(SettingType.INT, spec.type)
        assertEquals("Sensitivity", spec.label)
        assertEquals("50", spec.default)
        assertEquals(0.0, spec.min)
        assertEquals(100.0, spec.max)
        assertEquals(5.0, spec.step)
        assertEquals(Scope.DEVICE, spec.scope)
        assertNull(spec.ha)
        assertEquals("0", (SettingValue.validate(spec, "0") as Validation.Ok).normalized)
        assertEquals("100", (SettingValue.validate(spec, "100") as Validation.Ok).normalized)
        assertTrue(SettingValue.validate(spec, "-1") is Validation.Bad)
        assertTrue(SettingValue.validate(spec, "101") is Validation.Bad)
    }

    @Test fun `HA source accepts only blank or an exact sensor entity id`() {
        val spec = SettingsRegistry.spec("auto_brightness_ha_entity")!!

        assertEquals(Scope.DEVICE, spec.scope)
        assertEquals("ha_illuminance", spec.picker)
        assertNull(spec.ha)
        assertEquals("", (SettingValue.validate(spec, "  ") as Validation.Ok).normalized)
        assertEquals(
            "sensor.living_room_illuminance",
            (SettingValue.validate(spec, " sensor.living_room_illuminance ") as Validation.Ok).normalized,
        )
        listOf(
            "light.living_room",
            "sensor.Living_Room",
            "sensor.room lux",
            "sensor.room/*",
            "{{ states('sensor.room') }}",
        ).forEach { value ->
            assertTrue("must reject $value", SettingValue.validate(spec, value) is Validation.Bad)
        }
    }
}
