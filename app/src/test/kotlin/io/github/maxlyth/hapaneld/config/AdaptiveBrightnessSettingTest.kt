package io.github.maxlyth.hapaneld.config

import java.io.File
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
            displayKeys.indexOf("auto_brightness") < displayKeys.indexOf("auto_brightness_minimum_percent"),
        )
        assertTrue(
            displayKeys.indexOf("auto_brightness_minimum_percent") < displayKeys.indexOf("auto_brightness_sensitivity"),
        )
    }

    @Test fun `minimum automatic level preserves the existing floor by default`() {
        val spec = SettingsRegistry.spec("auto_brightness_minimum_percent")!!

        assertEquals(SettingType.INT, spec.type)
        assertEquals("Minimum level", spec.label)
        assertEquals("4", spec.default)
        assertEquals(4.0, spec.min)
        assertEquals(95.0, spec.max)
        assertEquals(1.0, spec.step)
        assertEquals(Scope.DEVICE, spec.scope)
        assertNull(spec.ha)
        assertEquals("4", (SettingValue.validate(spec, "4") as Validation.Ok).normalized)
        assertEquals("95", (SettingValue.validate(spec, "95") as Validation.Ok).normalized)
        assertTrue(SettingValue.validate(spec, "3") is Validation.Bad)
        assertTrue(SettingValue.validate(spec, "96") is Validation.Bad)
    }

    @Test fun `sensitivity is bounded with a balanced default`() {
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

    @Test fun `configure hides automatic brightness child controls while disabled`() {
        val source = listOf(
            File("src/main/assets/configure.js"),
            File("app/src/main/assets/configure.js"),
        ).first { it.isFile }.readText()

        val renderGate = source.substring(
            source.indexOf("function shouldRenderRow"),
            source.indexOf("function radioJoined"),
        )
        assertTrue(renderGate.contains("auto_brightness_minimum_percent"))
        assertTrue(renderGate.contains("auto_brightness_sensitivity"))
        assertTrue(renderGate.contains("""values.auto_brightness !== "true""""))

        val displayCard = source.substring(
            source.indexOf("""if (g === "Display")"""),
            source.indexOf("// Dashboard card action"),
        )
        assertTrue(displayCard.contains("""values.auto_brightness === "true" && ambientLightSourceConfigured()"""))
    }
}
