package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardenedControlContractTest {
    private fun source(relative: String): String = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
    ).first { it.isFile }.readText()

    private val server by lazy { source("http/PaneldServer.kt") }
    private val mqtt by lazy { source("MqttBridge.kt") }

    @Test fun configImportApprovalBindsCompleteHttpRequestAndRevisionRestoreBindsContent() {
        val import = server.substring(
            server.indexOf("private suspend fun handleConfigImport"),
            server.indexOf("private suspend fun applyAccepted"),
        )
        assertTrue(import.contains("exactHttpApprovalPayload(call, importDigest)"))
        assertTrue(import.contains("rejectHardenedNetworkAdb(call, accepted[\"network_adb\"])"))

        val revision = server.substring(
            server.indexOf("private suspend fun handleRevisionRestore"),
            server.indexOf("private fun jarr"),
        )
        assertTrue(revision.contains("bundle.serialize()"))
        assertTrue(revision.contains("exactHttpApprovalPayload(call, revisionDigest)"))
        assertTrue(revision.indexOf("authorizeSensitive(") < revision.indexOf("applyAccepted("))
    }

    @Test fun hardenedModeRejectsRemoteCoordinateInjectionBeforeQueueAdmission() {
        val handler = server.substring(
            server.indexOf("private suspend fun respondRemoteAdmission"),
            server.indexOf("private fun configSchemaJson"),
        )
        assertTrue(handler.contains("command is RemoteControl.Tap"))
        assertTrue(handler.contains("remote-input-disabled"))
        assertTrue(handler.contains("HttpStatusCode.Forbidden"))
        assertTrue(handler.indexOf("remote-input-disabled") < handler.indexOf("remoteControls.submit"))
        assertTrue(handler.contains("isLoopbackPeer(call.request.origin.remoteAddress)"))
        assertTrue(server.contains("config.hardenedSecurityEnabled && !command.loopback"))
    }

    @Test fun highImpactHttpAliasesAuthorizeBeforeMutation() {
        assertRouteAuthorizesBefore("/webview/heal", "onInstallComponent(")
        assertRouteAuthorizesBefore("/dashboard/clear-storage", "clearStorageGate.claim()")
        assertRouteAuthorizesBefore("/companion/repair-url", "onRepairCompanionUrl()")
        assertRouteAuthorizesBefore("/display/density", "density.reset()")
        assertRouteAuthorizesBefore("/power-safety/repair", "onRepairPowerSafety()")
        assertRouteAuthorizesBefore("/tame", "updateTameSelection")
        val remoteAction = server.substring(
            server.indexOf("internal suspend fun handleRemoteAction"),
            server.indexOf("/** One renderer-sensitive execution seam"),
        )
        assertTrue(remoteAction.contains("SensitiveOperation.DASHBOARD_RELOAD"))
        assertTrue(remoteAction.contains("SensitiveOperation.DEVICE_REBOOT"))
        assertTrue(remoteAction.indexOf("dependencies.authorizeSensitive(") <
            remoteAction.indexOf("dependencies.admit("))

        val tame = route("/tame")
        assertTrue(tame.contains("tame.recommendedSelections(tameProfileCandidates)"))
        assertTrue(tame.contains("SensitiveOperation.PACKAGE_TAME"))

        val power = route("/power-safety/repair")
        assertTrue(power.contains("SensitiveOperation.POWER_CONFIGURATION"))
        assertTrue(power.contains("sha256Hex(ByteArray(0))"))
        assertFalse(power.contains("reboot("))
        assertFalse(power.contains("fireAndForget"))
    }

    @Test fun mqttFreshInstallsRebootAndPolicyExpansionRemainApprovalGated() {
        val dispatch = mqtt.substring(mqtt.indexOf("private fun dispatchCommand"), mqtt.indexOf("fun publishScreenOn"))
        assertTrue(dispatch.contains("cmdUpdateCompanion ->"))
        assertTrue(dispatch.contains("cmdUpdatePaneld ->"))
        assertTrue(dispatch.contains("cmdReboot ->"))
        assertTrue(dispatch.contains("SensitiveOperation.APK_INSTALL"))
        assertTrue(dispatch.contains("SensitiveOperation.DEVICE_REBOOT"))

        val auto = mqtt.substring(mqtt.indexOf("override fun handleCompanionAuto"), mqtt.indexOf("override fun handleSilenceBootChime"))
        assertTrue(auto.contains("if (on && approvalRequired) authorizeMqttSensitive("))
        assertTrue(auto.contains("approvalRequired && config.selfUpdate && requested != was"))
        assertTrue(auto.contains("approvalRequired && config.companionAutoUpdate && requested != was"))

        val serviceApply = mqtt.substring(mqtt.indexOf("internal fun applySetting"), mqtt.indexOf("// ---- discovery ----"))
        assertTrue(serviceApply.contains("sensitiveApprovalRequired = false"))
        assertTrue(serviceApply.contains("dispatchLiveSetting("))
        assertTrue(serviceApply.contains("handlers = this"))
        assertFalse(serviceApply.contains("\"mqtt\""))
    }

    @Test fun networkAdbCannotBeEnabledUnderHardenedMode() {
        val handler = mqtt.substring(mqtt.indexOf("override fun handleNetAdb"), mqtt.indexOf("private fun authorizeMqttSensitive"))
        assertTrue(handler.contains("on && config.hardenedSecurityEnabled"))
        assertFalse(handler.contains("LocalApprovalBroker"))

        val configPost = server.substring(
            server.indexOf("private suspend fun handleConfigPost"),
            server.indexOf("private fun updateTameSelection"),
        )
        assertTrue(configPost.contains("rejectHardenedNetworkAdb(call, p[\"network_adb\"])"))
        assertTrue(configPost.contains("tamePackagesChanged -> SensitiveOperation.PACKAGE_TAME"))

        val activity = source("ConfigActivity.kt")
        val enableHardened = activity.substring(
            activity.indexOf("private fun enableHardenedMode"),
            activity.indexOf("private fun showPendingApprovals"),
        )
        assertTrue(enableHardened.contains("classic network ADB"))
        assertTrue(enableHardened.contains("Android Wireless debugging"))
        assertTrue(enableHardened.contains("withContext(Dispatchers.IO)"))
        assertTrue(enableHardened.contains("RemoteDebugSecurityTransitionGate.mutate"))
        assertTrue(enableHardened.contains("CdpRelay.stopAndVerifyThen(this@ConfigActivity)"))
        assertTrue(enableHardened.contains("commitHardenedWhenRemoteAdbInactive"))
        assertTrue(
            enableHardened.indexOf("RemoteDebugSecurityTransitionGate.mutate") <
                enableHardened.indexOf("CdpRelay.stopAndVerifyThen"),
        )
        assertTrue(
            enableHardened.indexOf("CdpRelay.stopAndVerifyThen") <
                enableHardened.indexOf("commitHardenedWhenRemoteAdbInactive"),
        )
        assertTrue(
            enableHardened.indexOf("commitHardenedWhenRemoteAdbInactive") <
                enableHardened.indexOf("setSecurityMode"),
        )
        assertFalse(enableHardened.contains("port 5555"))

        val adb = source("control/AdbController.kt")
        assertTrue(adb.contains("noBackupFilesDir.resolve(NETWORK_ADB_DISABLE_MARKER_FILE)"))
        assertTrue(adb.contains("NETWORK_ADB_DISABLE_MARKER_FILE = \"network-adb-disable.v1\""))
        val set = adb.substring(adb.indexOf("fun set(on: Boolean)"), adb.indexOf("fun reassert()"))
        assertTrue(set.contains("RemoteDebugSecurityTransitionGate.mutate"))
        assertTrue(set.contains("if (disableMarker.isPending())"))
        assertTrue(set.indexOf("if (disableMarker.isPending())") < set.indexOf("apply()"))
        val reassert = adb.substring(adb.indexOf("fun reassert()"), adb.indexOf("private fun apply()"))
        assertTrue(reassert.contains("RemoteDebugSecurityTransitionGate.mutate"))
        assertTrue(reassert.contains("if (disableMarker.isPending())"))
        assertTrue(reassert.indexOf("completeDisableTransition()") < reassert.indexOf("apply()"))
        val hardenedStart = adb.indexOf("internal fun commitHardenedWhenRemoteAdbInactive")
        val hardened = adb.substring(
            hardenedStart,
            adb.indexOf("private fun completeDisableTransition", hardenedStart),
        )
        assertTrue(hardened.contains("RemoteDebugSecurityTransitionGate.mutate"))
        val admission = adb.substring(
            adb.indexOf("internal fun completeHardenedNetworkAdbAdmission"),
            adb.indexOf("internal fun shouldReassertNetworkAdb"),
        )
        assertTrue(admission.contains("ownershipEnabled() || !disableAbsentDurably()"))
        assertTrue(
            admission.indexOf("ownershipEnabled() || !disableAbsentDurably()") <
                admission.indexOf("commitHardened()"),
        )

        val dashboard = source("DashboardActivity.kt")
        assertTrue(
            dashboard.contains(
                "shouldEnableWebViewDebugging(config.networkAdbEnabled, config.hardenedSecurityEnabled)",
            ),
        )
        val relay = source("control/CdpRelay.kt")
        assertTrue(relay.contains("if (Config(ctx).hardenedSecurityEnabled) return@mutate \"failed\""))
    }

    @Test fun powerSafetyReductionsAuthorizeBeforeConfigMutationAndMqttCannotBypassApproval() {
        val configPost = server.substring(
            server.indexOf("private suspend fun handleConfigPost"),
            server.indexOf("private fun updateTameSelection"),
        )
        assertTrue(configPost.contains("PowerSafetyMutationPolicy.requestsSafetyReduction("))
        assertTrue(configPost.contains("ConfigSensitiveAdmission.authorize("))
        assertTrue(configPost.contains("SensitiveOperation.POWER_CONFIGURATION"))
        assertTrue(configPost.contains("exactHttpApprovalPayload(call, p.canonicalDigest())"))
        assertTrue(configPost.contains("separate-sensitive-changes"))
        assertTrue(configPost.indexOf("ConfigSensitiveAdmission.authorize(") <
            configPost.indexOf("InstallProgress.startConfigMutation()"))

        val handler = mqtt.substring(
            mqtt.indexOf("override fun handlePreventIdleDim"),
            mqtt.indexOf("override fun handleTouchSound"),
        )
        assertTrue(handler.contains("PowerSafetyMutationPolicy.parseGuardSwitch(payload)"))
        assertTrue(handler.contains("SensitiveOperation.POWER_CONFIGURATION"))
        assertTrue(handler.contains("\"prevent_idle_dim\\u0000${'$'}payload\""))
        assertTrue(handler.indexOf("authorizeMqttSensitive(") < handler.indexOf("config.setPreventIdleDim(on)"))

        val dispatch = mqtt.substring(
            mqtt.indexOf("internal fun dispatchLiveSetting"),
            mqtt.indexOf("internal fun liveSettingApplyResult"),
        )
        assertTrue(dispatch.contains("if (sensitiveApprovalRequired) value else onOff"))
    }

    @Test fun securityModesAreNamedRelaxedAndHardenedInternallyAndOnPanel() {
        val config = source("Config.kt")
        assertTrue(config.contains("enum class SecurityMode { RELAXED, HARDENED }"))
        assertFalse(config.contains("SecurityMode.CONVENIENCE"))

        val activity = source("ConfigActivity.kt")
        assertTrue(activity.contains("Relaxed mode is on."))
        assertTrue(activity.contains("Use Relaxed mode"))
        assertTrue(activity.contains("Enable Hardened mode"))
        assertFalse(activity.contains("Convenience mode"))

        val service = source("PaneldService.kt")
        assertTrue(service.contains("\"Security mode\" to if (config.hardenedSecurityEnabled)"))
        assertTrue(service.contains("\"Relaxed\""))
    }

    @Test fun approvalSummariesIdentifyApkAndProfileTargets() {
        val control = source("http/ControlPlaneRoutes.kt")
        assertTrue(control.contains("identity.pkg"))
        assertTrue(control.contains("identity.version"))
        assertTrue(control.contains("identity.signerSha256"))
        val profiles = source("http/ProfileRoutes.kt")
        assertTrue(profiles.contains("approvalTarget.ref.id"))
        assertTrue(profiles.contains("approvalTarget.ref.revision.take(16)"))
        assertTrue(profiles.contains("Roll back to \$targetSummary"))
    }

    private fun assertRouteAuthorizesBefore(path: String, mutation: String) {
        val body = route(path)
        assertTrue("$path lacks a Hardened-mode authorization", body.contains("authorizeSensitive("))
        assertTrue("$path authorizes after mutation", body.indexOf("authorizeSensitive(") < body.indexOf(mutation))
    }

    private fun route(path: String): String {
        val start = server.indexOf("post(\"$path\")")
        check(start >= 0) { "missing POST $path" }
        // Route blocks contain nested indentation; use the next peer route/comment boundary conservatively.
        val peer = Regex("(?m)^ {20}(?:get|post)\\(\"").find(server, start + 1)?.range?.first ?: server.length
        return server.substring(start, peer)
    }
}
