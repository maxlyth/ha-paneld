package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.sensors.ProximityCalibrationEngine.Calibration
import io.github.maxlyth.hapaneld.sensors.ProximityCalibrationEngine.Mode
import io.github.maxlyth.hapaneld.sensors.ProximityCalibrationEngine.Stage
import org.junit.Assert.*
import org.junit.Test

class ProximityCalibrationEngineTest {
    @Test fun rangedCalibrationWorksAcrossPositiveScalesAndBothDirections() {
        for ((clear, near) in listOf(40f to 5f, 1_000f to 100f, 2f to 100f, .04f to .006f)) {
            var committed: Calibration? = null
            val engine = ProximityCalibrationEngine(commit = { committed = it; true })
            completeWizard(engine, clear, near)
            assertEquals(Stage.REVIEW, engine.current().stage)
            assertNull(committed)
            assertEquals(3, engine.current().accepted)
            assertFalse(engine.current().wakeReady)
            engine.action("save", 11_000)
            assertEquals(Stage.SAVED, engine.current().stage)
            assertEquals(Mode.RANGED, committed?.mode)
            assertEquals(clear, committed!!.clearRaw, 0f)
            assertEquals(near, committed!!.nearRaw, 0f)
            assertWave(engine, clear, near, 12_000)
        }
    }

    @Test fun binaryPolarityComesFromActualClearAndNearValues() {
        for ((clear, near) in listOf(0f to 1f, 1f to 0f)) {
            var committed: Calibration? = null
            val engine = ProximityCalibrationEngine(commit = { committed = it; true })
            completeWizard(engine, clear, near)
            assertEquals(Mode.BINARY, engine.current().mode)
            assertTrue(engine.current().message.contains("distance is fixed"))
            engine.action("save", 11_000)
            assertEquals(Mode.BINARY, committed!!.mode)
            assertEquals(clear, committed!!.clearRaw, 0f)
            assertWave(engine, clear, near, 12_000)
        }
    }

    @Test fun noMetadataParameterCanTurnBinaryValuesIntoAnAdvertisedRange() {
        val engine = ProximityCalibrationEngine()
        completeWizard(engine, 1f, 0f)
        assertEquals(Mode.BINARY, engine.current().mode)
    }

    @Test fun ordinaryMovementCannotTrainOrPersist() {
        var writes = 0
        val engine = ProximityCalibrationEngine(commit = { writes++; true })
        repeat(30) { index ->
            engine.observe(40f, index * 2_000L)
            engine.observe(4f, index * 2_000L + 800)
            assertFalse(engine.observe(40f, index * 2_000L + 1_200).gesture)
            assertFalse(engine.tick(index * 2_000L + 1_400).gesture)
        }
        assertNull(engine.current().calibration)
        assertFalse(engine.current().wakeReady)
        assertEquals(0, writes)
    }

    @Test fun fixedCalibrationNeverRebasesAfterMovementOrLongHolds() {
        val fixed = ranged()
        var writes = 0
        val engine = ProximityCalibrationEngine(fixed) { writes++; true }
        repeat(50) { index ->
            engine.observe(38f, index * 2_000L)
            engine.observe(1f, index * 2_000L + 800)
            engine.observe(38f, index * 2_000L + 1_200)
        }
        engine.observe(20f, 110_000)
        engine.tick(200_000)
        assertEquals(fixed, engine.current().calibration)
        assertEquals(0, writes)
    }

    @Test fun cancellationAtEveryStagePreservesPreviousCalibrationAndDoesNotWrite() {
        for (target in listOf(Stage.INTRO, Stage.CLEAR, Stage.NEAR, Stage.RETURN_CLEAR, Stage.WAVES, Stage.REVIEW)) {
            val original = ranged()
            var writes = 0
            val engine = ProximityCalibrationEngine(original) { writes++; true }
            progressTo(engine, 0f, 1f, target)
            assertEquals(target, engine.current().stage)
            engine.action("cancel", 11_000)
            assertEquals(Stage.CANCELLED, engine.current().stage)
            assertEquals(original, engine.current().calibration)
            assertEquals(0, writes)
            assertWave(engine, original.clearRaw, original.nearRaw, 12_000)
        }
    }

    @Test fun timeoutAndProcessRestartDiscardStagedChanges() {
        val old = ranged()
        var writes = 0
        val engine = ProximityCalibrationEngine(old) { writes++; true }
        completeWizard(engine, 0f, 1f)
        engine.tick(ProximityCalibrationEngine.SESSION_TIMEOUT_MS)
        assertEquals(Stage.TIMED_OUT, engine.current().stage)
        assertEquals(old, engine.current().calibration)
        assertEquals(0, writes)
        val restarted = ProximityCalibrationEngine(old) { writes++; true }
        assertNull(restarted.current().stage)
        assertFalse(restarted.current().active)
        assertWave(restarted, 40f, 5f, 1_000)
        assertEquals(0, writes)
    }

    @Test fun sensorLossAndMalformedSamplesDiscardCandidateAndRevokeWakeGeneration() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null)) {
            val old = ranged()
            var writes = 0
            val engine = ProximityCalibrationEngine(old) { writes++; true }
            completeWizard(engine, 0f, 1f)
            val generation = engine.current().generation
            if (invalid == null) engine.sourceUnavailable(11_000) else engine.observe(invalid, 11_000)
            assertEquals(Stage.FAILED, engine.current().stage)
            assertFalse(engine.current().available)
            assertFalse(engine.current().wakeReady)
            assertTrue(engine.current().generation > generation)
            assertEquals(old, engine.current().calibration)
            engine.action("save", 11_001)
            assertEquals(0, writes)
        }
    }

    @Test fun atomicCommitFailurePreservesPreviousLiveAndDurableCalibration() {
        for (throws in listOf(false, true)) {
            val old = ranged()
            val stored = old
            var writes = 0
            val engine = ProximityCalibrationEngine(old) {
                writes++
                if (throws) throw IllegalStateException("disk unavailable")
                false
            }
            completeWizard(engine, 0f, 1f)
            assertEquals(old, engine.current().calibration)
            engine.action("save", 11_000)
            assertEquals(Stage.FAILED, engine.current().stage)
            assertEquals(old, engine.current().calibration)
            assertEquals(old, stored)
            assertEquals(1, writes)
            assertWave(engine, 40f, 5f, 12_000)
        }
    }

    @Test fun successfulCommitOccursOnceOnlyAfterAllEvidenceAndExplicitSave() {
        var writes = 0
        var durable = ranged()
        val engine = ProximityCalibrationEngine(durable) { proposed -> writes++; durable = proposed; true }
        completeWizard(engine, 0f, 1f)
        assertEquals(0, writes)
        assertEquals(Mode.RANGED, durable.mode)
        engine.action("save", 11_000)
        assertEquals(1, writes)
        assertEquals(durable, engine.current().calibration)
        engine.action("save", 11_001)
        assertEquals(1, writes)
        assertEquals(durable, ProximityCalibrationEngine(durable).current().calibration)
    }

    @Test fun noisyTransitionsDoNotWakeOrConsumeCooldown() {
        val engine = ProximityCalibrationEngine(binary())
        engine.observe(0f, 0)
        engine.tick(200)
        engine.observe(1f, 1_000)
        assertFalse(engine.observe(0f, 1_020).gesture)
        assertFalse(engine.tick(1_200).gesture)
        engine.observe(1f, 1_800)
        engine.observe(0f, 2_100)
        assertTrue(engine.tick(2_250).gesture)
        assertFalse(engine.tick(2_400).gesture)
    }

    @Test fun shortClearBounceDoesNotCompleteWaveOrConsumeProvisionalCooldown() {
        val engine = ProximityCalibrationEngine(binary())
        engine.observe(0f, 0)
        engine.tick(200)
        engine.observe(1f, 1_000)
        engine.observe(0f, 1_300)
        assertFalse(engine.tick(1_350).gesture)
        engine.observe(1f, 1_400)
        assertFalse(engine.tick(1_600).gesture)
        engine.observe(0f, 1_700)
        assertFalse(engine.tick(1_850).gesture)
        engine.observe(1f, 2_450)
        engine.observe(0f, 2_750)
        assertTrue(engine.tick(2_900).gesture)
    }

    @Test fun heldNearOverMaximumIsRejectedAndNextWaveCanWake() {
        val engine = ProximityCalibrationEngine(binary())
        engine.observe(0f, 0)
        engine.tick(200)
        engine.observe(1f, 1_000)
        assertFalse(engine.tick(5_001).gesture)
        engine.observe(0f, 5_100)
        assertFalse(engine.tick(5_300).gesture)
        engine.observe(1f, 5_900)
        engine.observe(0f, 6_200)
        assertTrue(engine.tick(6_350).gesture)
    }

    @Test fun stateOnlyNearOrReturnCannotWakeAndCancelsPendingLiveWave() {
        for (historicalNear in listOf(true, false)) {
            val engine = ProximityCalibrationEngine(binary())
            engine.observe(0f, 0)
            engine.tick(200)
            engine.observe(1f, 1_000, live = !historicalNear)
            engine.observe(0f, 1_300, live = historicalNear)
            assertFalse(engine.tick(1_500).gesture)
            assertWave(engine, 0f, 1f, 3_000)
        }
        val engine = ProximityCalibrationEngine(binary())
        engine.observe(0f, 0)
        engine.tick(200)
        engine.observe(1f, 1_000)
        engine.observe(0f, 1_300)
        val generation = engine.current().generation
        engine.observe(0f, 1_350, live = false)
        assertTrue(engine.current().generation > generation)
        assertFalse(engine.tick(1_500).gesture)
    }

    @Test fun sourceLossBetweenEdgeAndDebounceCannotWake() {
        val engine = ProximityCalibrationEngine(binary())
        engine.observe(0f, 0)
        engine.tick(200)
        engine.observe(1f, 1_000)
        engine.observe(0f, 1_300)
        engine.sourceUnavailable(1_400)
        assertFalse(engine.tick(1_500).gesture)
        assertFalse(engine.current().wakeReady)
    }

    @Test fun wizardStartRevokesPendingWaveAndNoWizardGestureActuates() {
        val engine = ProximityCalibrationEngine(binary())
        engine.observe(0f, 0)
        engine.tick(200)
        engine.observe(1f, 1_000)
        engine.observe(0f, 1_300)
        val generation = engine.current().generation
        engine.start(1_350)
        assertTrue(engine.current().generation > generation)
        assertFalse(engine.tick(1_500).gesture)
        assertFalse(engine.current().wakeReady)
    }

    @Test fun hysteresisAndDebouncePreventPresenceChatter() {
        val engine = ProximityCalibrationEngine(ranged())
        engine.observe(40f, 0)
        assertNull(engine.current().near)
        assertEquals(false, engine.tick(200).near)
        engine.observe(5f, 1_000)
        assertEquals(false, engine.current().near)
        assertEquals(true, engine.tick(1_150).near)
        for (now in 1_200L..1_400L step 50) assertEquals(true, engine.observe(22f, now).near)
        engine.observe(40f, 1_500)
        assertEquals(true, engine.current().near)
        assertEquals(false, engine.tick(1_650).near)
    }

    @Test fun noClearOrNoNearCannotPassWizard() {
        val engine = ProximityCalibrationEngine(ranged())
        engine.start(0)
        engine.action("begin", 0)
        engine.tick(ProximityCalibrationEngine.SESSION_TIMEOUT_MS)
        assertEquals(Stage.TIMED_OUT, engine.current().stage)
        val unchanged = ProximityCalibrationEngine(ranged())
        unchanged.start(0)
        unchanged.observe(40f, 0)
        unchanged.action("begin", 0)
        unchanged.tick(1_200)
        unchanged.tick(2_000)
        unchanged.observe(40f, 3_200)
        unchanged.tick(4_000)
        assertEquals(Stage.NEAR, unchanged.current().stage)
        unchanged.tick(ProximityCalibrationEngine.SESSION_TIMEOUT_MS)
        assertEquals(Stage.TIMED_OUT, unchanged.current().stage)
    }

    @Test fun clockRegressionAndUnexpectedBinaryValueFailClosedWithoutErasingCalibration() {
        val fixed = binary()
        for (clockRegression in listOf(true, false)) {
            val engine = ProximityCalibrationEngine(fixed)
            engine.observe(0f, 100)
            engine.tick(300)
            if (clockRegression) engine.observe(1f, 200) else engine.observe(8f, 400)
            assertFalse(engine.current().available)
            assertFalse(engine.current().wakeReady)
            assertEquals(fixed, engine.current().calibration)
        }
    }

    @Test fun retainedWizardStateCannotBePromotedToCaptureEvidenceByTick() {
        val engine = ProximityCalibrationEngine()
        engine.start(0)
        engine.action("begin", 0)
        engine.observe(0f, 1_200, live = false)
        engine.tick(2_000)
        engine.tick(3_000)
        assertEquals(Stage.CLEAR, engine.current().stage)
        engine.observe(0f, 3_100)
        engine.tick(3_900)
        assertEquals(Stage.NEAR, engine.current().stage)
    }

    @Test fun representationChangeAfterReviewCannotBeSaved() {
        var writes = 0
        val old = ranged()
        val engine = ProximityCalibrationEngine(old) { writes++; true }
        completeWizard(engine, 0f, 1f)
        engine.observe(3f, 11_000)
        assertEquals(Stage.FAILED, engine.current().stage)
        engine.action("save", 11_001)
        assertEquals(0, writes)
        assertEquals(old, engine.current().calibration)
    }

    @Test fun freshProbeCanCaptureCalibrationButCannotWake() {
        val engine = ProximityCalibrationEngine(binary())
        engine.observe(0f, 0)
        engine.tick(200)
        engine.observe(1f, 1_000)
        engine.observe(0f, 1_300, live = false, calibrationLive = true)
        assertFalse(engine.tick(1_500).gesture)
        engine.start(2_000)
        engine.action("begin", 2_000)
        engine.observe(0f, 3_200, live = false, calibrationLive = true)
        engine.tick(4_000)
        assertEquals(Stage.NEAR, engine.current().stage)
    }

    @Test fun startingWizardRequiresNewSourceEvidenceRatherThanOldHeldState() {
        val engine = ProximityCalibrationEngine()
        engine.observe(0f, 0)
        engine.start(10_000)
        engine.action("begin", 10_000)
        engine.tick(11_200)
        engine.tick(12_000)
        assertEquals(Stage.CLEAR, engine.current().stage)
    }

    @Test fun rejectedServiceAdmissionReleasesCooldownWithoutLosingClearEvidence() {
        val engine = ProximityCalibrationEngine(binary())
        engine.observe(0f, 0)
        engine.tick(200)
        engine.observe(1f, 800)
        engine.observe(0f, 1_100)
        assertTrue(engine.tick(1_250).gesture)
        assertTrue(engine.current().wakeReady)
        engine.releaseGestureCooldown()
        assertTrue(engine.current().wakeReady)
        engine.observe(1f, 1_800)
        engine.observe(0f, 2_100)
        assertTrue(engine.tick(2_250).gesture)
    }

    @Test fun noisyButSeparatedCaptureUsesBoundedWindowsInsteadOfWaitingForStillRawValues() {
        for ((clearValues, nearValues) in listOf(
            listOf(16.875f, 23.625f, 18f, 22f) to listOf(78f, 83f, 81f, 80f),
            listOf(20f, 30f, 22f, 28f) to listOf(1f, 2f, 1.5f, 2.5f),
        )) {
            val engine = ProximityCalibrationEngine()
            engine.start(0)
            engine.action("begin", 0)
            for (index in 0..8) engine.observe(clearValues[index % clearValues.size], 1_200L + index * 100)
            assertEquals(Stage.NEAR, engine.current().stage)
            for (index in 0..8) engine.observe(nearValues[index % nearValues.size], 3_200L + index * 100)
            assertEquals(Stage.RETURN_CLEAR, engine.current().stage)
            val clear = clearValues[0]
            val near = nearValues[0]
            engine.observe(clear, 4_100)
            engine.tick(4_900)
            assertEquals(Stage.WAVES, engine.current().stage)
            for (start in listOf(6_000L, 8_000L, 10_000L)) {
                engine.observe(near, start)
                engine.observe(clear, start + 400)
                engine.tick(start + 550)
            }
            assertEquals(Stage.REVIEW, engine.current().stage)
            assertEquals(Mode.RANGED, engine.current().mode)
        }
    }

    @Test fun overlappingNoiseWaitsWithoutChangingPreviousCalibration() {
        val original = ranged()
        val engine = ProximityCalibrationEngine(original)
        engine.start(0)
        engine.action("begin", 0)
        for (index in 0..8) engine.observe(if (index % 2 == 0) 20f else 40f, 1_200L + index * 100)
        assertEquals(Stage.NEAR, engine.current().stage)
        for (index in 0..8) engine.observe(if (index % 2 == 0) 21f else 39f, 3_200L + index * 100)
        assertEquals(Stage.NEAR, engine.current().stage)
        assertEquals(original, engine.current().calibration)
    }

    @Test fun clearNoiseCanContinueDuringNearInstructionsUntilAnActualApproach() {
        val engine = ProximityCalibrationEngine()
        engine.start(0)
        engine.action("begin", 0)
        val clearBand = listOf(20f, 25f, 30f)
        for (index in 0..8) engine.observe(clearBand[index % clearBand.size], 1_200L + index * 100)
        assertEquals(Stage.NEAR, engine.current().stage)
        for (index in 0..30) {
            engine.observe(if (index % 2 == 0) 20f else 30f, 3_200L + index * 100)
            assertEquals(Stage.NEAR, engine.current().stage)
        }
        for (index in 0..8) engine.observe(1f, 6_300L + index * 100)
        assertEquals(Stage.RETURN_CLEAR, engine.current().stage)
        engine.observe(20f, 7_200)
        engine.tick(8_000)
        assertEquals(Stage.WAVES, engine.current().stage)
        for (start in listOf(9_000L, 11_000L, 13_000L)) {
            engine.observe(1f, start)
            engine.observe(20f, start + 400)
            engine.tick(start + 550)
        }
        assertEquals(Stage.REVIEW, engine.current().stage)
    }

    @Test fun finiteExtremeRawValuesUseDoubleArithmeticWithoutCrashing() {
        val calibration = Calibration(mode = Mode.RANGED, clearRaw = -Float.MAX_VALUE, nearRaw = -Float.MAX_VALUE / 2f)
        assertEquals(0, calibration.level(-Float.MAX_VALUE))
        assertEquals(100, calibration.level(Float.MAX_VALUE))
        val engine = ProximityCalibrationEngine(calibration)
        engine.observe(-Float.MAX_VALUE, 0)
        engine.tick(200)
        assertEquals(100, engine.observe(Float.MAX_VALUE, 1_000).level)
    }

    private fun ranged() = Calibration(mode = Mode.RANGED, clearRaw = 40f, nearRaw = 5f)
    private fun binary() = Calibration(mode = Mode.BINARY, clearRaw = 0f, nearRaw = 1f)

    private fun assertWave(engine: ProximityCalibrationEngine, clear: Float, near: Float, start: Long) {
        assertFalse(engine.observe(clear, start).gesture)
        engine.tick(start + 200)
        assertTrue(engine.current().wakeReady)
        assertFalse(engine.observe(near, start + 800).gesture)
        assertFalse(engine.observe(clear, start + 1_100).gesture)
        assertFalse(engine.tick(start + 1_249).gesture)
        assertTrue(engine.tick(start + 1_250).gesture)
        assertFalse(engine.tick(start + 1_251).gesture)
    }

    private fun completeWizard(engine: ProximityCalibrationEngine, clear: Float, near: Float) =
        progressTo(engine, clear, near, Stage.REVIEW)

    private fun progressTo(engine: ProximityCalibrationEngine, clear: Float, near: Float, target: Stage) {
        engine.start(0)
        engine.observe(clear, 0)
        if (target == Stage.INTRO) return
        engine.action("begin", 10)
        if (target == Stage.CLEAR) return
        engine.tick(1_210)
        engine.tick(2_010)
        assertEquals(Stage.NEAR, engine.current().stage)
        if (target == Stage.NEAR) return
        engine.observe(near, 3_310)
        engine.tick(4_110)
        assertEquals(Stage.RETURN_CLEAR, engine.current().stage)
        if (target == Stage.RETURN_CLEAR) return
        engine.observe(clear, 4_210)
        engine.tick(5_010)
        assertEquals(Stage.WAVES, engine.current().stage)
        if (target == Stage.WAVES) return
        for (start in listOf(6_000L, 8_000L, 10_000L)) {
            assertFalse(engine.observe(near, start).gesture)
            assertFalse(engine.observe(clear, start + 400).gesture)
            assertFalse(engine.tick(start + 550).gesture)
        }
        assertEquals(Stage.REVIEW, engine.current().stage)
    }
}
