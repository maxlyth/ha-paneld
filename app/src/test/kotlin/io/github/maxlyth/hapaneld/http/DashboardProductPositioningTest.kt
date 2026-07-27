package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardProductPositioningTest {
    private fun source(path: String): String = listOf(
        File(path),
        File("app/$path"),
    ).first { it.isFile }.readText()

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
        val server = source("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
        assertTrue(server.contains("MQTT is configured. Next: choose a dashboard renderer."))
        assertTrue(server.contains("Select ha-paneld's built-in renderer"))
        assertTrue(server.contains("install the Home Assistant Companion app"))
        assertFalse(server.contains("No dashboard app detected</b> — select ha-paneld's built-in renderer"))
    }

    @Test fun builtinRendererSignInGuidanceDoesNotLookLikeTheMqttStepFailed() {
        val server = source("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
        assertTrue(server.contains("MQTT is configured. Next: Home Assistant sign-in."))
        assertTrue(server.contains("ha-paneld's built-in renderer is selected. Complete the sign-in shown on the panel"))
    }
}
