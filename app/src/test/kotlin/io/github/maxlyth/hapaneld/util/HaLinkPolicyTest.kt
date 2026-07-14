package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaLinkPolicyTest {
    @Test fun `resolution target retains the exact native base path and normalizes identity`() {
        assertEquals(
            "v1:25:https://native.example/ha:wall_panel",
            HaLink.resolutionTarget("  https://native.example/ha/ ", "Wall Panel"),
        )
    }

    @Test fun `resolution target is safe for SharedPreferences XML`() {
        val target = HaLink.resolutionTarget("https://native.example/ha", "Wall Panel")

        assertEquals(-1, target.indexOf('\u0000'))
    }

    @Test fun `native and mqtt servers cannot share a cached device id`() {
        val native = HaLink.resolutionTarget("https://native.example/ha", "panel")
        val mqtt = HaLink.resolutionTarget("https://mqtt.example", "panel")

        assertNotEquals(native, mqtt)
    }

    @Test fun `stable panel identity wins over an earlier friendly name match`() {
        val response = """
            {"result":{"entities":[
              {"ei":"sensor.kitchen_display_status","di":"friendly-device"},
              {"ei":"sensor.wall_panel_status","di":"stable-device"}
            ]}}
        """.trimIndent()

        assertEquals(
            "stable-device",
            HaLink.matchDeviceId(response, listOf("wall_panel", "kitchen_display")),
        )
    }

    @Test fun `registry response without a matching panel entity has no device destination`() {
        val response = """
            {"result":[{"ei":"sensor.unrelated_panel_status","di":"other-device"}]}
        """.trimIndent()

        assertNull(HaLink.matchDeviceId(response, listOf("wall_panel")))
    }
}
