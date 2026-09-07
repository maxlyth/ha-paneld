package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import io.github.maxlyth.hapaneld.device.profile.ProfileProximityCalibration
import io.github.maxlyth.hapaneld.device.profile.ProfileWaveCalibration
import io.github.maxlyth.hapaneld.sensors.ProximityCalibrationEngine.Calibration
import io.github.maxlyth.hapaneld.sensors.ProximityCalibrationEngine.Mode
import io.github.maxlyth.hapaneld.sensors.ProximityCalibrationEngine.WaveCalibration
import io.github.maxlyth.hapaneld.sensors.ProximityCalibrationEngine.WavePattern
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Drives the production runtime, sparse live edges and its injected atomic model-store boundary. */
class ProximityCalibrationRuntimeTest {
    @Test fun ambientSamplesGesturesTickAndCloseNeverWriteCalibration() {
        val fixture = Fixture()
        repeat(10) { fixture.operationalWave(legacy().effectiveWave()!!) }
        fixture.runtime.close()
        assertEquals(0, fixture.backing.writes)
        assertEquals(0, fixture.backing.clears)
        assertNull(fixture.backing.row)
        assertEquals(1, fixture.backing.closes)
    }

    @Test fun completedWizardCommitsOnceAndRestartRestoresSameFingerprint() {
        for ((clear, near) in listOf(0f to 1f, 1f to 0f, 100f to 50f)) {
            val backing = Backing()
            val fixture = Fixture(backing, pattern = WavePattern.DOUBLE)
            fixture.toReview(clear, near, near)
            assertEquals(0, backing.writes)
            assertFalse(fixture.runtime.isWaveReady())
            assertTrue(fixture.status().getBoolean("canSave"))
            fixture.advance(1)
            assertTrue(fixture.runtime.localAction("save"))
            assertEquals("saved", fixture.status().getString("stage"))
            assertEquals("user", fixture.status().getString("calibrationSource"))
            assertEquals(1, backing.writes)
            val committed = assertRow(backing)
            assertEquals(ProximityCalibrationRuntime.STORAGE_VERSION, committed.algorithmVersion)
            val model = assertDecoded(committed.snapshotJson)
            assertEquals(2, model.version)
            assertEquals(WavePattern.DOUBLE, model.wave!!.pattern)
            assertFalse(fixture.runtime.localAction("save"))
            fixture.runtime.close()
            val restarted = Fixture(backing)
            assertEquals("user", restarted.status().getString("calibrationSource"))
            assertFalse(restarted.runtime.isWaveReady())
            restarted.operationalWave(model.wave!!)
            assertEquals(committed, backing.row)
            assertEquals(1, backing.writes)
        }
    }

    @Test fun legacyCalibrationIsReadWithoutMigrationWriteAndRemainsOperational() {
        val previous = row(legacy(), ProximityCalibrationRuntime.LEGACY_STORAGE_VERSION)
        val fixture = Fixture(Backing(previous))
        assertEquals("user", fixture.status().getString("calibrationSource"))
        fixture.operationalWave(legacy().effectiveWave()!!)
        fixture.runtime.close()
        assertEquals(previous, fixture.backing.row)
        assertEquals(0, fixture.backing.writes)
        assertEquals(0, fixture.backing.clears)
    }

    @Test fun profileRevisionAndSourceIdentityChangesExcludePreviousOverride() {
        for ((source, profile) in listOf(SOURCE to "profile-r2", "new-source" to PROFILE)) {
            val previous = row(binaryLegacy())
            val fixture = Fixture(Backing(previous), source = source, profile = profile)
            assertEquals("profile", fixture.status().getString("calibrationSource"))
            fixture.operationalWave(legacy().effectiveWave()!!)
            assertEquals(previous, fixture.backing.row)
            assertEquals(0, fixture.backing.writes)
        }
    }

    @Test fun passiveMalformedAndUnreadyRowsCannotOverrideProfileDefaults() {
        val good = row(binaryLegacy())
        val badRows = listOf(good.copy(algorithmVersion = 4), good.copy(snapshotJson = "not-json"),
            good.copy(snapshotJson = "{\"version\":1}"), good.copy(ready = false),
            good.copy(snapshotJson = good.snapshotJson.replace("\"version\":1", "\"version\":3")),
            row(presenceOnly(), ProximityCalibrationRuntime.LEGACY_STORAGE_VERSION))
        for (bad in badRows) {
            val fixture = Fixture(Backing(bad))
            assertEquals("profile", fixture.status().getString("calibrationSource"))
            fixture.operationalWave(legacy().effectiveWave()!!)
            fixture.runtime.close()
            assertEquals(bad, fixture.backing.row)
            assertEquals(0, fixture.backing.writes)
            assertEquals(0, fixture.backing.clears)
        }
    }

    @Test fun malformedBinaryWaveStoredRowFallsBackWithoutStartupFailureOrRewrite() {
        val malformed = JSONObject(ProximityCalibrationRuntime.encode(binaryLegacy().copy(version = 2)))
            .put("wave", JSONObject().apply {
                put("pattern", "SINGLE"); put("clearRaw", 1); put("nearRaw", 2)
                put("nearEnter", .65); put("clearExit", .30)
                put("debounceMs", 150); put("clearArmMs", 700)
                put("minimumNearMs", 200); put("maximumNearMs", 4000)
                put("cooldownMs", 1000); put("maxInterWaveGapMs", 1800)
            }).toString()
        assertNull(ProximityCalibrationRuntime.decode(malformed))
        val previous = row(binaryLegacy()).copy(snapshotJson = malformed)
        val fixture = Fixture(Backing(previous))
        assertEquals("profile", fixture.status().getString("calibrationSource"))
        fixture.operationalWave(legacy().effectiveWave()!!)
        fixture.runtime.close()
        assertEquals(previous, fixture.backing.row)
        assertEquals(0, fixture.backing.writes)
        assertEquals(0, fixture.backing.clears)
    }

    @Test fun cancelBrowserLossPanelLossSourceLossTimeoutAndRestartPreservePreviousRow() {
        for (ending in listOf("cancel", "browser", "panel", "source", "timeout", "restart", "malformed")) {
            val previous = row(binaryLegacy(), ProximityCalibrationRuntime.LEGACY_STORAGE_VERSION)
            val fixture = Fixture(Backing(previous))
            fixture.toReview(100f, 50f, 10f)
            when (ending) {
                "cancel" -> assertTrue(fixture.runtime.cancel(fixture.session))
                "browser" -> {
                    fixture.now += ProximityCalibrationRuntime.BROWSER_LEASE_MS + 1
                    fixture.runtime.visible(); fixture.runtime.tick(fixture.now, true)
                    assertTrue(fixture.status().getString("message").contains("browser disconnected"))
                }
                "panel" -> {
                    fixture.now += ProximityCalibrationRuntime.LOCAL_VISIBILITY_MS + 1
                    fixture.runtime.heartbeat(fixture.session); fixture.runtime.tick(fixture.now, true)
                    assertTrue(fixture.status().getString("message").contains("screen closed"))
                }
                "source" -> fixture.runtime.sourceUnavailable(fixture.now)
                "timeout" -> {
                    fixture.at(1_000 + ProximityCalibrationRuntime.SESSION_TIMEOUT_MS)
                    fixture.runtime.tick(fixture.now, true)
                    assertTrue(fixture.status().getString("message").contains("timed out"))
                }
                "restart" -> fixture.runtime.close()
                "malformed" -> fixture.sample(Float.NaN)
            }
            assertFalse(ending, fixture.runtime.active())
            assertEquals(previous, fixture.backing.row)
            assertEquals(0, fixture.backing.writes)
            assertEquals(0, fixture.backing.clears)
            Fixture(fixture.backing).operationalWave(binaryLegacy().effectiveWave()!!)
        }
    }

    @Test fun writeFailureMaintainsPriorRowAndPriorOperationalInterpretation() {
        val previous = row(binaryLegacy())
        val fixture = Fixture(Backing(previous))
        fixture.toReview(100f, 50f, 10f)
        fixture.backing.failWrite = true
        assertTrue(fixture.runtime.localAction("save"))
        assertEquals("failed", fixture.status().getString("stage"))
        assertEquals(previous, fixture.backing.row)
        assertEquals(1, fixture.backing.writes)
        fixture.operationalWave(binaryLegacy().effectiveWave()!!)
        assertEquals(previous, fixture.backing.row)
    }

    @Test fun unseenPanelCannotBeginAndWrongBrowserCannotOwnOrCancelSession() {
        val fixture = Fixture()
        assertTrue(fixture.runtime.start())
        fixture.session = fixture.status().getString("sessionId")
        assertFalse(fixture.runtime.localAction("begin"))
        assertFalse(fixture.runtime.heartbeat("wrong-session"))
        assertFalse(fixture.runtime.cancel("wrong-session"))
        fixture.runtime.visible()
        assertFalse(fixture.runtime.localAction("begin"))
        fixture.sample(100f, live = false, capture = true)
        assertTrue(fixture.runtime.localAction("begin"))
        assertTrue(fixture.runtime.heartbeat(fixture.session))
    }

    @Test fun introAcquisitionWaitRequiresFreshEvidenceAndPreservesPreviousRow() {
        val previous = row(legacy())
        val fixture = Fixture(Backing(previous))
        fixture.sample(100f); fixture.advance(200)
        assertTrue(fixture.runtime.isWaveReady())
        assertTrue(fixture.runtime.start())
        fixture.session = fixture.status().getString("sessionId")
        fixture.runtime.visible()
        assertFalse(fixture.runtime.localAction("begin"))
        fixture.runtime.sourceUnavailable(fixture.now)
        assertEquals("intro", fixture.stage())
        fixture.sample(100f, live = false, capture = false)
        assertEquals("source_unavailable", fixture.status().getString("health"))
        assertFalse(fixture.runtime.localAction("begin"))
        fixture.sample(100f, live = false, capture = true)
        assertTrue(fixture.runtime.localAction("begin"))
        assertEquals(previous, fixture.backing.row)
        assertEquals(0, fixture.backing.writes)
    }

    @Test fun retryRequiresFreshProbeBeforeQuietSourceCanBeCapturedAgain() {
        val fixture = Fixture()
        fixture.begin(100f)
        assertTrue(fixture.runtime.localAction("cancel"))
        assertTrue(fixture.runtime.localAction("retry"))
        assertFalse(fixture.runtime.localAction("begin"))
        fixture.advance(4_000)
        assertEquals("intro", fixture.stage())
        fixture.sample(100f, live = false, capture = true)
        assertTrue(fixture.runtime.localAction("begin"))
        fixture.capture(100f)
        assertEquals("near", fixture.stage())
    }

    @Test fun queuedSensorReceiptOlderThanLocalBeginUsesSerializedProcessingTime() {
        val fixture = Fixture()
        fixture.begin(100f)
        fixture.advance(10)
        fixture.runtime.observe(100f, 900, true)
        assertTrue(fixture.runtime.active())
        assertEquals("clear", fixture.stage())
        assertEquals("healthy", fixture.status().getString("health"))
    }

    @Test fun staleAndFreshProbeReadsHaveDifferentCalibrationEligibilityButNeverWake() {
        val fixture = Fixture()
        fixture.begin(100f)
        fixture.sample(100f, live = false, capture = false)
        fixture.advance(4_000)
        assertEquals("clear", fixture.stage())
        fixture.sample(100f, live = false, capture = true)
        fixture.advance(ProximityCalibrationEngine.CAPTURE_HOLD_MS)
        assertEquals("near", fixture.stage())
        val operational = Fixture(Backing(row(binaryLegacy())))
        operational.sample(0f); operational.advance(200)
        operational.advance(800, 1f)
        operational.advance(300)
        assertFalse(operational.sample(0f, live = false, capture = true).deliberateGesture)
        assertFalse(operational.advance(200).deliberateGesture)
    }

    @Test fun rejectedCurrentGestureReleasesCooldownButOldCompletionCannotReleaseNewerGesture() {
        val model = binaryLegacy().copy(cooldownMs = 2_000)
        val fixture = Fixture(Backing(row(model)))
        fixture.operationalWave(model.effectiveWave()!!)
        val first = fixture.runtime.gestureToken()
        fixture.runtime.completeGesture(first, false)
        fixture.advance(550, 1f); fixture.advance(300, 0f)
        assertTrue(fixture.advance(150).deliberateGesture)
        val second = fixture.runtime.gestureToken()
        assertTrue(second > first)
        fixture.runtime.completeGesture(first, false)
        fixture.advance(550, 1f); fixture.advance(300, 0f)
        assertFalse(fixture.advance(150).deliberateGesture)
    }

    @Test fun presenceOnlyApproachAndWaveOnlyGesturePublishIndependentCapabilities() {
        val presence = Fixture(Backing(row(presenceOnly())))
        presence.sample(100f); presence.advance(200)
        assertTrue(presence.runtime.isLearnedSignal())
        assertTrue(presence.runtime.isPresenceReady())
        assertFalse(presence.runtime.isWaveReady())
        presence.advance(800, 50f)
        val approach = presence.advance(200)
        assertTrue(approach.presenceApproach)
        assertTrue(presence.runtime.isPresenceNear())
        assertEquals(true, approach.near)
        assertFalse(approach.deliberateGesture)
        assertFalse(presence.status().getBoolean("waveSupported"))

        val waveOnly = presenceOnly().copy(presenceSupported = false, wave = WaveCalibration(clearRaw = 100f, nearRaw = 10f))
        val wave = Fixture(Backing(row(waveOnly)))
        wave.operationalWave(waveOnly.wave!!)
        assertFalse(wave.runtime.isLearnedSignal())
        assertFalse(wave.runtime.isPresenceReady())
        assertFalse(wave.runtime.isPresenceNear())
        assertTrue(wave.runtime.isWaveReady())
        assertFalse(wave.status().getBoolean("presenceSupported"))
        assertTrue(wave.status().isNull("near"))
        assertTrue(wave.status().isNull("level"))
    }

    @Test fun automaticJourneyExposesTimedCuesAndCanSavePresenceWithoutWave() {
        val fixture = Fixture()
        fixture.toReview(100f, 50f, null)
        assertEquals("validated", fixture.status().getString("presenceStatus"))
        assertEquals("not_distinguishable", fixture.status().getString("waveStatus"))
        assertTrue(fixture.status().getBoolean("canSave"))
        assertFalse(fixture.runtime.isPresenceReady())
        assertTrue(fixture.runtime.localAction("save"))
        val saved = assertDecoded(assertRow(fixture.backing).snapshotJson)
        assertTrue(saved.presenceSupported)
        assertNull(saved.wave)
        assertEquals(2, saved.version)
    }

    @Test fun candidatePresenceNeverPublishesBeforeAtomicSave() {
        val previous = row(binaryLegacy())
        val fixture = Fixture(Backing(previous))
        fixture.toReview(100f, 50f, 10f)
        val provisional = fixture.sample(10f)
        assertEquals(ProximityReportGate.NONE, provisional.reportMask)
        assertNull(provisional.near)
        assertNull(provisional.normalizedLevel)
        assertFalse(provisional.presenceApproach)
        assertFalse(provisional.deliberateGesture)
        assertEquals(previous, fixture.backing.row)
        assertEquals(0, fixture.backing.writes)
        assertTrue(fixture.runtime.cancel(fixture.session))
        fixture.operationalWave(binaryLegacy().effectiveWave()!!)
    }

    @Test fun codecRoundTripsLegacyPresenceWaveAndCombinedCapabilitiesWithoutImplicitUpgrade() {
        val wave = WaveCalibration(pattern = WavePattern.DOUBLE, clearRaw = 100f, nearRaw = 10f, maxInterWaveGapMs = 2100)
        val models = listOf(legacy(), presenceOnly(), presenceOnly().copy(wave = wave),
            presenceOnly().copy(presenceSupported = false, wave = wave))
        for (model in models) assertEquals(model, ProximityCalibrationRuntime.decode(ProximityCalibrationRuntime.encode(model)))
        val legacyJson = JSONObject(ProximityCalibrationRuntime.encode(legacy()))
        assertFalse(legacyJson.has("wave"))
        assertFalse(legacyJson.has("presenceSupported"))
        assertNotNull(assertDecoded(legacyJson.toString()).effectiveWave())
        assertNull(assertDecoded(ProximityCalibrationRuntime.encode(presenceOnly())).effectiveWave())
    }

    @Test fun codecRejectsMalformedWaveAndNeitherCapabilityInsteadOfEnablingWake() {
        val good = JSONObject(ProximityCalibrationRuntime.encode(presenceOnly().copy(wave = WaveCalibration(clearRaw = 100f, nearRaw = 10f))))
        fun changed(edit: (JSONObject) -> Unit): String = JSONObject(good.toString()).apply(edit).toString()
        val malformed = listOf(
            changed { it.put("version", 3) }, changed { it.put("version", "2") },
            changed { it.put("presenceSupported", "true") }, changed { it.put("presenceSupported", false); it.put("wave", JSONObject.NULL) },
            changed { it.getJSONObject("wave").put("nearRaw", "10") },
            changed { it.getJSONObject("wave").put("clearRaw", 10); it.getJSONObject("wave").put("nearRaw", 100) },
            changed { it.getJSONObject("wave").put("pattern", "TRIPLE") },
            changed { it.getJSONObject("wave").put("maxInterWaveGapMs", 299) },
            changed { it.getJSONObject("wave").put("debounceMs", 5001) },
            changed { it.getJSONObject("wave").remove("clearRaw") },
            changed { it.put("nearRaw", 1e100) },
        )
        for (raw in malformed) assertNull(raw, ProximityCalibrationRuntime.decode(raw))
    }

    @Test fun profileVersionControlsLegacyWaveAndVersionTwoOptionalCapabilities() {
        val old = ProfileProximityCalibration(1, "ranged", 100f, 50f, "Fixture")
        assertEquals(1, ProximityCalibrationRuntime.fromProfile(old).version)
        assertNotNull(ProximityCalibrationRuntime.fromProfile(old).effectiveWave())
        assertNull(ProximityCalibrationRuntime.fromProfile(old.copy(formatVersion = 2)).effectiveWave())
        val wave = old.copy(formatVersion = 2, presenceSupported = false,
            wave = ProfileWaveCalibration("double", 100f, 10f))
        val mapped = ProximityCalibrationRuntime.fromProfile(wave)
        assertFalse(mapped.presenceSupported)
        assertEquals(WavePattern.DOUBLE, ProximityCalibrationRuntime.profileWavePattern(wave))
        assertEquals(WavePattern.SINGLE, ProximityCalibrationRuntime.profileWavePattern(null))
        assertEquals(WavePattern.SINGLE, ProximityCalibrationRuntime.profileWavePattern(old))
        assertEquals(WavePattern.DOUBLE, mapped.wave!!.pattern)
        assertEquals(10f, mapped.wave!!.nearRaw, 0f)
    }

    private class Fixture(
        val backing: Backing = Backing(), source: String = SOURCE, profile: String = PROFILE,
        private val pattern: WavePattern = WavePattern.SINGLE,
    ) {
        var now = 1_000L
        val runtime = ProximityCalibrationRuntime(source, profile, legacy(), Store(backing),
            elapsed = { now }, wall = { 1_800_000_000_000L + now }, requestedWavePattern = pattern)
        var session = ""
        fun status() = JSONObject(runtime.json())
        fun stage() = status().getString("stage")
        fun at(value: Long) {
            now = value
            if (session.isNotEmpty()) { runtime.heartbeat(session); runtime.visible() }
        }
        fun sample(raw: Float, live: Boolean = true, capture: Boolean = live) = runtime.observe(raw, now, true, live, capture)
        fun advance(delta: Long, raw: Float? = null): ProximityCalibrationRuntime.Decision {
            at(now + delta)
            return if (raw == null) runtime.tick(now, true) else sample(raw)
        }
        fun begin(clear: Float) {
            assertTrue(runtime.start())
            session = status().getString("sessionId")
            assertTrue(runtime.visible())
            sample(clear, live = false, capture = true)
            assertTrue(runtime.localAction("begin"))
            assertEquals("clear", stage())
            assertEquals("move_away", status().getString("cue"))
            assertEquals(ProximityCalibrationEngine.COUNTDOWN_MS, status().getLong("cueDurationMs"))
            assertTrue(status().getLong("cueRemainingMs") > 0)
        }
        fun capture(raw: Float) {
            advance(ProximityCalibrationEngine.COUNTDOWN_MS, raw)
            advance(ProximityCalibrationEngine.CAPTURE_HOLD_MS)
        }
        fun toReview(clear: Float, near: Float, hand: Float?) {
            begin(clear)
            capture(clear); assertEquals("near", stage())
            capture(near); assertEquals("return_clear", stage())
            capture(clear); assertEquals("wave_baseline", stage())
            val waveClear = if (pattern == WavePattern.DOUBLE) clear else near
            capture(waveClear); assertEquals("wave_capture", stage())
            if (hand == null) {
                advance(ProximityCalibrationEngine.WAVE_PHASE_TIMEOUT_MS)
            } else {
                advance(ProximityCalibrationEngine.COUNTDOWN_MS, hand)
                advance(400, waveClear)
                assertEquals("waves", stage())
                advance(ProximityCalibrationEngine.COUNTDOWN_MS, waveClear)
                advance(200)
                repeat(3) {
                    repeat(if (pattern == WavePattern.DOUBLE) 2 else 1) {
                        assertFalse(advance(800, hand).deliberateGesture)
                        assertFalse(advance(400, waveClear).deliberateGesture)
                        assertFalse(advance(150).deliberateGesture)
                    }
                }
            }
            assertEquals("review", stage())
        }
        fun operationalWave(wave: WaveCalibration) {
            assertFalse(advance(1_500, wave.clearRaw).deliberateGesture)
            advance(200)
            assertTrue(runtime.isWaveReady())
            repeat(if (wave.pattern == WavePattern.DOUBLE) 2 else 1) { index ->
                assertFalse(advance(800, wave.nearRaw).deliberateGesture)
                assertFalse(advance(300, wave.clearRaw).deliberateGesture)
                assertEquals(index == (if (wave.pattern == WavePattern.DOUBLE) 1 else 0), advance(150).deliberateGesture)
            }
            assertFalse(advance(1).deliberateGesture)
        }
    }

    private class Backing(var row: EntityCatalogStore.ProximityModelRow? = null) {
        var writes = 0; var clears = 0; var closes = 0; var failWrite = false
    }
    private class Store(private val backing: Backing) : ProximityModelStore {
        // Deliberately return mismatched rows too: the runtime must validate their fingerprint.
        override fun readProximityModel(fingerprint: String) = backing.row
        override fun writeProximityBatch(model: EntityCatalogStore.ProximityModelRow,
            rollups: List<EntityCatalogStore.ProximityRollupRow>, episodes: List<EntityCatalogStore.ProximityEpisodeRow>, now: Long) {
            backing.writes++
            assertTrue(rollups.isEmpty()); assertTrue(episodes.isEmpty())
            if (backing.failWrite) throw IllegalStateException("atomic transaction failed")
            backing.row = model
        }
        override fun clearProximityLearning(fingerprint: String) { backing.clears++; backing.row = null }
        override fun close() { backing.closes++ }
    }
    companion object {
        private const val SOURCE = "android-hal|same-misleading-range-metadata"
        private const val PROFILE = "profile-r1"
        private fun legacy() = Calibration(version = 1, mode = Mode.RANGED, clearRaw = 100f, nearRaw = 50f)
        private fun binaryLegacy() = Calibration(version = 1, mode = Mode.BINARY, clearRaw = 0f, nearRaw = 1f)
        private fun presenceOnly() = Calibration(mode = Mode.RANGED, clearRaw = 100f, nearRaw = 50f)
        private fun row(value: Calibration, algorithm: Int = ProximityCalibrationRuntime.STORAGE_VERSION) = EntityCatalogStore.ProximityModelRow(
            ProximityCalibrationRuntime.fingerprint("$PROFILE|$SOURCE"), algorithm,
            "explicit-calibration-v${value.version}", ProximityCalibrationRuntime.encode(value), true, 1_800_000_000_000L)
        private fun assertRow(backing: Backing): EntityCatalogStore.ProximityModelRow {
            assertNotNull(backing.row)
            return backing.row!!
        }
        private fun assertDecoded(raw: String): Calibration {
            val result = ProximityCalibrationRuntime.decode(raw)
            assertNotNull(raw, result)
            return result!!
        }
    }
}
