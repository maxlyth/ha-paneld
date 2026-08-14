package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.config.SettingsRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveProximitySurfaceContractTest {
    @Test fun homeAssistantReceivesOnlyAvailableFleetNormalizedState() {
        val mqtt = source("MqttBridge.kt")

        assertTrue(mqtt.contains("normalizedLevel?.coerceIn(0, 100)"))
        assertTrue(mqtt.contains("registryExposable(\"proximity_level\""))
        // The proximity_level discovery payload is registry-driven; its % unit now lives in the
        // single SettingsRegistry descriptor rather than a MqttBridge literal (byte-parity is pinned
        // by DiscoveryParityTest).
        assertTrue(
            SettingsRegistry.spec("proximity_level")!!.ha!!
                .buildDiscoveryJson("test", "a", "d").contains("\"unit_of_measurement\":\"%\""),
        )
        assertTrue(mqtt.contains("availability_mode\":\"all\""))
        assertTrue(mqtt.contains("numericDeadband(4.0)"))
        assertTrue(mqtt.contains("publish(stateProximityLevel, \"\", retain = true)"))
        assertTrue(
            mqtt.indexOf("publish(proximityAvailabilityTopic") <
                mqtt.indexOf("publish(availabilityTopic, \"online\""),
        )
        assertFalse(mqtt.contains("fun publishProximity(near: Boolean)"))
    }

    @Test fun firstRunJourneyNamesWaitingAndRequiresDeliberateExamples() {
        val runtime = source("sensors/ProximityLearningRuntime.kt")
        val script = asset("proximity-learning.js")
        val help = SettingsRegistry.spec("wake_on_wave")!!.help

        assertTrue(runtime.contains("output.deliberateExample"))
        assertTrue(runtime.contains("waiting_for_reading"))
        assertTrue(runtime.contains("put(\"canTeach\""))
        assertTrue(script.contains("Waiting for the sensor’s first reading"))
        assertTrue(script.contains("heading.appendChild(node(\"span\", \"cardbadge exp\", \"experimental\"))"))
        assertTrue(script.contains("teach.disabled"))
        assertTrue(script.indexOf("active = !!") < script.indexOf("teach.hidden ="))
        assertTrue(help.contains("far-to-near-to-far"))
        assertFalse(help.contains("instant proximity"))
    }

    @Test fun independentReportMaskReachesTheMqttStateOwnerWithoutCollapsing() {
        val reporter = source("sensors/SensorReporter.kt")
        val callbacks = source("sensors/SensorRunCallbacks.kt")
        val service = source("PaneldService.kt")
        val mqtt = source("MqttBridge.kt")

        assertTrue(reporter.contains("run.proximity(decision.near, decision.normalizedLevel, decision.reportMask)"))
        assertTrue(callbacks.contains("private val onProximity: (Boolean?, Int?, Int) -> Unit"))
        assertTrue(callbacks.contains("onProximity(near, normalizedLevel, reportMask)"))
        assertTrue(service.contains("onProximity = { near, level, reportMask ->"))
        assertTrue(service.contains("mqtt.publishProximity(near, level, reportMask)"))
        assertTrue(mqtt.contains("reportMask: Int = ProximityReportGate.BOTH"))
        assertTrue(mqtt.contains("val admitted = admit(nextNear, level, reportMask)"))
        assertTrue(mqtt.contains("proximityPublication.near?.let"))
        assertTrue(mqtt.contains("proximityPublication.level?.let"))
        assertTrue(mqtt.contains("admitted and ProximityReportGate.PRESENCE != 0"))
        assertTrue(mqtt.contains("admitted and ProximityReportGate.LEVEL != 0"))
    }

    @Test fun continuousHotPathAvoidsFastPollingAndSnapshotAllocation() {
        val reporter = source("sensors/SensorReporter.kt")
        val runtime = source("sensors/ProximityLearningRuntime.kt")
        val reportGate = source("sensors/ProximityReportGate.kt")
        val observe = runtime.substring(runtime.indexOf("fun observe("), runtime.indexOf("fun sourceUnavailable("))
        val copy = runtime.substring(runtime.indexOf("private fun copy("), runtime.indexOf("private fun result("))
        val handle = reporter.substring(
            reporter.indexOf("private fun handleProximity("),
            reporter.indexOf("private fun deliverProximity("),
        )

        assertTrue(reporter.contains("SensorManager.SENSOR_DELAY_NORMAL"))
        assertFalse(reporter.contains("SensorManager.SENSOR_DELAY_UI"))
        assertTrue(reporter.contains("proximityRuntime?.tick(now, sparseReporting = reportingSparse())"))
        assertTrue(reporter.contains("ON_CHANGE_PROBE_INTERVAL_MS"))
        assertTrue(reporter.contains("sm.unregisterListener(activeListener, sensor)"))
        assertTrue(reporter.contains("handler.post {"))
        assertTrue(runtime.contains("private val reportGate = ProximityReportGate()"))
        assertTrue(runtime.contains("reportGate.project("))
        assertTrue(reportGate.contains("internal class ProximityReportGate("))
        assertTrue(reportGate.contains("private var pendingPresence"))
        assertTrue(reportGate.contains("private var lastLevelAt"))
        assertTrue(reportGate.contains("const val PRESENCE = 1"))
        assertTrue(reportGate.contains("const val LEVEL = 2"))
        assertFalse(reportGate.contains("Thread("))
        assertFalse(reportGate.contains("Handler"))
        assertFalse(reportGate.contains("Timer"))
        assertTrue(reporter.contains("sparseLearningSource = proximityPolicy.sparseLearning"))
        assertTrue(handle.contains("val sparseForObservation = reportingSparse()"))
        assertTrue(
            handle.indexOf("val sparseForObservation = reportingSparse()") <
                handle.indexOf("cadenceClassified = false"),
        )
        assertTrue(handle.contains("cadenceClassified = false"))
        assertTrue(handle.contains("proximitySampleCount = 1"))
        assertTrue(handle.contains("sparseReporting = sparseForObservation"))
        assertTrue(reporter.contains("proximityCadenceWindowIsContinuous(proximitySampleCount)"))
        assertTrue(reporter.contains("sparseReporting = true"))
        assertTrue(reporter.contains("proximityPolicy.onChangeHalLiveness"))
        assertFalse(reporter.contains("sparseReportingHint"))
        assertFalse(observe.contains("setOf("))
        assertFalse(copy.contains("snapshot()"))
    }

    @Test fun vi530xCallbacksJoinTheSensorHandlerBeforeMutatingLearningState() {
        val reporter = source("sensors/SensorReporter.kt")
        val vi530xBranch = reporter.substring(
            reporter.indexOf("proximityAcquisition == ProximityAcquisition.VI530X"),
            reporter.indexOf("proximityAcquisition == ProximityAcquisition.ANDROID_HAL"),
        )

        assertTrue(vi530xBranch.contains("onValue = { raw ->\n                            handler.post {"))
        assertTrue(vi530xBranch.contains("onUnavailable = {\n                            handler.post {"))
        assertTrue(reporter.contains("ProximityAcquisition.VI530X -> \"helper-vi530x\""))
    }

    @Test fun persistenceAndWakeBoundariesFailClosed() {
        val runtime = source("sensors/ProximityLearningRuntime.kt")
        val service = source("PaneldService.kt")
        val screen = source("control/ScreenController.kt")
        val server = source("http/PaneldServer.kt")
        val configure = asset("configure.js")

        assertTrue(runtime.contains("restorePendingEvidence(evidence)"))
        assertTrue(runtime.contains("withoutWakeEvidence(previous"))
        assertTrue(runtime.contains("output.completedEpisode || modelChanged || wakeEvidenceInvalidated"))
        assertFalse(runtime.contains("DiscardOldestPolicy"))
        assertFalse(runtime.contains("shutdownNow()"))
        assertTrue(service.contains("config.wakeOnWaveGeneration != settingGeneration"))
        assertTrue(service.contains("!sensors.hasLearnedProximity()"))
        assertTrue(screen.contains("fun reconcileObservedLit(expectedGeneration"))
        assertTrue(screen.contains("observedDarkGeneration != expectedGeneration"))
        assertTrue(server.contains("config.wakeOnWave && sensors.hasProximity()"))
        assertTrue(server.contains("PROXIMITY_SOURCE_REQUIRED"))
        assertTrue(server.contains("hasProximity = sensors.hasProximity()"))
        assertTrue(server.contains("hasLearnedProximity = sensors.hasLearnedProximity()"))
        assertTrue(server.contains("action != \"cancel\" && !sensors.hasProximity()"))
        assertTrue(server.contains("val proximityLearningEnabled = sensors.hasProximity() && config.wakeOnWave"))
        assertTrue(server.contains("if (proximityLearningEnabled) \"\"\"<div id=\"proximity-learning-mount\""))
        assertTrue(configure.contains("submittedValues, \"wake_on_wave\""))
        assertTrue(configure.contains("window.location.reload();"))
        assertTrue(runtime.contains("fun isLearnedSignal(): Boolean = isLearnedMode(view.mode)"))
        assertTrue(mqttTombstonesAllPresenceSurfaces())
    }

    private fun mqttTombstonesAllPresenceSurfaces(): Boolean {
        val mqtt = source("MqttBridge.kt")
        return mqtt.contains("capabilitySnapshot?.hasLearnedProximity == true") &&
            mqtt.contains("registryExposable(\"proximity\", proximityAvail, learnedProximity)") &&
            mqtt.contains("registryExposable(\"proximity_level\", proximityAvail, learnedProximity)") &&
            mqtt.contains("if (hasProximity) known(if (config.wakeOnWave)")
    }

    private fun source(relative: String): String = locate("src/main/kotlin/io/github/maxlyth/hapaneld/$relative").readText()

    private fun asset(name: String): String = locate("src/main/assets/$name").readText()

    private fun locate(relative: String): File = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        .firstOrNull(File::isFile) ?: error("missing test input $relative")
}
