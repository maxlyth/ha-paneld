package io.github.maxlyth.hapaneld.http

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveGetAdmissionContractTest {
    private val serverSource by lazy {
        listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
    }
    private val openApi by lazy {
        val file = listOf(
            File("src/main/assets/openapi.json"),
            File("app/src/main/assets/openapi.json"),
        ).first { it.isFile }
        JSONObject(file.readText()).getJSONObject("paths")
    }

    @Test fun resourcefulGetRoutesGateBrowserAdmissionBeforeStartingWork() {
        assertRouteGatesBefore("/logs/stream", "handleLogStream(call)")
        assertRouteGatesBefore("/perf", "PerfReader.touch()")
        assertRouteGatesBefore("/perf/history", "call.request.queryParameters")
        assertRouteGatesBefore("/perf/history", "entityLearning.performanceHistoryJson(hours)")
        assertRouteGatesBefore("/auto-sleep/history", "call.request.queryParameters")
        assertRouteGatesBefore("/auto-sleep/history", "autoSleepHttpApi.historyJson(hours)")
        assertRouteGatesBefore("/screenshot.png", "interactive.screenshot()")
        assertRouteGatesBefore("/tame/suggest", "PerfReader.touch()")

        val status = routeBody("/status")
        assertTrue(status.indexOf("admitActiveRead(call)") in 0 until status.indexOf("UpdateChecker.check("))
    }

    @Test fun performanceOpenApiDescribesReducedProjectionAndConditionalAdmission() {
        val perf = openApi.getJSONObject("/api/v1/perf").getJSONObject("get")
        val perfDescription = perf.getJSONObject("responses")
            .getJSONObject("200")
            .getString("description")
        assertTrue(perfDescription.contains("feature costs are fetched separately"))
        assertFalse(perfDescription.contains("featureCosts field"))
        assertTrue(perf.getJSONObject("responses").has("403"))

        val history = openApi.getJSONObject("/api/v1/perf/history").getJSONObject("get")
        val hours = history.getJSONArray("parameters").getJSONObject(0)
        assertEquals("hours", hours.getString("name"))
        assertEquals("query", hours.getString("in"))
        val schema = hours.getJSONObject("schema")
        assertEquals(1, schema.getInt("minimum"))
        assertEquals(168, schema.getInt("maximum"))
        assertEquals(24, schema.getInt("default"))
        assertTrue(history.getString("description").contains("same-origin"))
        assertTrue(
            history.getJSONObject("responses").getJSONObject("403").getString("description")
                .contains("headerless LAN automation remains supported"),
        )
    }

    private fun assertRouteGatesBefore(path: String, work: String) {
        val body = routeBody(path)
        assertTrue(
            "$path must gate active work",
            body.indexOf("admitActiveRead(call)") in 0 until body.indexOf(work),
        )
    }

    private fun routeBody(path: String): String {
        val start = serverSource.indexOf("get(\"$path\")")
        check(start >= 0) { "missing route $path" }
        val next = serverSource.indexOf("\n                    get(", start + 1)
        return serverSource.substring(start, if (next >= 0) next else serverSource.length)
    }
}
