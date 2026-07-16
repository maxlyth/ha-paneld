package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZigbeeHealthSurfaceContractTest {
    @Test fun mqttDiscoveryPublishesAlwaysPresentDiagnosticHealthSensor() {
        val source = source("MqttBridge.kt")
        assertTrue(source.contains("\"sensor\", \"\${panel}_zigbee_gateway_health\""))
        assertTrue(source.contains("\"json_attributes_topic\":\"\$attrZigbeeHealth\""))
        assertTrue(source.contains("\"entity_category\":\"diagnostic\""))
        assertTrue(source.contains("if (on) onZigbeeExplicitRetry()"))
    }

    @Test fun httpAndDiagnosticsUseTheSameBoundedSnapshot() {
        val server = source("http/PaneldServer.kt")
        val diagnostics = source("http/DiagReader.kt")
        assertTrue(server.contains("zigbee_gateway") && server.contains("\$zigbee"))
        assertTrue(server.contains("JSONObject(st.mqttAttributes())"))
        assertTrue(diagnostics.contains("[zigbee-health]"))
        assertFalse(diagnostics.contains("networkKey"))
        assertFalse(diagnostics.contains("radioMac"))
    }

    @Test fun installCardDisplaysGatewayHealthSeparatelyFromFirmwareStatus() {
        val server = source("http/PaneldServer.kt")
        val js = asset("install.js").readText()
        assertTrue(server.contains("id=\"radio-health\""))
        assertTrue(js.contains("document.getElementById('radio-health')"))
        assertTrue(js.contains("d.state || 'unknown'"))
    }

    private fun source(relative: String): String {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(working, "app/src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
            File(working, "src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
        ).first(File::isFile).readText()
    }

    private fun asset(name: String): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(File(working, "app/src/main/assets/$name"), File(working, "src/main/assets/$name"))
            .first(File::isFile)
    }
}
