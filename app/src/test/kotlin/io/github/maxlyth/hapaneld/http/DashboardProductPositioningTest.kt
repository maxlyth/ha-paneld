package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class DashboardProductPositioningTest {
    private fun source(path: String): String = listOf(
        File(path),
        File("app/$path"),
    ).first { it.isFile }.readText()

    private val server = source("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
    private val english = JSONObject(source("src/main/assets/i18n/en.json")).getJSONObject("strings")
    private fun english(key: String): String = english.getJSONObject(key).getString("text")

    @Test fun runtimeUiDoesNotRecommendOrSpecialCaseFullyKiosk() {
        val runtimeUi = listOf(
            source("src/main/assets/configure.js"),
            source("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            source("src/main/kotlin/io/github/maxlyth/hapaneld/http/PanelInfo.kt"),
            source("src/main/kotlin/io/github/maxlyth/hapaneld/http/HealthAudit.kt"),
        ).joinToString("\n").lowercase()

        assertFalse(runtimeUi.contains("fully kiosk"))
        assertFalse(runtimeUi.contains("fully-kiosk.com"))
        assertFalse(runtimeUi.contains("de.ozerov.fully"))
    }

    @Test fun missingRendererGuidanceLeadsWithSupportedChoices() {
        assertTrue(server.contains("strings.get(\"configure.setup.renderer.title\")"))
        assertTrue(server.contains("strings.get(\"configure.setup.renderer.body\")"))
        val title = english("configure.setup.renderer.title")
        val body = english("configure.setup.renderer.body")
        assertTrue(title.startsWith("MQTT is configured. Next:"))
        assertTrue(body.contains("ha-paneld's built-in renderer"))
        assertTrue(body.contains("another dashboard package"))
        assertFalse(body.contains("Fully Kiosk", ignoreCase = true))
    }

    @Test fun builtinRendererSignInGuidanceDoesNotLookLikeTheMqttStepFailed() {
        listOf(
            "dashboard.banner.ha_sign_in.title",
            "dashboard.banner.ha_sign_in.explanation",
            "dashboard.banner.ha_sign_in.action",
        ).forEach { key -> assertTrue(server.contains("strings.get(\"$key\")")) }
        val title = english("dashboard.banner.ha_sign_in.title")
        val explanation = english("dashboard.banner.ha_sign_in.explanation")
        assertTrue(title.startsWith("MQTT is configured. Next:"))
        assertTrue(explanation.contains("ha-paneld's built-in renderer is selected"))
        assertFalse(title.contains("failed", ignoreCase = true))
        assertFalse(explanation.contains("failed", ignoreCase = true))
    }
}
