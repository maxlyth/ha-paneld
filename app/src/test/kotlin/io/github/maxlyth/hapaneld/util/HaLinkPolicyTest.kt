package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import org.junit.Assert.assertThrows

class HaLinkPolicyTest {
    @Test fun websocketResolutionIsBoundedForUntrustedConfiguredEndpoints() {
        // The former MAX_WS_FRAME_BYTES bound left with the CIO engine (the OkHttp engine refuses
        // frame-size configuration; see HaWebSocketClientsFailoverTest); the one-deadline wall
        // around connect+auth+query remains the resolver's protection.
        assertTrue(HaLink.WS_RESOLUTION_DEADLINE_MS in 1_000L..30_000L)
    }

    @Test fun httpResolutionResponsesAreBoundedBeforeTextDecoding() {
        assertTrue(HaLink.MAX_HTTP_RESPONSE_BYTES in 1L..(4L * 1024L * 1024L))
        assertEquals("{}", HaLink.readHttpBody(ByteArrayInputStream("{}".toByteArray())))
        assertThrows(ByteLimitExceeded::class.java) {
            HaLink.readHttpBody(ByteArrayInputStream(ByteArray(HaLink.MAX_HTTP_RESPONSE_BYTES.toInt() + 1)))
        }
    }

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
              {"ei":"text.kitchen_display_home_dashboard","di":"friendly-device"},
              {"ei":"light.kitchen_display_screen","di":"friendly-device"},
              {"ei":"text.wall_panel_home_dashboard","di":"stable-device"},
              {"ei":"light.wall_panel_screen","di":"stable-device"}
            ]}}
        """.trimIndent()

        assertEquals(
            "stable-device",
            HaLink.matchDeviceId(response, listOf("wall_panel", "kitchen_display")),
        )
    }

    @Test fun `room-name prefix cannot select an unrelated device before the panel entities`() {
        val response = """
            {"result":{"entities":[
              {"ei":"sensor.kitchen_temperature","di":"temperature-device"},
              {"ei":"binary_sensor.kitchen_motion","di":"motion-device"},
              {"ei":"text.kitchen_home_dashboard","di":"panel-device"},
              {"ei":"light.kitchen_screen","di":"panel-device"}
            ]}}
        """.trimIndent()

        assertEquals("panel-device", HaLink.matchDeviceId(response, listOf("kitchen")))
    }

    @Test fun `numeric HA collision suffix combines with an exact panel marker`() {
        val response = """
            {"result":[
              {"ei":"light.kitchen_screen","di":"unrelated-screen"},
              {"ei":"text.kitchen_home_dashboard","di":"panel-device"},
              {"ei":"light.kitchen_screen_2","di":"panel-device"}
            ]}
        """.trimIndent()

        assertEquals("panel-device", HaLink.matchDeviceId(response, listOf("kitchen")))
    }

    @Test fun `qualified exact collision owner cannot outrank the suffixed panel device`() {
        val response = """
            {"result":[
              {"ei":"text.kitchen_home_dashboard","di":"collision-owner"},
              {"ei":"light.kitchen_screen","di":"collision-owner"},
              {"ei":"text.kitchen_home_dashboard_2","di":"panel-device"},
              {"ei":"light.kitchen_screen_2","di":"panel-device"}
            ]}
        """.trimIndent()

        assertNull(HaLink.matchDeviceId(response, listOf("kitchen")))
    }

    @Test fun `historical friendly name remains a fallback when stable identity has no candidate`() {
        val response = """
            {"result":{"entities":[
              {"ei":"light.wall_panel_screen","di":"incomplete-stable-device"},
              {"ei":"text.kitchen_display_home_dashboard","di":"legacy-device"},
              {"ei":"light.kitchen_display_screen","di":"legacy-device"}
            ]}}
        """.trimIndent()

        assertEquals(
            "legacy-device",
            HaLink.matchDeviceId(response, listOf("wall_panel", "kitchen_display")),
        )
    }

    @Test fun `one same-prefix known-looking entity is insufficient evidence`() {
        val response = """
            {"result":[
              {"ei":"light.kitchen_screen","di":"unrelated-screen"},
              {"ei":"sensor.kitchen_temperature","di":"temperature-device"}
            ]}
        """.trimIndent()

        assertNull(HaLink.matchDeviceId(response, listOf("kitchen")))
    }

    @Test fun `registry response without a matching panel entity has no device destination`() {
        val response = """
            {"result":[{"ei":"sensor.unrelated_panel_status","di":"other-device"}]}
        """.trimIndent()

        assertNull(HaLink.matchDeviceId(response, listOf("wall_panel")))
    }
}
