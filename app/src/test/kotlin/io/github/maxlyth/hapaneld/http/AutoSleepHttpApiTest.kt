package io.github.maxlyth.hapaneld.http

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import java.io.File

class AutoSleepHttpApiTest {
    @Test fun `config prerequisite rejections are structured JSON`() {
        val parsed = JSONObject(autoSleepConfigErrorJson(
            "auto-sleep-area-required",
            "Assign this panel to a Home Assistant Area before enabling Auto sleep.",
        ))

        assertFalse(parsed.getBoolean("ok"))
        assertEquals("auto-sleep-area-required", parsed.getString("error"))
        assertTrue(parsed.getString("message").contains("Home Assistant Area"))
    }

    @Test fun `prerequisite route is active-read admitted and redacted`() {
        val source = sequenceOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first(File::isFile).readText()
        val route = source.substringAfter("get(\"/auto-sleep/prerequisite\")")
            .substringBefore("get(\"/auto-sleep/history\")")

        assertTrue("if (!admitActiveRead(call)) return@get" in route)
        listOf("eligible", "phase", "area_name", "detail").forEach { assertTrue("\"$it\"" in route) }
        listOf("area_id", "device_id", "android_id", "panel_id").forEach { assertFalse("\"$it\"" in route) }
    }

    @Test fun `unwired runtime returns a compact readable status`() {
        val api = AutoSleepHttpApi.UNAVAILABLE

        val status = JSONObject(api.statusJson())
        assertFalse(status.getBoolean("available"))
        assertEquals("unavailable", status.getString("phase"))
        assertEquals(0, status.getInt("source_count"))
        assertFalse(status.getBoolean("manual_suppression"))
        assertEquals("", status.getString("detail"))
    }

    @Test fun `history hours default and bounds are strict`() {
        assertEquals(6, autoSleepHistoryHours(null))
        assertEquals(1, autoSleepHistoryHours("1"))
        assertEquals(24, autoSleepHistoryHours("24"))
        assertEquals(48, autoSleepHistoryHours("48"))
        listOf("", "0", "49", "six").forEach { raw ->
            assertTrue(runCatching { autoSleepHistoryHours(raw) }.isFailure)
        }
    }

    @Test fun `unwired history is categorical and explicit about exclusions`() = runTest {
        val history = JSONObject(AutoSleepHttpApi.UNAVAILABLE.historyJson(6))

        assertFalse(history.getBoolean("available"))
        assertTrue(history.getBoolean("area_sources_only"))
        assertEquals("selected_area_sources", history.getString("source_scope"))
        assertEquals(60_000L, history.getLong("bucket_ms"))
        assertEquals(0, history.getInt("source_count"))
        assertFalse(history.has("sources"))
        assertEquals(0, history.getJSONArray("segments").length())
        val exclusions = history.getJSONArray("exclusions")
        assertTrue((0 until exclusions.length()).any { exclusions.getString(it) == "panel_proximity" })
    }
}
