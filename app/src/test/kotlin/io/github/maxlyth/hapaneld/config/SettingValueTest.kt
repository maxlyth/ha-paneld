package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingValueTest {

    private fun spec(
        type: SettingType,
        min: Double? = null,
        max: Double? = null,
        options: List<String> = emptyList(),
        validate: (String) -> Validation = { Validation.Ok(it) },
    ) = SettingSpec(
        key = "k", type = type, group = "g", label = "l", default = "",
        min = min, max = max, options = options, validate = validate,
    )

    private fun ok(v: Validation): String = (v as Validation.Ok).normalized

    @Test fun boolAcceptsCommonSpellings() {
        listOf("true", "1", "on", "ON", "yes").forEach {
            assertEquals("true", ok(SettingValue.validate(spec(SettingType.BOOL), it)))
        }
        listOf("false", "0", "off", "no").forEach {
            assertEquals("false", ok(SettingValue.validate(spec(SettingType.BOOL), it)))
        }
        assertTrue(SettingValue.validate(spec(SettingType.BOOL), "maybe") is Validation.Bad)
    }

    @Test fun intRangeChecked() {
        val s = spec(SettingType.INT, min = -100.0, max = 100.0)
        assertEquals("5", ok(SettingValue.validate(s, "5")))
        assertEquals("-100", ok(SettingValue.validate(s, "-100")))
        assertTrue(SettingValue.validate(s, "101") is Validation.Bad)
        assertTrue(SettingValue.validate(s, "x") is Validation.Bad)
    }

    @Test fun floatNormalizesIntegral() {
        val s = spec(SettingType.FLOAT)
        assertEquals("5", ok(SettingValue.validate(s, "5.0")))
        assertEquals("5.5", ok(SettingValue.validate(s, "5.5")))
    }

    @Test fun enumIsCaseInsensitiveAndNormalizes() {
        val s = spec(SettingType.ENUM, options = listOf("Off", "Always on", "Swipe reveal"))
        assertEquals("Always on", ok(SettingValue.validate(s, "always on")))
        assertTrue(SettingValue.validate(s, "nope") is Validation.Bad)
    }

    @Test fun panelIdSlugValidatorFromRegistry() {
        val s = SettingsRegistry.spec("panel_id")!!
        assertEquals("bedroom_ml", ok(SettingValue.validate(s, "Bedroom-ML")))
        assertTrue(SettingValue.validate(s, "  -- ") is Validation.Bad)
    }
}
