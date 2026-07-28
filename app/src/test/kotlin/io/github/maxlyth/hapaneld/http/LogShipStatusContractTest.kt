package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.logship.LogShipStatusProjection
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogShipStatusContractTest {
    @Test fun endpointProjectionSerializesTheDedicatedLiveState() {
        val json = JSONObject(logShipStatusJson(LogShipStatusProjection(true, true, "tcp://collector:514 · connected")))
        assertTrue(json.getBoolean("enabled"))
        assertTrue(json.getBoolean("configured"))
        assertEquals("tcp://collector:514 · connected", json.getString("text"))

        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val route = source.substringAfter("get(\"/logship/status\")").substringBefore("get(\"/config/probe-broker\")")
        assertTrue(route.contains("logShipStatus()"))
        assertFalse(route.contains("snapStaleOk"))
        assertFalse(route.contains("managementProjection"))
    }

    @Test fun openApiPromisesLiveDestinationSafeStatus() {
        val paths = JSONObject(File("src/main/assets/openapi.json").readText()).getJSONObject("paths")
        val operation = paths.getJSONObject("/api/v1/logship/status").getJSONObject("get")
        val description = operation.getJSONObject("responses").getJSONObject("200").getString("description")
        assertTrue(operation.getString("summary").contains("Live synchronized"))
        assertTrue(operation.getString("summary").contains("Dashboard Runtime diagnostics"))
        assertTrue(description.contains("Passive live read"))
        assertTrue(description.contains("without repeating the destination"))
    }
}
