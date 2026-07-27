package io.github.maxlyth.hapaneld.sensors

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure proximity policy that turns an arbitrary panel sensor scale into one fleet-wide contract:
 * 0 is confidently far and 100 is the strongest learned near excursion. The engine learns the raw
 * scale, polarity, and binary/graded reporting empirically; callers do not need hardware heuristics.
 *
 * [observe] is O(1), uses fixed storage, and returns the same borrowed [Output] instance every time.
 * A caller must consume that view before its next call into the engine. Explicit persistence via
 * [snapshot] is deliberately off the hot path and may allocate.
 *
 * Time is supplied by the caller and must come from a monotonic clock. Regressing timestamps,
 * invalid samples, stale streams, unverified seeds, and model shifts all fail closed: presence is
 * null and gesture actuation is suppressed until trustworthy samples recover the model.
 */
internal class ProximityLearningEngine(
    private val policy: Policy = Policy(),
    private val prepareWakeEvidenceInvalidation: () -> Boolean = { true },
) {
    enum class Mode { UNKNOWN, BINARY, GRADED }

    enum class Polarity { UNKNOWN, NEAR_IS_LOWER, NEAR_IS_HIGHER }

    enum class GuidedLabel { NONE, FAR, NEAR }

    enum class LearningStatus {
        LEARNING_FAR,
        LEARNING_EXCURSION,
        VERIFYING_SEED,
        READY,
        RELEARNING,
    }

    enum class HealthStatus {
        NO_DATA,
        LEARNING,
        HEALTHY,
        STALE,
        INVALID_SAMPLE,
        CLOCK_REGRESSION,
        MODEL_SHIFT,
    }

    enum class ActuationStatus {
        SUPPRESSED_LEARNING,
        SUPPRESSED_UNHEALTHY,
        ARMING,
        ARMED,
        EXCURSION,
        COOLDOWN,
    }

    /** Construction-time tuning. Values are time- and scale-based, never device-specific. */
    data class Policy(
        val baselineSamples: Int = 8,
        val guidedFarSamples: Int = 3,
        val baselineDurationMs: Long = 350L,
        val guidedBaselineDurationMs: Long = 100L,
        val seedFarSamples: Int = 4,
        val guidedSeedFarSamples: Int = 2,
        val seedVerifyDurationMs: Long = 200L,
        val seedMismatchHoldMs: Long = 1_500L,
        val farArmMs: Long = 250L,
        val minimumExcursionMs: Long = 80L,
        // Dense sources must sustain both departure and return evidence for this interval. Sparse
        // on-change sources leave this at zero because silence between their two edges is evidence.
        val continuousEvidenceDwellMs: Long = 0L,
        val maximumGestureMs: Long = 3_000L,
        val cooldownMs: Long = 500L,
        val staleAfterMs: Long = 5_000L,
        val changePointHoldMs: Long = 15_000L,
        val quietEvidenceMs: Long = 2_000L,
        val contradictionConfirmWindowMs: Long = 30_000L,
        val nearEnterLevel: Int = 60,
        val farExitLevel: Int = 30,
        val farArmLevel: Int = 15,
        // Numeric epsilon only. Behavioral gates derive from observed noise and learned span so raw
        // units may be 0/0.01, 0/1, 0/1000, or a large translated vendor scale.
        val minimumAbsoluteDelta: Float = 0.000001f,
        val noiseMultiplier: Float = 6f,
    ) {
        init {
            require(baselineSamples >= 1 && guidedFarSamples >= 1)
            require(seedFarSamples >= 1 && guidedSeedFarSamples >= 1)
            require(baselineDurationMs >= 0L && guidedBaselineDurationMs >= 0L)
            require(seedVerifyDurationMs >= 0L && seedMismatchHoldMs > 0L)
            require(farArmMs >= 0L && minimumExcursionMs >= 0L && continuousEvidenceDwellMs >= 0L)
            require(maximumGestureMs >= minimumExcursionMs)
            require(cooldownMs >= 0L && staleAfterMs > 0L)
            require(changePointHoldMs > maximumGestureMs)
            require(quietEvidenceMs >= 0L)
            require(contradictionConfirmWindowMs >= maximumGestureMs)
            require(nearEnterLevel in 1..100)
            require(farExitLevel in 0 until nearEnterLevel)
            require(farArmLevel in 0..farExitLevel)
            require(minimumAbsoluteDelta > 0f && minimumAbsoluteDelta.isFinite())
            require(noiseMultiplier >= 1f && noiseMultiplier.isFinite())
        }
    }

    /** Approximate anchors imported from an older calibration or a hardware-neutral profile hint. */
    data class WarmSeed(
        val farRaw: Float,
        val nearRaw: Float,
        val mode: Mode = Mode.UNKNOWN,
        val noise: Float = Float.NaN,
    )

    /** Durable learned state. Monotonic timestamps and in-flight gestures are intentionally absent. */
    data class Snapshot(
        val schemaVersion: Int = SNAPSHOT_VERSION,
        val farRaw: Float,
        val nearRaw: Float,
        val noise: Float,
        val mode: Mode,
        val polarity: Polarity,
        val completedExcursions: Int,
        val deliberateExamples: Int = 0,
    )

    /**
     * Borrowed output view. [gesture] is true only on the observation that completes a deliberate
     * far -> excursion -> far cycle; [gestureSequence] also advances so a consumer can detect events
     * without retaining this mutable view.
     */
    class Output internal constructor() {
        var accepted: Boolean = false
            internal set
        var raw: Float = Float.NaN
            internal set
        var normalizedLevel: Int? = null
            internal set
        var presence: Boolean? = null
            internal set
        var gesture: Boolean = false
            internal set
        var gestureSequence: Long = 0L
            internal set
        var completedExcursions: Int = 0
            internal set
        var deliberateExample: Boolean = false
            internal set
        var completedEpisode: Boolean = false
            internal set
        var episodeDurationMs: Long = 0L
            internal set
        var episodePeakLevel: Int = 0
            internal set
        var deliberateExamples: Int = 0
            internal set
        var wakeEvidenceGeneration: Long = 0L
            internal set
        var modelSequence: Long = 0L
            internal set
        var mode: Mode = Mode.UNKNOWN
            internal set
        var polarity: Polarity = Polarity.UNKNOWN
            internal set
        var learning: LearningStatus = LearningStatus.LEARNING_FAR
            internal set
        var health: HealthStatus = HealthStatus.NO_DATA
            internal set
        var actuation: ActuationStatus = ActuationStatus.SUPPRESSED_LEARNING
            internal set
        var readiness: Int = 0
            internal set
    }

    private val borrowedOutput = Output()
    private val excursionValues = FloatArray(EXCURSION_VALUE_CAPACITY)

    private var lastCallAt = UNSET_TIME
    private var lastValidAt = UNSET_TIME
    private var latestRaw = Float.NaN
    private var inputHealthy = false
    private var lastFault = HealthStatus.NO_DATA

    private var mode = Mode.UNKNOWN
    private var polarity = Polarity.UNKNOWN
    private var farRaw = Float.NaN
    private var nearRaw = Float.NaN
    private var farNoise = policy.minimumAbsoluteDelta
    private var modelReady = false
    private var verifyingSeed = false
    private var relearning = false
    private var completedExcursions = 0
    private var deliberateExamples = 0
    private var wakeEvidenceGeneration = 0L
    private var weakExcursionCount = 0
    private var weakExcursionMeanDelta = 0f
    private var contradictoryEvidenceCount = 0
    private var contradictoryPeakRaw = Float.NaN
    private var contradictoryEvidenceAt = UNSET_TIME
    private var modelSequence = 0L

    private var baselineEstablished = false
    private var baselineCount = 0
    private var guidedFarCount = 0
    private var baselineStartedAt = UNSET_TIME
    private var learningBaselineSince = UNSET_TIME
    private var baselineMean = 0f
    private var baselineM2 = 0f

    private var verifyFarCount = 0
    private var verifyGuidedFarCount = 0
    private var verifyStartedAt = UNSET_TIME
    private var mismatchCount = 0
    private var mismatchStartedAt = UNSET_TIME

    private var candidateActive = false
    private var candidateStartedAt = UNSET_TIME
    private var candidatePeakRaw = Float.NaN
    private var candidatePeakDelta = 0f
    private var candidateEligible = false
    private var candidateQualifiedAt = UNSET_TIME
    private var candidateDepartureSince = UNSET_TIME
    private var candidateDepartureQualified = false
    private var candidateReturnSince = UNSET_TIME
    private var candidateContinuousEvidence = false
    private var candidateTimedOut = false
    private var candidateContradictsModel = false
    private var candidateGuidedNear = false
    private var excursionValueCount = 0
    private var excursionWriteIndex = 0

    private var currentLevel: Int? = null
    private var currentPresence: Boolean? = null
    private var currentHealth = HealthStatus.NO_DATA
    private var currentActuation = ActuationStatus.SUPPRESSED_LEARNING
    private var farStableSince = UNSET_TIME
    private var cooldownUntil = UNSET_TIME
    private var gestureSequence = 0L

    /** Accept one raw sensor observation at a caller-supplied monotonic timestamp. */
    @Synchronized
    fun observe(
        raw: Float,
        elapsedRealtimeMs: Long,
        label: GuidedLabel = GuidedLabel.NONE,
        continuousEvidence: Boolean = policy.continuousEvidenceDwellMs > 0L,
    ): Output {
        beginOutputCall(accepted = false)
        if (!admitTime(elapsedRealtimeMs)) {
            failInput(HealthStatus.CLOCK_REGRESSION)
            return syncOutput()
        }
        if (!raw.isFinite()) {
            failInput(HealthStatus.INVALID_SAMPLE)
            return syncOutput()
        }

        inputHealthy = true
        lastFault = HealthStatus.HEALTHY
        lastValidAt = elapsedRealtimeMs
        latestRaw = raw
        borrowedOutput.accepted = true

        when {
            verifyingSeed -> processSeedVerification(raw, elapsedRealtimeMs, label, continuousEvidence)
            modelReady -> processReady(raw, elapsedRealtimeMs, label, continuousEvidence)
            else -> processLearning(raw, elapsedRealtimeMs, label, continuousEvidence)
        }
        return syncOutput()
    }

    /** Advance health/arming timers without fabricating a sensor sample. */
    @Synchronized
    fun tick(elapsedRealtimeMs: Long): Output {
        beginOutputCall(accepted = false)
        if (!admitTime(elapsedRealtimeMs)) {
            failInput(HealthStatus.CLOCK_REGRESSION)
            return syncOutput()
        }
        if (lastValidAt == UNSET_TIME) {
            currentHealth = HealthStatus.NO_DATA
            currentLevel = null
            currentPresence = null
            currentActuation = ActuationStatus.SUPPRESSED_UNHEALTHY
            return syncOutput()
        }
        if (elapsedRealtimeMs - lastValidAt > policy.staleAfterMs) {
            inputHealthy = false
            lastFault = HealthStatus.STALE
            cancelCandidate(restoreReadyModel = true)
            farStableSince = UNSET_TIME
            currentHealth = HealthStatus.STALE
            currentLevel = null
            currentPresence = null
            currentActuation = ActuationStatus.SUPPRESSED_UNHEALTHY
            return syncOutput()
        }
        if (!inputHealthy) {
            currentHealth = lastFault
            currentLevel = null
            currentPresence = null
            currentActuation = ActuationStatus.SUPPRESSED_UNHEALTHY
            return syncOutput()
        }
        if (modelReady && !relearning) {
            currentHealth = HealthStatus.HEALTHY
            currentLevel = normalized(latestRaw)
            currentPresence = hystereticPresence(currentLevel!!)
            updateFarArm(elapsedRealtimeMs, currentLevel!!)
        } else {
            setLearningOutput()
        }
        return syncOutput()
    }

    /** Transport-level loss is authoritative even for a source whose normal silence is unbounded. */
    @Synchronized
    fun sourceUnavailable(): Output {
        beginOutputCall(accepted = false)
        failInput(HealthStatus.STALE)
        return syncOutput()
    }

    /** Load approximate anchors. Live far samples must verify them before output or actuation starts. */
    @Synchronized
    fun warmSeed(seed: WarmSeed): Boolean {
        val seedPolarity = inferredPolarity(seed.farRaw, seed.nearRaw)
        if (!validAnchors(seed.farRaw, seed.nearRaw, seedPolarity)) return false
        if (!seed.noise.isNaN() && (!seed.noise.isFinite() || seed.noise < 0f)) return false

        resetInternal(resetGestureSequence = true)
        farRaw = seed.farRaw
        nearRaw = seed.nearRaw
        polarity = seedPolarity
        mode = seed.mode
        val floor = numericFloor(seed.farRaw, seed.nearRaw)
        farNoise = if (seed.noise.isFinite()) {
            max(seed.noise, floor * 0.25f)
        } else {
            max(abs(seed.nearRaw - seed.farRaw) * 0.01f, floor * 0.25f)
        }
        verifyingSeed = true
        baselineEstablished = true
        currentHealth = HealthStatus.NO_DATA
        currentActuation = ActuationStatus.SUPPRESSED_LEARNING
        syncOutput()
        return true
    }

    /** Restore a schema-checked durable snapshot, again requiring live far verification. */
    @Synchronized
    fun restore(snapshot: Snapshot): Boolean {
        if (snapshot.schemaVersion != SNAPSHOT_VERSION) return false
        if (snapshot.mode == Mode.UNKNOWN || snapshot.polarity == Polarity.UNKNOWN) return false
        if (!snapshot.noise.isFinite() || snapshot.noise < 0f) return false
        if (snapshot.completedExcursions < 0) return false
        if (snapshot.deliberateExamples !in 0..snapshot.completedExcursions) return false
        if (!validAnchors(snapshot.farRaw, snapshot.nearRaw, snapshot.polarity)) return false

        resetInternal(resetGestureSequence = true)
        farRaw = snapshot.farRaw
        nearRaw = snapshot.nearRaw
        farNoise = max(snapshot.noise, numericFloor(snapshot.farRaw, snapshot.nearRaw) * 0.25f)
        mode = snapshot.mode
        polarity = snapshot.polarity
        completedExcursions = snapshot.completedExcursions
        deliberateExamples = snapshot.deliberateExamples
        verifyingSeed = true
        baselineEstablished = true
        currentHealth = HealthStatus.NO_DATA
        currentActuation = ActuationStatus.SUPPRESSED_LEARNING
        syncOutput()
        return true
    }

    /** Capture only a fully learned, stable model; transient gesture/timing state is never persisted. */
    @Synchronized
    fun snapshot(): Snapshot? {
        if (!modelReady || verifyingSeed || relearning || mode == Mode.UNKNOWN || polarity == Polarity.UNKNOWN) return null
        return Snapshot(
            farRaw = farRaw,
            nearRaw = nearRaw,
            noise = farNoise,
            mode = mode,
            polarity = polarity,
            completedExcursions = completedExcursions,
            deliberateExamples = deliberateExamples,
        )
    }

    @Synchronized
    fun reset() {
        resetInternal(resetGestureSequence = true)
        syncOutput()
    }

    /** Current borrowed view without advancing time. */
    @Synchronized
    fun current(): Output = syncOutput()

    private fun processLearning(raw: Float, now: Long, label: GuidedLabel, continuousEvidence: Boolean) {
        if (!baselineEstablished) {
            // Some vendor HALs claim to be continuous but only emit value changes. If one clear
            // sample has remained unchanged for a deliberate quiet interval, establish it as the
            // baseline before treating this later callback as a possible excursion.
            if (
                baselineCount == 1 && label != GuidedLabel.NEAR &&
                now - baselineStartedAt >= policy.quietEvidenceMs
            ) {
                establishBaseline()
                processLearning(raw, now, label, continuousEvidence)
                return
            }
            if (label != GuidedLabel.NEAR) addBaselineSample(raw, now, label == GuidedLabel.FAR)
            setLearningOutput()
            return
        }

        if (candidateActive) {
            processCandidate(raw, now, label)
            if (!modelReady) setLearningOutput()
            return
        }

        val delta = raw - farRaw
        val gate = departureGate(hasModel = false)
        val guidedDeparture = label == GuidedLabel.NEAR && abs(delta) >= guidedDepartureGate()
        if (guidedDeparture || abs(delta) >= gate) {
            beginCandidate(
                raw,
                now,
                label,
                eligible = false,
                contradicts = false,
                continuousEvidence = continuousEvidence,
            )
        } else if (label == GuidedLabel.FAR || label == GuidedLabel.NONE) {
            adaptFar(raw, alpha = BASELINE_ADAPT_ALPHA)
        }
        setLearningOutput()
    }

    private fun processSeedVerification(
        raw: Float,
        now: Long,
        label: GuidedLabel,
        continuousEvidence: Boolean,
    ) {
        currentLevel = null
        currentPresence = null
        currentHealth = HealthStatus.LEARNING
        currentActuation = ActuationStatus.SUPPRESSED_LEARNING

        // A previously matching far sample followed by a quiet interval is sufficient for a source
        // that behaves as on-change despite its HAL metadata. Promote the seed before interpreting
        // this later callback, which may be the near edge of the user's first gesture.
        if (
            verifyFarCount > 0 && verifyStartedAt != UNSET_TIME && label != GuidedLabel.NEAR &&
            now - verifyStartedAt >= policy.quietEvidenceMs
        ) {
            markSeedVerified()
            processReady(raw, now, label, continuousEvidence)
            return
        }

        val delta = abs(raw - farRaw)
        val tolerance = max(
            max(abs(nearRaw - farRaw) * SEED_FAR_TOLERANCE_FRACTION, farNoise * policy.noiseMultiplier),
            numericFloor(raw, farRaw),
        )
        val expectedLevel = normalized(raw)
        val directionMatches = directionOf(raw - farRaw) == polarity
        if (label == GuidedLabel.FAR || delta <= tolerance) {
            mismatchCount = 0
            mismatchStartedAt = UNSET_TIME
            if (verifyFarCount == 0) verifyStartedAt = now
            verifyFarCount++
            if (label == GuidedLabel.FAR) verifyGuidedFarCount++
            if (label == GuidedLabel.FAR && delta > tolerance) {
                // A guided far label is stronger evidence than an approximate seed. Preserve the
                // learned span while shifting both anchors onto the live sensor's current offset.
                val shift = raw - farRaw
                farRaw = raw
                nearRaw += shift
            } else {
                adaptFar(raw, alpha = SEED_ADAPT_ALPHA)
            }
            val guidedReady = verifyGuidedFarCount >= policy.guidedSeedFarSamples &&
                now - verifyStartedAt >= min(policy.seedVerifyDurationMs, policy.guidedBaselineDurationMs)
            val passiveReady = verifyFarCount >= policy.seedFarSamples &&
                now - verifyStartedAt >= policy.seedVerifyDurationMs
            if (guidedReady || passiveReady) {
                markSeedVerified()
                currentHealth = HealthStatus.HEALTHY
                currentLevel = normalized(raw)
                currentPresence = currentLevel!! >= policy.nearEnterLevel
                farStableSince = if (currentLevel!! <= policy.farArmLevel) now else UNSET_TIME
                currentActuation = ActuationStatus.ARMING
            }
            return
        }

        // A fully-near sample may be ordinary occupancy, so wait for far. A stable sub-near plateau
        // is different: after a long hold it is safer to open a new behavior epoch than stay Unknown
        // forever on a same-direction baseline/range shift.
        if (label == GuidedLabel.NEAR || (directionMatches && expectedLevel >= policy.nearEnterLevel)) {
            if (
                label != GuidedLabel.NEAR && expectedLevel < MODEL_SHIFT_NEAR_CEILING &&
                directionMatches
            ) {
                if (mismatchStartedAt == UNSET_TIME) mismatchStartedAt = now
                mismatchCount++
                if (now - mismatchStartedAt >= policy.changePointHoldMs) {
                    startFreshLearning(raw, now, isRelearning = true, includeAsFar = true)
                }
            } else {
                mismatchCount = 0
                mismatchStartedAt = UNSET_TIME
            }
            return
        }

        if (mismatchCount == 0) mismatchStartedAt = now
        mismatchCount++
        if (mismatchCount >= policy.seedFarSamples && now - mismatchStartedAt >= policy.seedMismatchHoldMs) {
            startFreshLearning(raw, now, isRelearning = true, includeAsFar = label != GuidedLabel.NEAR)
        }
    }

    private fun processReady(raw: Float, now: Long, label: GuidedLabel, continuousEvidence: Boolean) {
        if (
            contradictoryEvidenceCount > 0 && contradictoryEvidenceAt != UNSET_TIME &&
            now - contradictoryEvidenceAt > policy.contradictionConfirmWindowMs
        ) clearContradictoryEvidence()
        currentHealth = HealthStatus.HEALTHY
        if (candidateActive) {
            processCandidate(raw, now, label)
            if (candidateActive) {
                if ((candidateContradictsModel && candidateDepartureQualified) || relearning) {
                    currentLevel = null
                    currentPresence = null
                    currentHealth = HealthStatus.MODEL_SHIFT
                    currentActuation = ActuationStatus.SUPPRESSED_UNHEALTHY
                } else {
                    currentLevel = normalized(raw)
                    currentPresence = hystereticPresence(currentLevel!!)
                    currentActuation = ActuationStatus.EXCURSION
                }
            }
            return
        }

        val level = normalized(raw)
        currentLevel = level
        currentPresence = hystereticPresence(level)
        val delta = raw - farRaw
        val gate = departureGate(hasModel = true)
        val guidedDeparture = label == GuidedLabel.NEAR && abs(delta) >= guidedDepartureGate()
        if (guidedDeparture || abs(delta) >= gate) {
            val contradicts = polarity != Polarity.UNKNOWN && directionOf(delta) != polarity
            // On-change sensors emit no repeated far samples. A quiet elapsed far interval is just as
            // strong as periodic samples once the cooldown and arm windows have both passed.
            val elapsedFarArmed = farStableSince != UNSET_TIME &&
                now - farStableSince >= policy.farArmMs &&
                (cooldownUntil == UNSET_TIME || now >= cooldownUntil)
            val wasArmed = currentActuation == ActuationStatus.ARMED || elapsedFarArmed
            beginCandidate(
                raw,
                now,
                label,
                eligible = wasArmed,
                contradicts = contradicts,
                continuousEvidence = continuousEvidence,
            )
            if (contradicts && candidateDepartureQualified) {
                relearning = true
                currentLevel = null
                currentPresence = null
                currentHealth = HealthStatus.MODEL_SHIFT
                currentActuation = ActuationStatus.SUPPRESSED_UNHEALTHY
            } else {
                updateCandidateQualification(raw, now)
                currentActuation = ActuationStatus.EXCURSION
            }
            return
        }

        if (label != GuidedLabel.NEAR && level <= policy.farExitLevel) {
            adaptFar(raw, alpha = READY_FAR_ADAPT_ALPHA)
        }
        updateFarArm(now, level)
    }

    private fun beginCandidate(
        raw: Float,
        now: Long,
        label: GuidedLabel,
        eligible: Boolean,
        contradicts: Boolean,
        continuousEvidence: Boolean,
    ) {
        candidateActive = true
        candidateStartedAt = now
        candidatePeakRaw = raw
        candidatePeakDelta = abs(raw - farRaw)
        candidateEligible = eligible
        candidateQualifiedAt = UNSET_TIME
        candidateDepartureSince = now
        candidateContinuousEvidence = continuousEvidence && policy.continuousEvidenceDwellMs > 0L
        candidateDepartureQualified = !candidateContinuousEvidence
        candidateReturnSince = UNSET_TIME
        candidateTimedOut = false
        candidateContradictsModel = contradicts
        candidateGuidedNear = label == GuidedLabel.NEAR
        excursionValueCount = 0
        excursionWriteIndex = 0
        recordExcursionValue(raw)
        farStableSince = UNSET_TIME
        if (contradicts && candidateDepartureQualified) relearning = true
    }

    private fun processCandidate(raw: Float, now: Long, label: GuidedLabel) {
        recordExcursionValue(raw)
        val delta = abs(raw - farRaw)
        if (delta > candidatePeakDelta) {
            candidatePeakDelta = delta
            candidatePeakRaw = raw
        }
        if (label == GuidedLabel.NEAR) candidateGuidedNear = true
        if (!candidateContradictsModel) updateCandidateQualification(raw, now)

        val returnGate = max(
            numericFloor(raw, farRaw),
            max(departureGate(modelReady) * RETURN_GATE_FRACTION, candidatePeakDelta * PEAK_RETURN_FRACTION),
        )
        val returnedFar = label == GuidedLabel.FAR || delta <= returnGate
        val continuousDwell = if (candidateContinuousEvidence) policy.continuousEvidenceDwellMs else 0L
        if (continuousDwell > 0L && returnedFar && candidateReturnSince == UNSET_TIME) {
            candidateReturnSince = now
        } else if (!returnedFar) {
            candidateReturnSince = UNSET_TIME
        }
        val episodeEvidenceAt = if (candidateReturnSince != UNSET_TIME) candidateReturnSince else now
        if (episodeEvidenceAt - candidateStartedAt > policy.maximumGestureMs) candidateTimedOut = true
        val peakLevel = if (modelReady && !candidateContradictsModel) normalized(candidatePeakRaw) else 100
        if (
            candidateTimedOut && modelReady && !candidateContradictsModel &&
            peakLevel < MODEL_SHIFT_NEAR_CEILING &&
            candidateHasMeaningfulSpan()
        ) {
            // A bounded near gesture can be partial; a held mid-range value is more likely a shifted
            // baseline/range. Suppress fleet output first, then require a much longer hold to rebase.
            relearning = true
        }

        if (continuousDwell > 0L) {
            if (returnedFar) {
                candidateDepartureSince = UNSET_TIME
                if (now - candidateReturnSince >= continuousDwell) {
                    if (candidateDepartureQualified) {
                        completeCandidate(raw, candidateReturnSince, now)
                    } else {
                        rejectCandidate(raw, now)
                    }
                    return
                }
            } else {
                val provesDeparture = label == GuidedLabel.NEAR || abs(delta) >= departureGate(modelReady)
                if (!candidateDepartureQualified) {
                    if (provesDeparture) {
                        if (candidateDepartureSince == UNSET_TIME) candidateDepartureSince = now
                        if (now - candidateDepartureSince >= continuousDwell) {
                            candidateDepartureQualified = true
                        }
                    } else {
                        candidateDepartureSince = UNSET_TIME
                    }
                }
            }
        } else if (returnedFar && now - candidateStartedAt >= policy.minimumExcursionMs) {
            completeCandidate(raw, now, now)
            return
        }

        // Dense streams must prove that an opposite-direction departure persists before the
        // current model is failed closed. Sub-dwell pulses are rejected on their sustained return.
        if (candidateContradictsModel && candidateDepartureQualified) relearning = true

        // A fully-near correctly directed hold remains presence. Opposite polarity or a prolonged
        // sub-near plateau is behavioral-epoch evidence and may become a new far baseline.
        if (
            !modelReady &&
            now - candidateStartedAt >= policy.changePointHoldMs &&
            candidateStartedAt - learningBaselineSince >= 0L &&
            now - candidateStartedAt >= max(
                max(policy.farArmMs, policy.minimumExcursionMs),
                (candidateStartedAt - learningBaselineSince) * INITIAL_DWELL_NUMERATOR /
                    INITIAL_DWELL_DENOMINATOR,
            ) &&
            label != GuidedLabel.NEAR
        ) {
            promoteHeldCandidateAsFar(raw, now)
            return
        }

        if (
            (candidateContradictsModel || relearning && peakLevel < MODEL_SHIFT_NEAR_CEILING) &&
            now - candidateStartedAt >= policy.changePointHoldMs &&
            label != GuidedLabel.NEAR
        ) {
            startFreshLearning(raw, now, isRelearning = true, includeAsFar = true)
        }
    }

    private fun completeCandidate(returnRaw: Float, episodeEndedAt: Long, observedAt: Long) {
        val priorSpan = if (farRaw.isFinite() && nearRaw.isFinite()) abs(nearRaw - farRaw) else 0f
        if (!candidateHasMeaningfulSpan()) {
            cancelCandidate(restoreReadyModel = true)
            if (modelReady) {
                currentHealth = HealthStatus.HEALTHY
                currentLevel = normalized(returnRaw)
                currentPresence = hystereticPresence(currentLevel!!)
                updateFarArm(observedAt, currentLevel!!)
            }
            return
        }

        val boundedDeliberateShape = !candidateTimedOut &&
            episodeEndedAt - candidateStartedAt in policy.minimumExcursionMs..policy.maximumGestureMs
        borrowedOutput.completedEpisode = true
        borrowedOutput.episodeDurationMs = episodeEndedAt - candidateStartedAt
        borrowedOutput.episodePeakLevel = if (priorSpan > 0f) {
            (candidatePeakDelta / priorSpan * 100f).roundToInt().coerceIn(0, 100)
        } else {
            100
        }
        val observedMode = classifyCandidateMode()
        if (modelReady && candidateContradictsModel) {
            completeContradictoryCandidate(returnRaw, episodeEndedAt, observedAt)
            return
        }
        if (!modelReady) {
            if (!boundedDeliberateShape) {
                completedExcursions++
                clearCandidateFields()
                learningBaselineSince = episodeEndedAt
                setLearningOutput()
                return
            }
            val candidateDuration = episodeEndedAt - candidateStartedAt
            val baselineDwell = (candidateStartedAt - learningBaselineSince).coerceAtLeast(0L)
            val minimumDwell = max(policy.farArmMs, policy.minimumExcursionMs)
            val baselineIsFar = candidateGuidedNear ||
                baselineDwell >= minimumDwell &&
                baselineDwell * INITIAL_DWELL_DENOMINATOR >= candidateDuration * INITIAL_DWELL_NUMERATOR
            val candidateIsFar = !candidateGuidedNear &&
                candidateDuration >= minimumDwell &&
                candidateDuration * INITIAL_DWELL_DENOMINATOR >= baselineDwell * INITIAL_DWELL_NUMERATOR
            if (!baselineIsFar && !candidateIsFar) {
                completedExcursions++
                clearCandidateFields()
                learningBaselineSince = episodeEndedAt
                setLearningOutput()
                return
            }
            if (candidateIsFar) {
                val originalBaseline = farRaw
                farRaw = candidatePeakRaw
                nearRaw = originalBaseline
                farNoise = numericFloor(farRaw, nearRaw) * 0.25f
            } else {
                nearRaw = candidatePeakRaw
            }
            polarity = inferredPolarity(farRaw, nearRaw)
            mode = observedMode
            modelReady = true
            modelSequence++
            verifyingSeed = false
            relearning = false
            completedExcursions++
            if (baselineIsFar && boundedDeliberateShape) {
                deliberateExamples++
                borrowedOutput.deliberateExample = true
            }
            weakExcursionCount = 0
            weakExcursionMeanDelta = 0f
            clearCandidateFields()
            currentHealth = HealthStatus.HEALTHY
            currentLevel = normalized(returnRaw)
            currentPresence = currentLevel!! >= policy.nearEnterLevel
            farStableSince = UNSET_TIME
            updateFarArm(observedAt, currentLevel!!)
            return
        }

        updateModeEvidence(observedMode)
        clearContradictoryEvidence()
        val span = max(abs(nearRaw - farRaw), numericFloor(farRaw, nearRaw))
        val peakLevel = ((candidatePeakDelta / span) * 100f).roundToInt().coerceIn(0, 100)
        val deliberateExample =
            candidateQualifiedAt != UNSET_TIME &&
            peakLevel >= policy.nearEnterLevel &&
            boundedDeliberateShape
        val deliberate = candidateEligible && deliberateExample &&
            episodeEndedAt - candidateQualifiedAt >= policy.minimumExcursionMs

        // Follow stronger excursions quickly and ordinary full excursions slowly. Partial gestures do
        // not shrink the learned span; repeated compressed-range hardware changes are handled by the
        // bounded change-point path instead of silently making actuation more sensitive.
        val spanRatio = candidatePeakDelta / span
        var weakRangeRelearned = false
        when {
            spanRatio > 1.05f -> {
                nearRaw += STRONG_NEAR_ADAPT_ALPHA * (candidatePeakRaw - nearRaw)
                weakExcursionCount = 0
                weakExcursionMeanDelta = 0f
            }
            spanRatio >= 0.80f -> {
                nearRaw += READY_NEAR_ADAPT_ALPHA * (candidatePeakRaw - nearRaw)
                weakExcursionCount = 0
                weakExcursionMeanDelta = 0f
            }
            spanRatio >= WEAK_RELEARN_MINIMUM_FRACTION -> {
                val consistent = weakExcursionCount == 0 ||
                    abs(candidatePeakDelta - weakExcursionMeanDelta) <=
                    max(weakExcursionMeanDelta * WEAK_RELEARN_TOLERANCE, departureGate(true))
                if (!consistent) {
                    weakExcursionCount = 1
                    weakExcursionMeanDelta = candidatePeakDelta
                } else {
                    weakExcursionCount++
                    weakExcursionMeanDelta +=
                        (candidatePeakDelta - weakExcursionMeanDelta) / weakExcursionCount.toFloat()
                }
                if (weakExcursionCount >= WEAK_RELEARN_EXCURSIONS) {
                    nearRaw = farRaw + if (polarity == Polarity.NEAR_IS_LOWER) {
                        -weakExcursionMeanDelta
                    } else {
                        weakExcursionMeanDelta
                    }
                    weakExcursionCount = 0
                    weakExcursionMeanDelta = 0f
                    weakRangeRelearned = true
                    modelSequence++
                }
            }
        }
        completedExcursions++
        if (deliberate) {
            deliberateExamples++
            borrowedOutput.deliberateExample = true
        }
        clearCandidateFields()
        relearning = false
        currentHealth = HealthStatus.HEALTHY
        currentLevel = normalized(returnRaw)
        currentPresence = hystereticPresence(currentLevel!!)
        farStableSince = if (currentPresence == false && currentLevel!! <= policy.farArmLevel) observedAt else UNSET_TIME
        if (deliberate && !weakRangeRelearned) {
            gestureSequence++
            borrowedOutput.gesture = true
            cooldownUntil = observedAt + policy.cooldownMs
            currentActuation = ActuationStatus.COOLDOWN
        } else {
            updateFarArm(observedAt, currentLevel!!)
        }
    }

    /** Keep the timeout and return paths on one admission threshold. A candidate too small to
     * become a completed episode must not fail the published model while it is still in flight. */
    private fun candidateHasMeaningfulSpan(): Boolean {
        if (candidateGuidedNear) return true
        val gate = departureGate(modelReady)
        val priorSpan = if (farRaw.isFinite() && nearRaw.isFinite()) abs(nearRaw - farRaw) else 0f
        val minimumMeaningful = if (candidateContradictsModel && priorSpan > 0f) {
            max(gate * 1.25f, priorSpan * CONTRADICTORY_MINIMUM_SPAN_FRACTION)
        } else {
            max(gate * 1.25f, guidedDepartureGate())
        }
        return candidatePeakDelta >= minimumMeaningful
    }

    private fun completeContradictoryCandidate(returnRaw: Float, episodeEndedAt: Long, observedAt: Long) {
        val priorSpan = abs(nearRaw - farRaw)
        val tolerance = max(departureGate(true), priorSpan * CONTRADICTORY_CONSISTENCY_FRACTION)
        val consistent = contradictoryEvidenceCount == 0 ||
            abs(candidatePeakRaw - contradictoryPeakRaw) <= tolerance
        if (!consistent) {
            contradictoryEvidenceCount = 0
            contradictoryPeakRaw = Float.NaN
        }
        contradictoryEvidenceCount++
        contradictoryPeakRaw = if (contradictoryEvidenceCount == 1) {
            candidatePeakRaw
        } else {
            contradictoryPeakRaw + (candidatePeakRaw - contradictoryPeakRaw) /
                contradictoryEvidenceCount.toFloat()
        }
        contradictoryEvidenceAt = episodeEndedAt
        completedExcursions++

        if (contradictoryEvidenceCount < CONTRADICTORY_CONFIRMATIONS) {
            clearCandidateFields()
            relearning = false
            currentHealth = HealthStatus.HEALTHY
            currentLevel = normalized(returnRaw)
            currentPresence = hystereticPresence(currentLevel!!)
            farStableSince = UNSET_TIME
            updateFarArm(observedAt, currentLevel!!)
            return
        }

        if (!prepareWakeEvidenceInvalidation()) {
            currentHealth = HealthStatus.MODEL_SHIFT
            currentPresence = null
            currentLevel = null
            return
        }
        nearRaw = contradictoryPeakRaw
        polarity = inferredPolarity(farRaw, nearRaw)
        mode = classifyCandidateMode()
        commitWakeEvidenceInvalidation()
        modelReady = true
        modelSequence++
        verifyingSeed = false
        relearning = false
        weakExcursionCount = 0
        weakExcursionMeanDelta = 0f
        clearContradictoryEvidence()
        clearCandidateFields()
        currentHealth = HealthStatus.HEALTHY
        currentLevel = normalized(returnRaw)
        currentPresence = currentLevel!! >= policy.nearEnterLevel
        farStableSince = UNSET_TIME
        updateFarArm(observedAt, currentLevel!!)
    }

    private fun clearContradictoryEvidence() {
        contradictoryEvidenceCount = 0
        contradictoryPeakRaw = Float.NaN
        contradictoryEvidenceAt = UNSET_TIME
    }

    private fun commitWakeEvidenceInvalidation() {
        deliberateExamples = 0
        wakeEvidenceGeneration++
    }

    private fun addBaselineSample(raw: Float, now: Long, guidedFar: Boolean) {
        if (baselineCount == 0) {
            baselineStartedAt = now
            baselineMean = raw
            baselineM2 = 0f
        } else {
            val count = baselineCount + 1
            val delta = raw - baselineMean
            baselineMean += delta / count.toFloat()
            baselineM2 += delta * (raw - baselineMean)
        }
        baselineCount++
        if (guidedFar) guidedFarCount++

        val guidedReady = guidedFarCount >= policy.guidedFarSamples &&
            now - baselineStartedAt >= policy.guidedBaselineDurationMs
        val passiveReady = baselineCount >= policy.baselineSamples &&
            now - baselineStartedAt >= policy.baselineDurationMs
        if (!guidedReady && !passiveReady) return

        establishBaseline()
    }

    private fun establishBaseline() {
        farRaw = baselineMean
        val variance = if (baselineCount > 1) baselineM2 / (baselineCount - 1).toFloat() else 0f
        farNoise = max(sqrt(max(variance, 0f)), numericFloor(baselineMean, baselineMean) * 0.25f)
        baselineEstablished = true
        learningBaselineSince = baselineStartedAt
    }

    private fun promoteHeldCandidateAsFar(raw: Float, now: Long) {
        if (baselineCount != 1) {
            // Only an on-change source with one retained boot sample has enough evidence to infer
            // that the held state is the real clear baseline. A dense baseline changing regimes must
            // learn the new regime from scratch, never manufacture an inverted ready model.
            startFreshLearning(raw, now, isRelearning = false, includeAsFar = true)
            return
        }
        val originalBaseline = farRaw
        farRaw = candidatePeakRaw
        nearRaw = originalBaseline
        farNoise = numericFloor(farRaw, nearRaw) * 0.25f
        polarity = inferredPolarity(farRaw, nearRaw)
        mode = classifyCandidateMode()
        modelReady = true
        modelSequence++
        verifyingSeed = false
        relearning = false
        completedExcursions++
        clearCandidateFields()
        currentHealth = HealthStatus.HEALTHY
        currentLevel = normalized(raw)
        currentPresence = hystereticPresence(currentLevel!!)
        farStableSince = now
        updateFarArm(now, currentLevel!!)
    }

    private fun markSeedVerified() {
        verifyingSeed = false
        modelReady = true
        relearning = false
    }

    private fun updateCandidateQualification(raw: Float, now: Long) {
        if (!modelReady || candidateContradictsModel || candidateQualifiedAt != UNSET_TIME) return
        if (normalized(raw) >= policy.nearEnterLevel) candidateQualifiedAt = now
    }

    private fun classifyCandidateMode(): Mode {
        if (candidatePeakDelta <= 0f) return Mode.UNKNOWN
        var interiorSamples = 0
        for (i in 0 until excursionValueCount) {
            val progress = abs(excursionValues[i] - farRaw) / candidatePeakDelta
            if (progress in GRADED_INTERIOR_LOW..GRADED_INTERIOR_HIGH) interiorSamples++
        }
        return if (interiorSamples >= GRADED_INTERIOR_SAMPLES) Mode.GRADED else Mode.BINARY
    }

    private fun updateModeEvidence(observed: Mode) {
        when {
            observed == Mode.GRADED -> mode = Mode.GRADED
            mode == Mode.UNKNOWN -> mode = Mode.BINARY
        }
    }

    private fun recordExcursionValue(raw: Float) {
        excursionValues[excursionWriteIndex] = raw
        excursionWriteIndex = (excursionWriteIndex + 1) % excursionValues.size
        if (excursionValueCount < excursionValues.size) excursionValueCount++
    }

    private fun adaptFar(raw: Float, alpha: Float) {
        if (!farRaw.isFinite()) return
        val prior = farRaw
        farRaw += alpha * (raw - farRaw)
        val deviation = abs(raw - prior)
        val adaptedNoise = farNoise + NOISE_ADAPT_ALPHA * (deviation - farNoise)
        // Before the first model exists, the complete passive baseline is the strongest evidence we
        // have about idle variance. Continue tracking offset drift, but never let a later quiet spell
        // weaken that admission gate until a real excursion has established the signal range.
        farNoise = if (modelReady) adaptedNoise else max(farNoise, adaptedNoise)
        farNoise = max(farNoise, numericFloor(raw, prior) * 0.25f)
    }

    private fun updateFarArm(now: Long, level: Int) {
        if (!modelReady || verifyingSeed || relearning || currentHealth != HealthStatus.HEALTHY) {
            farStableSince = UNSET_TIME
            currentActuation = if (currentHealth == HealthStatus.LEARNING) {
                ActuationStatus.SUPPRESSED_LEARNING
            } else {
                ActuationStatus.SUPPRESSED_UNHEALTHY
            }
            return
        }
        if (cooldownUntil != UNSET_TIME && now < cooldownUntil) {
            currentActuation = ActuationStatus.COOLDOWN
            return
        }
        cooldownUntil = UNSET_TIME
        if (currentPresence == false && level <= policy.farArmLevel) {
            if (farStableSince == UNSET_TIME) farStableSince = now
            currentActuation = if (now - farStableSince >= policy.farArmMs) {
                ActuationStatus.ARMED
            } else {
                ActuationStatus.ARMING
            }
        } else {
            farStableSince = UNSET_TIME
            currentActuation = ActuationStatus.ARMING
        }
    }

    private fun normalized(raw: Float): Int {
        val span = nearRaw - farRaw
        if (!span.isFinite() || abs(span) < numericFloor(farRaw, nearRaw)) return 0
        val level = (((raw - farRaw) / span) * 100f).roundToInt().coerceIn(0, 100)
        return if (mode == Mode.BINARY) {
            if (level >= BINARY_LEVEL_SPLIT) 100 else 0
        } else {
            level
        }
    }

    private fun hystereticPresence(level: Int): Boolean {
        return when (currentPresence) {
            true -> level > policy.farExitLevel
            false -> level >= policy.nearEnterLevel
            null -> level >= policy.nearEnterLevel
        }
    }

    private fun departureGate(hasModel: Boolean): Float {
        var gate = max(
            numericFloor(farRaw, latestRaw),
            farNoise * policy.noiseMultiplier,
        )
        if (hasModel && nearRaw.isFinite()) gate = max(gate, abs(nearRaw - farRaw) * MODEL_DEPARTURE_FRACTION)
        return gate
    }

    private fun guidedDepartureGate(): Float =
        max(numericFloor(farRaw, latestRaw), farNoise * GUIDED_NOISE_MULTIPLIER)

    private fun startFreshLearning(
        raw: Float,
        now: Long,
        isRelearning: Boolean,
        includeAsFar: Boolean,
    ) {
        if (!prepareWakeEvidenceInvalidation()) {
            currentHealth = HealthStatus.MODEL_SHIFT
            currentPresence = null
            currentLevel = null
            return
        }
        modelReady = false
        verifyingSeed = false
        relearning = isRelearning
        commitWakeEvidenceInvalidation()
        mode = Mode.UNKNOWN
        polarity = Polarity.UNKNOWN
        farRaw = Float.NaN
        nearRaw = Float.NaN
        farNoise = numericFloor(raw, raw)
        baselineEstablished = false
        baselineCount = 0
        guidedFarCount = 0
        baselineStartedAt = UNSET_TIME
        learningBaselineSince = UNSET_TIME
        baselineMean = 0f
        baselineM2 = 0f
        verifyFarCount = 0
        verifyGuidedFarCount = 0
        verifyStartedAt = UNSET_TIME
        mismatchCount = 0
        mismatchStartedAt = UNSET_TIME
        clearContradictoryEvidence()
        clearCandidateFields()
        currentLevel = null
        currentPresence = null
        currentHealth = if (isRelearning) HealthStatus.MODEL_SHIFT else HealthStatus.LEARNING
        currentActuation = if (isRelearning) {
            ActuationStatus.SUPPRESSED_UNHEALTHY
        } else {
            ActuationStatus.SUPPRESSED_LEARNING
        }
        farStableSince = UNSET_TIME
        cooldownUntil = UNSET_TIME
        if (includeAsFar) addBaselineSample(raw, now, guidedFar = false)
    }

    private fun failInput(status: HealthStatus) {
        inputHealthy = false
        lastFault = status
        cancelCandidate(restoreReadyModel = true)
        farStableSince = UNSET_TIME
        currentLevel = null
        currentPresence = null
        currentHealth = status
        currentActuation = ActuationStatus.SUPPRESSED_UNHEALTHY
    }

    private fun cancelCandidate(restoreReadyModel: Boolean) {
        clearCandidateFields()
        if (restoreReadyModel && modelReady) relearning = false
    }

    /** A dense pulse that returned before sustaining departure evidence is noise, not an episode. */
    private fun rejectCandidate(raw: Float, now: Long) {
        cancelCandidate(restoreReadyModel = true)
        if (!modelReady) return
        currentHealth = HealthStatus.HEALTHY
        currentLevel = normalized(raw)
        currentPresence = hystereticPresence(currentLevel!!)
        updateFarArm(now, currentLevel!!)
    }

    private fun clearCandidateFields() {
        candidateActive = false
        candidateStartedAt = UNSET_TIME
        candidatePeakRaw = Float.NaN
        candidatePeakDelta = 0f
        candidateEligible = false
        candidateQualifiedAt = UNSET_TIME
        candidateDepartureSince = UNSET_TIME
        candidateDepartureQualified = false
        candidateReturnSince = UNSET_TIME
        candidateContinuousEvidence = false
        candidateTimedOut = false
        candidateContradictsModel = false
        candidateGuidedNear = false
        excursionValueCount = 0
        excursionWriteIndex = 0
    }

    private fun setLearningOutput() {
        currentLevel = null
        currentPresence = null
        currentHealth = if (relearning) HealthStatus.MODEL_SHIFT else HealthStatus.LEARNING
        currentActuation = if (relearning) {
            ActuationStatus.SUPPRESSED_UNHEALTHY
        } else {
            ActuationStatus.SUPPRESSED_LEARNING
        }
    }

    private fun learningStatus(): LearningStatus = when {
        relearning -> LearningStatus.RELEARNING
        verifyingSeed -> LearningStatus.VERIFYING_SEED
        modelReady -> LearningStatus.READY
        baselineEstablished -> LearningStatus.LEARNING_EXCURSION
        else -> LearningStatus.LEARNING_FAR
    }

    private fun readiness(): Int = when {
        modelReady && !relearning -> 100
        verifyingSeed -> (70 + verifyFarCount * 25 / max(policy.seedFarSamples, 1)).coerceAtMost(95)
        !baselineEstablished -> {
            val required = if (guidedFarCount > 0) policy.guidedFarSamples else policy.baselineSamples
            (baselineCount * 50 / max(required, 1)).coerceAtMost(49)
        }
        candidateActive -> {
            val gate = max(departureGate(false), numericFloor(farRaw, latestRaw))
            (50 + (candidatePeakDelta / gate * 10f).roundToInt()).coerceAtMost(95)
        }
        else -> 50
    }

    private fun beginOutputCall(accepted: Boolean) {
        borrowedOutput.accepted = accepted
        borrowedOutput.gesture = false
        borrowedOutput.deliberateExample = false
        borrowedOutput.completedEpisode = false
        borrowedOutput.episodeDurationMs = 0L
        borrowedOutput.episodePeakLevel = 0
    }

    private fun admitTime(now: Long): Boolean {
        if (lastCallAt != UNSET_TIME && now < lastCallAt) return false
        lastCallAt = now
        return true
    }

    private fun syncOutput(): Output {
        borrowedOutput.raw = latestRaw
        borrowedOutput.normalizedLevel = currentLevel
        borrowedOutput.presence = currentPresence
        borrowedOutput.gestureSequence = gestureSequence
        borrowedOutput.completedExcursions = completedExcursions
        borrowedOutput.deliberateExamples = deliberateExamples
        borrowedOutput.wakeEvidenceGeneration = wakeEvidenceGeneration
        borrowedOutput.modelSequence = modelSequence
        borrowedOutput.mode = mode
        borrowedOutput.polarity = polarity
        borrowedOutput.learning = learningStatus()
        borrowedOutput.health = currentHealth
        borrowedOutput.actuation = currentActuation
        borrowedOutput.readiness = readiness()
        return borrowedOutput
    }

    private fun resetInternal(resetGestureSequence: Boolean) {
        lastCallAt = UNSET_TIME
        lastValidAt = UNSET_TIME
        latestRaw = Float.NaN
        inputHealthy = false
        lastFault = HealthStatus.NO_DATA
        mode = Mode.UNKNOWN
        polarity = Polarity.UNKNOWN
        farRaw = Float.NaN
        nearRaw = Float.NaN
        farNoise = policy.minimumAbsoluteDelta
        modelReady = false
        verifyingSeed = false
        relearning = false
        completedExcursions = 0
        deliberateExamples = 0
        wakeEvidenceGeneration = 0L
        weakExcursionCount = 0
        weakExcursionMeanDelta = 0f
        clearContradictoryEvidence()
        modelSequence = 0L
        baselineEstablished = false
        baselineCount = 0
        guidedFarCount = 0
        baselineStartedAt = UNSET_TIME
        learningBaselineSince = UNSET_TIME
        baselineMean = 0f
        baselineM2 = 0f
        verifyFarCount = 0
        verifyGuidedFarCount = 0
        verifyStartedAt = UNSET_TIME
        mismatchCount = 0
        mismatchStartedAt = UNSET_TIME
        clearCandidateFields()
        currentLevel = null
        currentPresence = null
        currentHealth = HealthStatus.NO_DATA
        currentActuation = ActuationStatus.SUPPRESSED_LEARNING
        farStableSince = UNSET_TIME
        cooldownUntil = UNSET_TIME
        if (resetGestureSequence) gestureSequence = 0L
        beginOutputCall(accepted = false)
    }

    private fun validAnchors(far: Float, near: Float, statedPolarity: Polarity): Boolean {
        if (!far.isFinite() || !near.isFinite() || statedPolarity == Polarity.UNKNOWN) return false
        if (abs(near - far) < numericFloor(far, near)) return false
        return inferredPolarity(far, near) == statedPolarity
    }

    /** Float-resolution-aware raw epsilon. The floor follows representable precision at large vendor
     * offsets without imposing a behavioral threshold in any sensor's native units. */
    private fun numericFloor(first: Float, second: Float): Float {
        var scale = 1f
        if (first.isFinite()) scale = max(scale, abs(first))
        if (second.isFinite()) scale = max(scale, abs(second))
        return max(policy.minimumAbsoluteDelta, java.lang.Math.ulp(scale) * NUMERIC_ULPS)
    }

    private fun inferredPolarity(far: Float, near: Float): Polarity = when {
        !far.isFinite() || !near.isFinite() || far == near -> Polarity.UNKNOWN
        near < far -> Polarity.NEAR_IS_LOWER
        else -> Polarity.NEAR_IS_HIGHER
    }

    private fun directionOf(delta: Float): Polarity = when {
        delta < 0f -> Polarity.NEAR_IS_LOWER
        delta > 0f -> Polarity.NEAR_IS_HIGHER
        else -> Polarity.UNKNOWN
    }

    companion object {
        const val SNAPSHOT_VERSION = 1

        private const val EXCURSION_VALUE_CAPACITY = 64
        private const val NUMERIC_ULPS = 1f
        private const val GRADED_INTERIOR_LOW = 0.15f
        private const val GRADED_INTERIOR_HIGH = 0.85f
        private const val GRADED_INTERIOR_SAMPLES = 1
        private const val BINARY_LEVEL_SPLIT = 50
        private const val MODEL_SHIFT_NEAR_CEILING = 90
        private const val INITIAL_DWELL_NUMERATOR = 3L
        private const val INITIAL_DWELL_DENOMINATOR = 2L
        private const val MODEL_DEPARTURE_FRACTION = 0.08f
        private const val RETURN_GATE_FRACTION = 0.75f
        private const val PEAK_RETURN_FRACTION = 0.12f
        private const val CONTRADICTORY_MINIMUM_SPAN_FRACTION = 0.25f
        private const val CONTRADICTORY_CONSISTENCY_FRACTION = 0.25f
        private const val CONTRADICTORY_CONFIRMATIONS = 2
        private const val WEAK_RELEARN_MINIMUM_FRACTION = 0.25f
        private const val WEAK_RELEARN_TOLERANCE = 0.25f
        private const val WEAK_RELEARN_EXCURSIONS = 3
        private const val SEED_FAR_TOLERANCE_FRACTION = 0.20f
        private const val GUIDED_NOISE_MULTIPLIER = 2f
        private const val BASELINE_ADAPT_ALPHA = 0.05f
        private const val READY_FAR_ADAPT_ALPHA = 0.02f
        private const val SEED_ADAPT_ALPHA = 0.05f
        private const val READY_NEAR_ADAPT_ALPHA = 0.05f
        private const val STRONG_NEAR_ADAPT_ALPHA = 0.20f
        private const val NOISE_ADAPT_ALPHA = 0.05f
        private const val UNSET_TIME = Long.MIN_VALUE
    }
}
