package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class ProximityLearningRuntimePolicyTest {
    @Test fun guidedWakeEvidenceExpiresOnEveryEngineEvidenceEpochChange() {
        assertFalse(ProximityLearningRuntime.shouldInvalidateGuidedWakeEvidence(4L, 4L))
        assertTrue(ProximityLearningRuntime.shouldInvalidateGuidedWakeEvidence(4L, 5L))
    }

    @Test fun synchronousMarkerFailureFallsBackWithoutCrashingSensorIngestion() {
        var fallbacks = 0
        assertTrue(ProximityLearningRuntime.establishWakeInvalidationAuthority(
            mark = { false },
            fallback = { fallbacks++; true },
        ))
        assertEquals(1, fallbacks)

        assertFalse(ProximityLearningRuntime.establishWakeInvalidationAuthority(
            mark = { throw IllegalStateException("preferences unavailable") },
            fallback = { false },
        ))
    }

    @Test fun journalAcknowledgementRejectsTokenMismatchAndClearFailure() {
        assertTrue(ProximityLearningRuntime.canAcknowledgeWakeInvalidation(true, "same-token"))
        assertTrue(ProximityLearningRuntime.canAcknowledgeWakeInvalidation(false, null))
        assertFalse(ProximityLearningRuntime.canAcknowledgeWakeInvalidation(false, "newer-or-uncleared-token"))
    }

    @Test fun durableFallbackRetainsRangeButCannotRetainWakeReadiness() {
        val fingerprint = ProximityLearningRuntime.fingerprint("hal:8:durable-fallback")
        val row = EntityCatalogStore.ProximityModelRow(
            fingerprint = fingerprint,
            algorithmVersion = ProximityLearningRuntime.ALGORITHM_VERSION,
            behaviorSignature = "GRADED:NEAR_IS_LOWER",
            snapshotJson = JSONObject().apply {
                put("schema", 1)
                put("guidedReady", true)
                put("engineSchema", 1)
                put("farRaw", 100.0)
                put("nearRaw", 0.0)
                put("noise", 1.0)
                put("mode", "GRADED")
                put("polarity", "NEAR_IS_LOWER")
                put("completedExcursions", 8)
                put("deliberateExamples", 5)
            }.toString(),
            ready = true,
            updatedAt = 1L,
        )

        val sanitized = ProximityLearningRuntime.withoutWakeEvidence(row, updatedAt = 2L)
        val json = JSONObject(sanitized.snapshotJson)

        assertEquals(row.fingerprint, sanitized.fingerprint)
        assertEquals(row.behaviorSignature, sanitized.behaviorSignature)
        assertTrue(sanitized.ready)
        assertEquals(2L, sanitized.updatedAt)
        assertFalse(json.getBoolean("guidedReady"))
        assertEquals(0, json.getInt("deliberateExamples"))
        assertEquals(100.0, json.getDouble("farRaw"), 0.0)
        assertEquals(0.0, json.getDouble("nearRaw"), 0.0)

        val retry = ProximityLearningRuntime.withoutWakeEvidence(sanitized, updatedAt = 3L)
        val retryJson = JSONObject(retry.snapshotJson)
        assertFalse(retryJson.getBoolean("guidedReady"))
        assertEquals(0, retryJson.getInt("deliberateExamples"))
        assertEquals(3L, retry.updatedAt)
    }

    @Test fun algorithmEpochRejectsEveryOlderModelIdentity() {
        val source = "hal:8:vendor-proximity"
        val versionOneFingerprint = ProximityLearningRuntime.fingerprint(source, algorithmVersion = 1)
        val versionTwoFingerprint = ProximityLearningRuntime.fingerprint(source, algorithmVersion = 2)
        val versionThreeFingerprint = ProximityLearningRuntime.fingerprint(source, algorithmVersion = 3)
        val currentFingerprint = ProximityLearningRuntime.fingerprint(source)
        val versionOne = EntityCatalogStore.ProximityModelRow(
            fingerprint = versionOneFingerprint,
            algorithmVersion = 1,
            behaviorSignature = "GRADED:NEAR_IS_LOWER",
            snapshotJson = "{}",
            ready = true,
            updatedAt = 1L,
        )

        assertEquals(4, ProximityLearningRuntime.ALGORITHM_VERSION)
        assertFalse(versionOneFingerprint == currentFingerprint)
        assertFalse(versionTwoFingerprint == currentFingerprint)
        assertFalse(versionThreeFingerprint == currentFingerprint)
        assertFalse(ProximityLearningRuntime.isRestorableModel(versionOne, currentFingerprint))
        assertFalse(
            ProximityLearningRuntime.isRestorableModel(
                versionOne.copy(fingerprint = currentFingerprint),
                currentFingerprint,
            ),
        )
        assertFalse(
            ProximityLearningRuntime.isRestorableModel(
                versionOne.copy(fingerprint = currentFingerprint, algorithmVersion = 3),
                currentFingerprint,
            ),
        )
        assertTrue(
            ProximityLearningRuntime.isRestorableModel(
                versionOne.copy(fingerprint = currentFingerprint, algorithmVersion = 4),
                currentFingerprint,
            ),
        )
        assertFalse(
            ProximityLearningRuntime.isRestorableModel(
                versionOne.copy(fingerprint = versionTwoFingerprint, algorithmVersion = 2),
                currentFingerprint,
            ),
        )
        assertFalse(
            ProximityLearningRuntime.isRestorableModel(
                versionOne.copy(fingerprint = currentFingerprint, algorithmVersion = 4, ready = false),
                currentFingerprint,
            ),
        )
    }

    @Test fun fleetProjectionSuppressesOnlyFarSideLearnerNoise() {
        assertNull(ProximityLearningRuntime.levelForReport(null, null))
        assertNull(ProximityLearningRuntime.levelForReport(false, null))
        assertEquals(0, ProximityLearningRuntime.levelForReport(false, 0))
        assertEquals(0, ProximityLearningRuntime.levelForReport(false, 15))
        assertEquals(16, ProximityLearningRuntime.levelForReport(false, 16))
        assertEquals(4, ProximityLearningRuntime.levelForReport(true, 4))
    }

    @Test fun onlyEligibleUnrestoredLegacySeedsWarmTheLearner() {
        val seed = Any()

        assertEquals(seed, ProximityLearningRuntime.eligibleLegacySeed(restored = false, seed = seed, eligible = true))
        assertNull(ProximityLearningRuntime.eligibleLegacySeed(restored = true, seed = seed, eligible = true))
        assertNull(ProximityLearningRuntime.eligibleLegacySeed(restored = false, seed = seed, eligible = false))
        assertNull(ProximityLearningRuntime.eligibleLegacySeed(restored = false, seed = null, eligible = true))
    }

    @Test fun teachingWaitsForABaselineAndLabelsActiveDepartures() {
        assertFalse(ProximityLearningRuntime.canTeachDuring(ProximityLearningEngine.LearningStatus.LEARNING_FAR))
        assertFalse(ProximityLearningRuntime.canTeachDuring(ProximityLearningEngine.LearningStatus.RELEARNING))
        assertTrue(ProximityLearningRuntime.canTeachDuring(ProximityLearningEngine.LearningStatus.LEARNING_EXCURSION))
        assertTrue(ProximityLearningRuntime.canTeachDuring(ProximityLearningEngine.LearningStatus.VERIFYING_SEED))
        assertTrue(ProximityLearningRuntime.canTeachDuring(ProximityLearningEngine.LearningStatus.READY))
        assertEquals(
            ProximityLearningEngine.GuidedLabel.NONE,
            ProximityLearningRuntime.teachingObservationLabel(teaching = false),
        )
        assertEquals(
            ProximityLearningEngine.GuidedLabel.NEAR,
            ProximityLearningRuntime.teachingObservationLabel(teaching = true),
        )
    }

    @Test fun denseNumericTelemetryStaysSafelyBelowPointTwoPerSecondForFiveMinutes() {
        val gate = ProximityReportGate()
        var levelReports = 0
        var presenceReports = 0
        val startedAt = 10_000L
        val duration = 300_000L
        val quantizedLevels = intArrayOf(62, 74, 86, 100, 82, 70)

        for (now in startedAt..startedAt + duration step 50L) {
            val elapsed = now - startedAt
            val farPulse = elapsed % 1_000L < 200L
            val level = if (farPulse) 0 else quantizedLevels[((elapsed / 50L) % quantizedLevels.size).toInt()]
            val mask = gate.project(
                near = !farPulse,
                level = level,
                now = now,
                sparseReporting = false,
            )
            if (mask and ProximityReportGate.LEVEL != 0) levelReports++
            if (mask and ProximityReportGate.PRESENCE != 0) presenceReports++
        }

        val reportsPerSecond = levelReports.toDouble() / (duration / 1_000.0)
        val aggregateReportsPerSecond = (levelReports + presenceReports).toDouble() / (duration / 1_000.0)
        assertTrue("dense numeric rate was $reportsPerSecond/s", reportsPerSecond <= 0.20)
        assertTrue("dense aggregate rate was $aggregateReportsPerSecond/s", aggregateReportsPerSecond <= 0.20)
        assertTrue("fixture must cross more than one numeric window", levelReports >= 20)
        assertEquals("sub-250 ms pulses must not reverse presence", 2, presenceReports)
    }

    @Test fun availabilityAndMaturedDensePresenceEdgesAreImmediate() {
        val gate = ProximityReportGate()
        assertEquals(
            ProximityReportGate.BOTH,
            gate.project(near = true, level = 100, now = 9_000L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.NONE,
            gate.project(near = false, level = 0, now = 10_000L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.NONE,
            gate.project(near = false, level = 0, now = 10_249L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.PRESENCE,
            gate.project(near = false, level = 0, now = 10_250L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.BOTH,
            gate.project(near = null, level = null, now = 10_270L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.BOTH,
            gate.project(near = true, level = 100, now = 10_290L, sparseReporting = false),
        )
    }

    @Test fun densePresenceReversalRequiresTwoHundredFiftyMillisecondsOfContinuousEvidence() {
        val gate = ProximityReportGate()
        assertEquals(
            ProximityReportGate.BOTH,
            gate.project(near = false, level = 0, now = 1_000L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.NONE,
            gate.project(near = true, level = 100, now = 1_050L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.NONE,
            gate.project(near = false, level = 0, now = 1_200L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.NONE,
            gate.project(near = true, level = 100, now = 1_250L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.NONE,
            gate.project(near = true, level = 100, now = 1_499L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.PRESENCE,
            gate.project(near = true, level = 100, now = 1_500L, sparseReporting = false),
        )
    }

    @Test fun eitherMissingChannelIsImmediateCombinedUnavailabilityAndClearsPending() {
        val gate = ProximityReportGate()
        assertEquals(ProximityReportGate.BOTH, gate.project(true, 100, 1_000L, sparseReporting = false))
        assertEquals(ProximityReportGate.NONE, gate.project(false, 0, 1_100L, sparseReporting = false))
        assertEquals(ProximityReportGate.BOTH, gate.project(false, null, 1_200L, sparseReporting = false))
        assertEquals(ProximityReportGate.BOTH, gate.project(false, 0, 1_220L, sparseReporting = false))
        assertEquals(ProximityReportGate.NONE, gate.project(true, 100, 1_240L, sparseReporting = false))
        assertEquals(ProximityReportGate.BOTH, gate.project(null, 100, 1_250L, sparseReporting = false))
    }

    @Test fun reportGateResetReopensBothChannelsAndClearsPendingPresence() {
        val gate = ProximityReportGate()
        assertEquals(ProximityReportGate.BOTH, gate.project(false, 0, 1_000L, sparseReporting = false))
        assertEquals(ProximityReportGate.NONE, gate.project(true, 100, 1_100L, sparseReporting = false))

        gate.reset()

        assertEquals(ProximityReportGate.BOTH, gate.project(true, 100, 1_101L, sparseReporting = false))
    }

    @Test fun sparsePresenceAndNumericEdgesStayImmediate() {
        val gate = ProximityReportGate()
        assertEquals(
            ProximityReportGate.BOTH,
            gate.project(near = false, level = 0, now = 10_000L, sparseReporting = true),
        )
        assertEquals(
            ProximityReportGate.BOTH,
            gate.project(near = true, level = 100, now = 10_020L, sparseReporting = true),
        )
        assertEquals(
            ProximityReportGate.LEVEL,
            gate.project(near = true, level = 70, now = 10_040L, sparseReporting = true),
        )
    }

    @Test fun maturedPresenceDoesNotMoveTheIndependentDenseNumericWindow() {
        val gate = ProximityReportGate()
        assertEquals(
            ProximityReportGate.BOTH,
            gate.project(near = false, level = 0, now = 10_000L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.NONE,
            gate.project(near = true, level = 100, now = 10_200L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.PRESENCE,
            gate.project(near = true, level = 100, now = 10_450L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.NONE,
            gate.project(near = true, level = 70, now = 24_999L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.LEVEL,
            gate.project(near = true, level = 70, now = 25_000L, sparseReporting = false),
        )
    }

    @Test fun dueNumericTelemetryWaitsForPendingDensePresenceToResolve() {
        val gate = ProximityReportGate()
        assertEquals(ProximityReportGate.BOTH, gate.project(false, 0, 10_000L, sparseReporting = false))
        assertEquals(ProximityReportGate.NONE, gate.project(true, 100, 24_900L, sparseReporting = false))
        assertEquals(ProximityReportGate.NONE, gate.project(true, 100, 25_000L, sparseReporting = false))
        assertEquals(ProximityReportGate.BOTH, gate.project(true, 100, 25_150L, sparseReporting = false))
    }

    @Test fun denseBaselineJitterProjectsToOneStableFleetLevel() {
        val engine = ProximityLearningEngine(ProximityLearningRuntime.learningPolicy(sparseSource = false))
        val denseBaselineJitter = floatArrayOf(154f, 156f, 159f, 161f, 158f, 155f, 160f, 157f)
        var now = 0L
        repeat(260) { sample ->
            engine.observe(denseBaselineJitter[sample % denseBaselineJitter.size], now)
            now += 120L
        }
        engine.observe(105f, now)
        engine.observe(80f, now + 100L)
        engine.observe(95f, now + 200L)
        engine.observe(80f, now + 300L)
        engine.observe(158f, now + 400L)
        engine.observe(158f, now + 500L)
        var output = engine.observe(158f, now + 650L)
        now += 800L

        val gate = ProximityReportGate()
        var reports = 0
        var maximumLocalLevel = 0

        repeat(4_000) { sample ->
            output = engine.observe(denseBaselineJitter[sample % denseBaselineJitter.size], now)
            val localLevel = output.normalizedLevel
            maximumLocalLevel = maxOf(maximumLocalLevel, localLevel ?: 0)
            val reportLevel = ProximityLearningRuntime.levelForReport(output.presence, localLevel)
            val mask = gate.project(
                near = output.presence,
                level = reportLevel,
                now = now,
                sparseReporting = false,
            )
            if (mask != ProximityReportGate.NONE) reports++
            now += 120L
        }

        assertTrue("the fixture must exercise real normalized jitter", maximumLocalLevel >= 4)
        assertEquals(false, output.presence)
        assertEquals(1, reports)
    }

    @Test fun sparseFinalNumericEdgeIsImmediateOnlyAfterCadenceAdmission() {
        val gate = ProximityReportGate()
        assertEquals(
            ProximityReportGate.BOTH,
            gate.project(near = false, level = 0, now = 10_000L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.NONE,
            gate.project(near = false, level = 25, now = 10_020L, sparseReporting = false),
        )
        assertEquals(
            ProximityReportGate.LEVEL,
            gate.project(near = false, level = 25, now = 10_040L, sparseReporting = true),
        )
    }

    @Test fun unresolvedTwoEdgeBurstFlushesItsHeldFinalLevelWhenSparseIsReadmitted() {
        val gate = ProximityReportGate()
        assertEquals(ProximityReportGate.BOTH, gate.project(false, 0, 10_000L, sparseReporting = true))
        assertEquals(ProximityReportGate.LEVEL, gate.project(false, 20, 10_020L, sparseReporting = true))
        assertEquals(ProximityReportGate.NONE, gate.project(false, 30, 10_040L, sparseReporting = false))
        assertFalse(proximityCadenceWindowIsContinuous(sampleCount = 2))
        assertEquals(ProximityReportGate.LEVEL, gate.project(false, 30, 13_020L, sparseReporting = true))
    }
}
