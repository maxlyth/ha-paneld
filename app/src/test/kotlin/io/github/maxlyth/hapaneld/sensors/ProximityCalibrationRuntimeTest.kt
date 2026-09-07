package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import io.github.maxlyth.hapaneld.sensors.ProximityCalibrationEngine.Calibration
import io.github.maxlyth.hapaneld.sensors.ProximityCalibrationEngine.Mode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Drives the production runtime, sparse live edges and its injected atomic model-store boundary. */
class ProximityCalibrationRuntimeTest {
    @Test fun ambientSamplesGesturesTickAndCloseNeverWriteCalibration() {
        val backing = Backing()
        val fixture = Fixture(backing)
        repeat(10) { index -> fixture.wave(40f, 5f, 2_000L + index * 3_000L) }
        fixture.runtime.close()
        assertEquals(0, backing.writes)
        assertEquals(0, backing.clears)
        assertNull(backing.row)
        assertEquals(1, backing.closes)
    }

    @Test fun completedWizardCommitsOnceAndRestartRestoresSameFingerprint() {
        val backing = Backing()
        val first = Fixture(backing)
        first.toReview(0f, 1f)
        assertEquals(0, backing.writes)
        assertFalse(first.runtime.isWaveReady())
        first.at(12_000)
        assertTrue(first.runtime.localAction("save"))
        assertEquals("saved", first.status().getString("stage"))
        assertEquals("user", first.status().getString("calibrationSource"))
        assertEquals(1, backing.writes)
        val committed = checkNotNull(backing.row)
        assertEquals(ProximityCalibrationRuntime.STORAGE_VERSION, committed.algorithmVersion)
        assertEquals(Mode.BINARY, ProximityCalibrationRuntime.decode(committed.snapshotJson)!!.mode)
        assertFalse(first.runtime.localAction("save"))
        first.runtime.close()
        assertEquals(committed, backing.row)
        assertEquals(1, backing.writes)

        val restarted = Fixture(backing)
        assertEquals("user", restarted.status().getString("calibrationSource"))
        assertFalse(restarted.runtime.isWaveReady())
        restarted.wave(0f, 1f, 2_000)
        assertEquals(committed, backing.row)
        assertEquals(1, backing.writes)
    }

    @Test fun bothBinaryPolaritiesAndRangedOverridePersistAndWakeAfterRestart() {
        for ((clear, near) in listOf(0f to 1f, 1f to 0f, 2f to 100f)) {
            val backing = Backing()
            val first = Fixture(backing)
            first.toReview(clear, near)
            first.at(12_000)
            assertTrue(first.runtime.localAction("save"))
            first.runtime.close()
            val restarted = Fixture(backing)
            restarted.wave(clear, near, 2_000)
            assertEquals(1, backing.writes)
        }
    }

    @Test fun profileRevisionAndSourceIdentityChangesExcludePreviousOverride() {
        for ((source, profile) in listOf(SOURCE to "profile-r2", "new-source" to PROFILE)) {
            val row = row(binary())
            val backing = Backing(row)
            val changed = Fixture(backing, source = source, profile = profile)
            assertEquals("profile", changed.status().getString("calibrationSource"))
            changed.wave(40f, 5f, 2_000)
            assertEquals(row, backing.row)
            assertEquals(0, backing.writes)
        }
    }

    @Test fun passiveMalformedAndUnreadyRowsCannotOverrideProfileDefaults() {
        val good = row(binary())
        val badRows = listOf(
            good.copy(algorithmVersion = 4, snapshotJson = "{\"farRaw\":0,\"nearRaw\":1,\"guidedReady\":true}"),
            good.copy(snapshotJson = "not-json"),
            good.copy(snapshotJson = "{\"version\":1}"),
            good.copy(ready = false),
            good.copy(snapshotJson = good.snapshotJson.replace("\"version\":1", "\"version\":2")),
        )
        for (bad in badRows) {
            val backing = Backing(bad)
            val fixture = Fixture(backing)
            assertEquals("profile", fixture.status().getString("calibrationSource"))
            fixture.wave(40f, 5f, 2_000)
            fixture.runtime.close()
            assertEquals(bad, backing.row)
            assertEquals(0, backing.writes)
            assertEquals(0, backing.clears)
        }
    }

    @Test fun cancelBrowserLossPanelLossSourceLossTimeoutAndRestartPreservePreviousRow() {
        for (interruption in listOf("cancel", "browser", "panel", "source", "timeout", "restart", "malformed")) {
            val previous = row(binary())
            val backing = Backing(previous)
            val fixture = Fixture(backing)
            fixture.toReview(2f, 100f)
            when (interruption) {
                "cancel" -> {
                    fixture.at(12_000)
                    assertTrue(fixture.runtime.cancel(fixture.session))
                }
                "browser" -> {
                    fixture.now = 11_550 + ProximityCalibrationRuntime.BROWSER_LEASE_MS + 1
                    fixture.runtime.visible()
                    fixture.runtime.tick(fixture.now, true)
                    assertTrue(fixture.status().getString("message").contains("browser disconnected"))
                }
                "panel" -> {
                    fixture.now = 11_550 + ProximityCalibrationRuntime.LOCAL_VISIBILITY_MS + 1
                    fixture.runtime.heartbeat(fixture.session)
                    fixture.runtime.tick(fixture.now, true)
                    assertTrue(fixture.status().getString("message").contains("screen closed"))
                }
                "source" -> {
                    fixture.at(12_000)
                    fixture.runtime.sourceUnavailable(fixture.now)
                    assertFalse(fixture.runtime.isWaveReady())
                }
                "timeout" -> {
                    fixture.now = 1_000 + ProximityCalibrationRuntime.SESSION_TIMEOUT_MS
                    fixture.runtime.heartbeat(fixture.session)
                    fixture.runtime.visible()
                    fixture.runtime.tick(fixture.now, true)
                    assertTrue(fixture.status().getString("message").contains("timed out"))
                }
                "restart" -> fixture.runtime.close()
                "malformed" -> {
                    fixture.at(12_000)
                    fixture.runtime.observe(Float.NaN, fixture.now, true)
                }
            }
            assertFalse(fixture.runtime.active())
            assertEquals(previous, backing.row)
            assertEquals(0, backing.writes)
            assertEquals(0, backing.clears)
            val restarted = Fixture(backing)
            restarted.wave(0f, 1f, 2_000)
            assertEquals(previous, backing.row)
        }
    }

    @Test fun writeFailureMaintainsPriorRowAndPriorOperationalInterpretation() {
        val previous = row(binary())
        val backing = Backing(previous)
        val fixture = Fixture(backing)
        fixture.toReview(2f, 100f)
        backing.failWrite = true
        fixture.at(12_000)
        assertTrue(fixture.runtime.localAction("save"))
        assertEquals("failed", fixture.status().getString("stage"))
        assertEquals(previous, backing.row)
        assertEquals(1, backing.writes)
        assertEquals(0, backing.clears)
        fixture.wave(0f, 1f, 13_000)
        assertEquals(previous, backing.row)
    }

    @Test fun unseenPanelCannotBeginAndWrongBrowserCannotOwnOrCancelSession() {
        val fixture = Fixture(Backing())
        assertTrue(fixture.runtime.start())
        val session = fixture.status().getString("sessionId")
        assertFalse(fixture.runtime.localAction("begin"))
        assertFalse(fixture.runtime.heartbeat("wrong-session"))
        assertFalse(fixture.runtime.cancel("wrong-session"))
        assertEquals("intro", fixture.status().getString("stage"))
        fixture.runtime.visible()
        fixture.runtime.observe(40f, fixture.now, true, live = false, calibrationLive = true)
        assertTrue(fixture.runtime.localAction("begin"))
        assertTrue(fixture.runtime.heartbeat(session))
    }

    @Test fun staleAndFreshProbeReadsHaveDifferentCalibrationEligibilityButNeverWake() {
        val fixture = Fixture(Backing())
        fixture.begin()
        fixture.at(2_210)
        fixture.runtime.observe(0f, fixture.now, true, live = false, calibrationLive = false)
        fixture.at(3_010)
        fixture.runtime.tick(fixture.now, true)
        assertEquals("clear", fixture.status().getString("stage"))
        fixture.at(3_100)
        fixture.runtime.observe(0f, fixture.now, true, live = false, calibrationLive = true)
        fixture.at(3_900)
        fixture.runtime.tick(fixture.now, true)
        assertEquals("near", fixture.status().getString("stage"))

        val operational = Fixture(Backing(row(binary())))
        operational.at(2_000)
        operational.runtime.observe(0f, operational.now, true)
        operational.at(2_200)
        operational.runtime.tick(operational.now, true)
        operational.at(2_800)
        operational.runtime.observe(1f, operational.now, true)
        operational.at(3_100)
        assertFalse(operational.runtime.observe(0f, operational.now, true, live = false, calibrationLive = true).deliberateGesture)
        operational.at(3_300)
        assertFalse(operational.runtime.tick(operational.now, true).deliberateGesture)
    }

    @Test fun rejectedCurrentGestureReleasesCooldownButOldCompletionCannotReleaseNewerGesture() {
        val fixture = Fixture(Backing(row(binary())))
        fixture.wave(0f, 1f, 2_000)
        val firstToken = fixture.runtime.gestureToken()
        fixture.runtime.completeGesture(firstToken, accepted = false)
        fixture.at(3_800); fixture.runtime.observe(1f, fixture.now, true)
        fixture.at(4_100); fixture.runtime.observe(0f, fixture.now, true)
        fixture.at(4_250); assertTrue(fixture.runtime.tick(fixture.now, true).deliberateGesture)
        val secondToken = fixture.runtime.gestureToken()
        assertTrue(secondToken > firstToken)
        fixture.runtime.completeGesture(firstToken, accepted = false)
        fixture.at(4_800); fixture.runtime.observe(1f, fixture.now, true)
        fixture.at(5_100); fixture.runtime.observe(0f, fixture.now, true)
        fixture.at(5_250); assertFalse(fixture.runtime.tick(fixture.now, true).deliberateGesture)
    }

    @Test fun retryRequiresFreshProbeBeforeQuietSourceCanBeCapturedAgain() {
        val fixture = Fixture(Backing())
        fixture.begin(0f)
        fixture.at(1_100)
        assertTrue(fixture.runtime.localAction("cancel"))
        fixture.at(1_200)
        assertTrue(fixture.runtime.localAction("retry"))
        assertFalse(fixture.runtime.localAction("begin"))
        fixture.at(2_400); fixture.runtime.tick(fixture.now, true)
        fixture.at(3_200); fixture.runtime.tick(fixture.now, true)
        assertEquals("intro", fixture.status().getString("stage"))
        fixture.runtime.observe(0f, fixture.now, true, live = false, calibrationLive = true)
        assertTrue(fixture.runtime.localAction("begin"))
        fixture.at(4_400); fixture.runtime.tick(fixture.now, true)
        fixture.at(5_200); fixture.runtime.tick(fixture.now, true)
        assertEquals("near", fixture.status().getString("stage"))
    }

    @Test fun queuedSensorReceiptOlderThanLocalBeginUsesSerializedProcessingTime() {
        val fixture = Fixture(Backing())
        fixture.begin()
        assertEquals("clear", fixture.status().getString("stage"))
        fixture.runtime.observe(40f, fixture.now - 1, true, live = false, calibrationLive = true)
        assertEquals("clear", fixture.status().getString("stage"))
        assertTrue(fixture.runtime.active())
        assertFalse(fixture.status().getString("message").contains("timing was invalid"))
        fixture.at(2_210); fixture.runtime.tick(fixture.now, true)
        fixture.at(3_010); fixture.runtime.tick(fixture.now, true)
        assertEquals("near", fixture.status().getString("stage"))
    }

    @Test fun introAcquisitionWaitRequiresFreshEvidenceAndPreservesPreviousRow() {
        val previous = row(binary())
        val fixture = Fixture(Backing(previous))
        fixture.runtime.observe(0f, fixture.now, true)
        fixture.at(1_200); fixture.runtime.tick(fixture.now, true)
        assertTrue(fixture.runtime.isWaveReady())
        assertTrue(fixture.runtime.start())
        fixture.session = fixture.status().getString("sessionId")
        fixture.runtime.visible()
        assertEquals("source_unavailable", fixture.status().getString("health"))
        assertFalse(fixture.runtime.localAction("begin"))
        fixture.runtime.sourceUnavailable(fixture.now)
        assertEquals("intro", fixture.status().getString("stage"))
        assertTrue(fixture.runtime.active())
        fixture.runtime.observe(0f, fixture.now, true, live = false, calibrationLive = false)
        assertEquals("source_unavailable", fixture.status().getString("health"))
        assertFalse(fixture.runtime.localAction("begin"))
        assertFalse(fixture.runtime.isWaveReady())
        fixture.at(1_300)
        assertFalse(fixture.runtime.observe(0f, fixture.now, true, live = false, calibrationLive = true).deliberateGesture)
        assertEquals("healthy", fixture.status().getString("health"))
        assertTrue(fixture.runtime.localAction("begin"))
        fixture.at(2_500); fixture.runtime.tick(fixture.now, true)
        fixture.at(3_300); fixture.runtime.tick(fixture.now, true)
        assertEquals("near", fixture.status().getString("stage"))
        assertEquals(previous, fixture.backing.row)
        assertEquals(0, fixture.backing.writes)
    }

    @Test fun introWaitingCancelTimeoutAndMalformedSampleKeepDurableCalibration() {
        for (ending in listOf("cancel", "timeout", "malformed")) {
            val previous = row(binary())
            val fixture = Fixture(Backing(previous))
            assertTrue(fixture.runtime.start())
            fixture.session = fixture.status().getString("sessionId")
            fixture.runtime.visible()
            fixture.runtime.sourceUnavailable(fixture.now)
            when (ending) {
                "cancel" -> fixture.runtime.cancel(fixture.session)
                "timeout" -> {
                    fixture.at(1_000 + ProximityCalibrationRuntime.SESSION_TIMEOUT_MS)
                    fixture.runtime.tick(fixture.now, true)
                }
                "malformed" -> fixture.runtime.observe(Float.NaN, fixture.now, true)
            }
            assertFalse(fixture.runtime.active())
            assertFalse(fixture.runtime.isWaveReady())
            assertEquals(previous, fixture.backing.row)
            assertEquals(0, fixture.backing.writes)
        }
    }

    private class Fixture(
        val backing: Backing,
        source: String = SOURCE,
        profile: String = PROFILE,
    ) {
        var now = 1_000L
        val runtime = ProximityCalibrationRuntime(source, profile, ranged(), Store(backing), elapsed = { now }, wall = { 1_800_000_000_000L + now })
        var session = ""
        fun status() = JSONObject(runtime.json())
        fun at(value: Long) {
            now = value
            if (session.isNotEmpty()) {
                runtime.heartbeat(session)
                runtime.visible()
            }
        }
        fun begin(probeRaw: Float = 40f) {
            assertTrue(runtime.start())
            session = status().getString("sessionId")
            assertTrue(runtime.visible())
            runtime.observe(probeRaw, now, true, live = false, calibrationLive = true)
            at(1_010)
            assertTrue(runtime.localAction("begin"))
        }
        fun toReview(clear: Float, near: Float) {
            begin(clear)
            runtime.observe(clear, now, true, live = false, calibrationLive = true)
            at(2_210); runtime.tick(now, true)
            at(3_010); runtime.tick(now, true)
            assertEquals("near", status().getString("stage"))
            at(4_310); runtime.observe(near, now, true)
            at(5_110); runtime.tick(now, true)
            assertEquals("return_clear", status().getString("stage"))
            at(5_210); runtime.observe(clear, now, true)
            at(6_010); runtime.tick(now, true)
            assertEquals("waves", status().getString("stage"))
            for (edge in listOf(7_000L, 9_000L, 11_000L)) {
                at(edge); assertFalse(runtime.observe(near, now, true).deliberateGesture)
                at(edge + 400); assertFalse(runtime.observe(clear, now, true).deliberateGesture)
                at(edge + 550); assertFalse(runtime.tick(now, true).deliberateGesture)
            }
            assertEquals("review", status().getString("stage"))
            assertEquals(3, status().getInt("acceptedGestures"))
        }
        fun wave(clear: Float, near: Float, time: Long) {
            at(time); assertFalse(runtime.observe(clear, now, true).deliberateGesture)
            at(time + 200); assertFalse(runtime.tick(now, true).deliberateGesture)
            assertTrue(runtime.isWaveReady())
            at(time + 800); assertFalse(runtime.observe(near, now, true).deliberateGesture)
            at(time + 1_100); assertFalse(runtime.observe(clear, now, true).deliberateGesture)
            at(time + 1_250); assertTrue(runtime.tick(now, true).deliberateGesture)
            at(time + 1_251); assertFalse(runtime.tick(now, true).deliberateGesture)
        }
    }

    private class Backing(var row: EntityCatalogStore.ProximityModelRow? = null) {
        var writes = 0
        var clears = 0
        var closes = 0
        var failWrite = false
    }

    private class Store(private val backing: Backing) : ProximityModelStore {
        // Deliberately return mismatched rows too: the runtime itself must validate their fingerprint.
        override fun readProximityModel(fingerprint: String) = backing.row
        override fun writeProximityBatch(
            model: EntityCatalogStore.ProximityModelRow,
            rollups: List<EntityCatalogStore.ProximityRollupRow>,
            episodes: List<EntityCatalogStore.ProximityEpisodeRow>,
            now: Long,
        ) {
            backing.writes++
            assertTrue(rollups.isEmpty())
            assertTrue(episodes.isEmpty())
            if (backing.failWrite) throw IllegalStateException("atomic transaction failed")
            backing.row = model
        }
        override fun clearProximityLearning(fingerprint: String) { backing.clears++; backing.row = null }
        override fun close() { backing.closes++ }
    }

    companion object {
        private const val SOURCE = "android-hal|same-misleading-range-metadata"
        private const val PROFILE = "profile-r1"
        private fun ranged() = Calibration(mode = Mode.RANGED, clearRaw = 40f, nearRaw = 5f)
        private fun binary() = Calibration(mode = Mode.BINARY, clearRaw = 0f, nearRaw = 1f)
        private fun row(value: Calibration) = EntityCatalogStore.ProximityModelRow(
            ProximityCalibrationRuntime.fingerprint("$PROFILE|$SOURCE"), ProximityCalibrationRuntime.STORAGE_VERSION,
            "explicit-calibration-v1", ProximityCalibrationRuntime.encode(value), true, 1_800_000_000_000L,
        )
    }
}
