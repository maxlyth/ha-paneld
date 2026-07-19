package io.github.maxlyth.hapaneld.http

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class AutoBrightnessHttpApiTest {
    @Test fun `unwired API is readable but closes mutations`() = runTest {
        val api = AutoBrightnessHttpApi.UNAVAILABLE

        assertFalse(JSONObject(api.statusJson()).getBoolean("available"))
        assertEquals(JSONArray().toString(), JSONObject(api.historyJson()).getJSONArray("points").toString())
        assertEquals(JSONArray().toString(), JSONObject(api.haSourcesJson("", 100)).getJSONArray("items").toString())
        val validation = api.validateHaSource("sensor.room_illuminance")
        assertEquals(503, validation.action.statusCode)
        assertFalse(JSONObject(validation.action.json).getBoolean("ok"))
        listOf(
            api.selectHaSource(null),
            api.resetHistory(),
            api.resumeFullAuto(),
        ).forEach { action ->
            assertEquals(503, action.statusCode)
            assertFalse(JSONObject(action.json).getBoolean("ok"))
        }
    }

    @Test fun `history query defaults and enforces public bounds`() {
        assertEquals(AutoBrightnessHistoryParameters(168, null, null), autoBrightnessHistoryParameters(null, null))
        assertEquals(AutoBrightnessHistoryParameters(1, 0, 4), autoBrightnessHistoryParameters("1", "0", "4"))
        assertEquals(AutoBrightnessHistoryParameters(168, 100, 95), autoBrightnessHistoryParameters("168", "100", "95"))

        listOf("0", "169", "not-a-number").forEach { hours ->
            assertTrue(runCatching { autoBrightnessHistoryParameters(hours, null) }.isFailure)
        }
        listOf("-1", "101", "balanced").forEach { sensitivity ->
            assertTrue(runCatching { autoBrightnessHistoryParameters(null, sensitivity) }.isFailure)
        }
        listOf("3", "96", "balanced").forEach { minimum ->
            assertTrue(runCatching { autoBrightnessHistoryParameters(null, null, minimum) }.isFailure)
        }
    }

    @Test fun `action result keeps status and JSON body together`() {
        val action = AutoBrightnessHttpAction.ok("""{"ok":true,"state":"learning"}""")

        assertEquals(200, action.statusCode)
        assertTrue(JSONObject(action.json).getBoolean("ok"))
        assertEquals("learning", JSONObject(action.json).getString("state"))
        assertTrue(runCatching { AutoBrightnessHttpAction(199, "{}") }.isFailure)
        assertTrue(runCatching { AutoBrightnessHttpAction(200, "") }.isFailure)
    }
}
