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
        assertTrue(server.contains("post(\"/radio/join\")"))
        assertTrue(server.contains("onZigbeeJoinRetry()"))
        assertTrue(server.contains(".put(\"router_enabled\""))
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

    @Test fun configureCardOffersOneConfirmedJoinActionUsingExistingRouterToggle() {
        val js = asset("configure.js").readText()
        val css = asset("info.css").readText()
        val bridge = source("MqttBridge.kt")
        assertTrue(js.contains("id: \"cfg-zigbee_join\""))
        assertTrue(js.contains("Permit join is enabled — request join?"))
        assertTrue(js.contains("fetch(\"/api/v1/radio/join\", { method: \"POST\" })"))
        assertTrue(js.contains("text: \"Request join\""))
        assertTrue(js.contains("request.disabled = !enabled || joined || coolingDown"))
        assertTrue(js.contains("recomputeDirty();\n        loadRadio();\n        restampConfigWatchBaseline();\n        if (editGeneration"))
        assertFalse(js.contains("Turn Zigbee off"))
        assertFalse(js.contains("Join request active"))
        assertTrue(js.contains("joinCooldownUntil = Date.now() + 60000"))
        assertTrue(bridge.contains("fun requestZigbeeJoin(): Boolean"))
        assertTrue(bridge.contains("return admitZigbee(true) != ConflatedWorker.Admission.CLOSED"))
        assertTrue("router and join rows must render as one visual subgroup", css.contains("#cfg-zigbee_router + #cfg-zigbee_join{border-top:0;padding-top:0}"))
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
