package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.PowerRepairStepStatus
import io.github.maxlyth.hapaneld.control.PowerRiskLevel
import io.github.maxlyth.hapaneld.control.PowerSafetyAssessment
import io.github.maxlyth.hapaneld.control.PowerSafetyObservation
import io.github.maxlyth.hapaneld.control.PowerSafetyRepairResult
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSafetyPresentationTest {
    @Test fun structuredJsonAndDiagnosticsRetainBoundedProbeTruth() {
        val assessment = assessment(PowerRiskLevel.UNKNOWN)
        val json = JSONObject(PowerSafetyPresentation.json(assessment))

        assertEquals("unknown", json.getString("state"))
        assertTrue(json.getBoolean("warning"))
        assertTrue(json.isNull("plugged_mask"))
        assertEquals("unknown", json.getString("power_source"))
        assertTrue(json.isNull("stay_on_effective"))
        assertEquals("doze_exemption_unknown", json.getJSONArray("reason_codes").getString(0))

        val diagnostic = PowerSafetyPresentation.diagnosticLine(assessment)
        assertTrue(diagnostic.startsWith("[power-safety] state=unknown"))
        assertTrue(diagnostic.contains("power_source=unknown"))
        assertTrue(diagnostic.contains("doze_exempt=unknown"))
        assertFalse(diagnostic.contains("battery"))
    }

    @Test fun warningsUseOneSharedSummaryAndOfferOnlyAnExplicitPostRepair() {
        val assessment = assessment(PowerRiskLevel.AT_RISK)
        val warning = PowerSafetyPresentation.statusWarningHtml(assessment).orEmpty()
        val banner = PowerSafetyPresentation.bannerHtml(assessment, inlineRepair = true)

        assertTrue(warning.contains(assessment.summary))
        assertTrue(warning.contains(assessment.action))
        assertTrue(banner.contains("method=\"post\""))
        assertTrue(banner.contains("action=\"/api/v1/power-safety/repair\""))
        assertTrue(banner.contains("data-power-safety-repair"))
        assertTrue(banner.contains("Repair power safety"))
        assertTrue(banner.contains("never reboots"))
        assertFalse(banner.contains("onclick="))
        assertEquals("", PowerSafetyPresentation.bannerHtml(assessment(PowerRiskLevel.SAFE), true))
    }

    @Test fun repairResultReportsEveryCapabilityAwareStep() {
        val result = PowerSafetyRepairResult(
            status = "partial",
            keepAwake = PowerRepairStepStatus.APPLIED,
            preventIdleDim = PowerRepairStepStatus.FAILED,
            stayOnWhilePluggedIn = PowerRepairStepStatus.UNAVAILABLE,
            dozeExemption = PowerRepairStepStatus.ALREADY,
            privilegedPowerControl = "unavailable",
            assessment = assessment(PowerRiskLevel.CAUTION),
        )
        val json = JSONObject(PowerSafetyPresentation.repairJson(result))
        val steps = json.getJSONObject("steps")

        assertEquals("partial", json.getString("status"))
        assertFalse(json.getBoolean("complete"))
        assertEquals("unavailable", json.getString("privileged_power_control"))
        assertEquals("applied", steps.getString("keep_awake"))
        assertEquals("failed", steps.getString("prevent_idle_dim"))
        assertEquals("unavailable", steps.getString("stay_on_while_plugged_in"))
        assertEquals("already", steps.getString("doze_exemption"))
    }

    @Test fun allRequestedSurfacesConsumeTheSameAssessmentPresentation() {
        val server = source("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
        val diagnostic = source("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/DiagReader.kt")
        val controller = source("app/src/main/kotlin/io/github/maxlyth/hapaneld/control/PowerSafetyController.kt")
        val interaction = source("app/src/main/assets/power-safety.js")
        val provisioner = source("scripts/provision.sh")

        assertTrue(server.contains("PowerSafetyPresentation.bannerHtml(powerSafety(), inlineRepair = true)"))
        assertTrue(server.contains("PowerSafetyPresentation.statusWarningHtml(powerAssessment)"))
        assertTrue(server.contains("\\\"power_safety\\\":${'$'}{PowerSafetyPresentation.json(powerAssessment)}"))
        assertTrue(diagnostic.contains("PowerSafetyPresentation.diagnosticLine(it)"))
        assertTrue(server.contains("/assets/power-safety.js"))
        assertTrue(interaction.contains("form[data-power-safety-repair]"))
        assertTrue(interaction.contains("body.error === 'approval-required'"))
        assertTrue(interaction.contains("method: 'POST'"))
        assertTrue(interaction.contains("'Accept': 'application/json'"))
        assertTrue(controller.contains("runCatching(root::available)"))
        assertTrue(controller.contains("root.runSingleAttempt(\"settings put global"))
        assertTrue(controller.contains("Settings.Global.getInt"))
        assertTrue(controller.contains("stayBaseline == null -> PowerRepairStepStatus.UNAVAILABLE"))
        assertFalse(controller.contains("before.stayOnWhilePluggedIn ?: 0"))
        assertTrue(controller.contains("PowerSafetyRepairPolicy.dozeMutationCommand"))
        assertFalse(controller.contains("cmd deviceidle whitelist +${'$'}packageName ||"))
        assertTrue(controller.contains("powerManager.isIgnoringBatteryOptimizations(packageName)"))
        assertTrue(controller.contains("readKeepAwakeConfigured()"))
        assertTrue(controller.contains("readPreventIdleDimConfigured()"))
        assertFalse(controller.contains("runSingleAttempt(\"reboot"))
        assertFalse(controller.contains("root.run(\"reboot"))
        assertFalse(controller.contains("fireAndForget"))
        assertTrue(provisioner.contains("Read the app-owned power classification"))
        assertFalse(provisioner.contains("settings get global stay_on_while_plugged_in"))
        assertFalse(provisioner.contains("deviceidle whitelist"))
    }

    private fun assessment(level: PowerRiskLevel): PowerSafetyAssessment = PowerSafetyAssessment(
        level = level,
        observation = PowerSafetyObservation(
            keepAwakeConfigured = true,
            wakeLockHeld = true,
            wifiLockRequired = true,
            wifiLockHeld = true,
            preventIdleDimConfigured = true,
            screenOffTimeoutMs = Int.MAX_VALUE,
            interactive = true,
            pluggedMask = null,
            stayOnWhilePluggedIn = 3,
            deviceIdleMode = false,
            ignoringBatteryOptimizations = null,
            screenOffMechanism = "sysfs",
        ),
        reasonCodes = listOf("doze_exemption_unknown"),
        summary = "bounded summary",
        action = "bounded action",
    )

    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .first { it.isFile }
        .readText()
}
