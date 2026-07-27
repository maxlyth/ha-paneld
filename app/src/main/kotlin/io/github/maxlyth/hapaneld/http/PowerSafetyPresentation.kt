package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.PowerRiskLevel
import io.github.maxlyth.hapaneld.control.PowerSafetyAssessment
import io.github.maxlyth.hapaneld.control.PowerSafetyPolicy
import io.github.maxlyth.hapaneld.control.PowerSafetyRepairResult
import org.json.JSONArray
import org.json.JSONObject

internal object PowerSafetyPresentation {
    fun json(assessment: PowerSafetyAssessment): String = jsonObject(assessment).toString()

    fun repairJson(result: PowerSafetyRepairResult): String = JSONObject()
        .put("status", result.status)
        .put("complete", result.complete)
        .put("privileged_power_control", result.privilegedPowerControl)
        .put(
            "steps",
            JSONObject()
                .put("keep_awake", result.keepAwake.wireValue)
                .put("prevent_idle_dim", result.preventIdleDim.wireValue)
                .put("stay_on_while_plugged_in", result.stayOnWhilePluggedIn.wireValue)
                .put("doze_exemption", result.dozeExemption.wireValue),
        )
        .put("power_safety", jsonObject(result.assessment))
        .toString()

    fun statusWarningHtml(assessment: PowerSafetyAssessment): String? {
        if (!assessment.warning) return null
        val prefix = when (assessment.level) {
            PowerRiskLevel.AT_RISK -> "⛔ <b>Panel power safety: at risk</b>"
            PowerRiskLevel.CAUTION -> "⚠ <b>Panel power safety: caution</b>"
            PowerRiskLevel.UNKNOWN -> "⚠ <b>Panel power safety: unknown</b>"
            PowerRiskLevel.SAFE -> return null
        }
        return "$prefix — ${assessment.summary} ${assessment.action}"
    }

    fun bannerHtml(assessment: PowerSafetyAssessment, inlineRepair: Boolean): String {
        val warning = statusWarningHtml(assessment) ?: return ""
        val action = if (inlineRepair) {
            """ <form method="post" action="/api/v1/power-safety/repair" data-power-safety-repair style="display:inline">""" +
                """<button class="pbtn" type="submit" data-hardened-approval """ +
                """title="Repair is explicit, read-back verified, and never reboots the panel">Repair power safety</button>""" +
                """ <span class="power-safety-repair-result" role="status" aria-live="polite"></span></form>"""
        } else {
            " <a href=\"/configure#cfg-keep_awake\">Review and repair on Configure →</a>"
        }
        val critical = assessment.level == PowerRiskLevel.AT_RISK
        return "<div class=\"setup${if (critical) " crit" else ""}\">$warning$action</div>"
    }

    fun diagnosticLine(assessment: PowerSafetyAssessment): String {
        val observation = assessment.observation
        fun value(value: Any?): String = value?.toString() ?: "unknown"
        return buildString {
            append("[power-safety] state=").append(assessment.level.wireValue)
            append(" keep_awake=").append(observation.keepAwakeConfigured)
            append(" wake_lock=").append(observation.wakeLockHeld)
            append(" wifi_lock_required=").append(observation.wifiLockRequired)
            append(" wifi_lock=").append(observation.wifiLockHeld)
            append(" prevent_idle_dim=").append(observation.preventIdleDimConfigured)
            append(" screen_timeout_ms=").append(value(observation.screenOffTimeoutMs))
            append(" interactive=").append(value(observation.interactive))
            append(" power_source=").append(powerSource(observation.pluggedMask))
            append(" plugged_mask=").append(value(observation.pluggedMask))
            append(" stay_on_mask=").append(value(observation.stayOnWhilePluggedIn))
            append(" stay_on_effective=").append(value(PowerSafetyPolicy.stayAwakeEffective(observation)))
            append(" device_idle=").append(value(observation.deviceIdleMode))
            append(" doze_exempt=").append(value(observation.ignoringBatteryOptimizations))
            append(" screen_off=").append(observation.screenOffMechanism)
            append(" reasons=").append(assessment.reasonCodes.ifEmpty { listOf("none") }.joinToString(","))
        }
    }

    private fun jsonObject(assessment: PowerSafetyAssessment): JSONObject {
        val observation = assessment.observation
        return JSONObject()
            .put("state", assessment.level.wireValue)
            .put("warning", assessment.warning)
            .put("summary", assessment.summary)
            .put("action", assessment.action)
            .put("reason_codes", JSONArray(assessment.reasonCodes))
            .put("keep_awake_configured", observation.keepAwakeConfigured)
            .put("wake_lock_held", observation.wakeLockHeld)
            .put("wifi_lock_required", observation.wifiLockRequired)
            .put("wifi_lock_held", observation.wifiLockHeld)
            .put("prevent_idle_dim_configured", observation.preventIdleDimConfigured)
            .put("screen_off_timeout_ms", observation.screenOffTimeoutMs ?: JSONObject.NULL)
            .put("interactive", observation.interactive ?: JSONObject.NULL)
            .put("power_source", powerSource(observation.pluggedMask))
            .put("plugged_mask", observation.pluggedMask ?: JSONObject.NULL)
            .put("stay_on_while_plugged_in", observation.stayOnWhilePluggedIn ?: JSONObject.NULL)
            .put("stay_on_effective", PowerSafetyPolicy.stayAwakeEffective(observation) ?: JSONObject.NULL)
            .put("device_idle", observation.deviceIdleMode ?: JSONObject.NULL)
            .put("doze_exempt", observation.ignoringBatteryOptimizations ?: JSONObject.NULL)
            .put("screen_off_mechanism", observation.screenOffMechanism)
    }

    private fun powerSource(mask: Int?): String = when (mask) {
        null -> "unknown"
        0 -> "android_reports_unplugged"
        1 -> "ac"
        2 -> "usb"
        4 -> "wireless"
        8 -> "dock"
        else -> "multiple_or_oem_$mask"
    }
}
