package io.github.maxlyth.hapaneld.sensors

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Explicit calibration and fixed interpretation of raw samples. Observation never changes a calibration. */
internal class ProximityCalibrationEngine(
    initial: Calibration? = null,
    private val commit: (Calibration) -> Boolean = { true },
) {
    enum class Mode { BINARY, RANGED }
    enum class Stage { INTRO, CLEAR, NEAR, RETURN_CLEAR, WAVES, REVIEW, SAVED, CANCELLED, TIMED_OUT, FAILED }

    data class Calibration(
        val version: Int = 1,
        val mode: Mode,
        val clearRaw: Float,
        val nearRaw: Float,
        val nearEnter: Float = .65f,
        val clearExit: Float = .30f,
        val debounceMs: Long = 150,
        val clearArmMs: Long = 700,
        val minimumNearMs: Long = 200,
        val maximumNearMs: Long = 4_000,
        val cooldownMs: Long = 1_000,
    ) {
        init {
            require(version == 1)
            require(clearRaw.isFinite() && nearRaw.isFinite() && clearRaw != nearRaw && (nearRaw - clearRaw).isFinite())
            require(clearExit >= 0f && nearEnter <= 1f && clearExit < nearEnter)
            require(debounceMs >= 0 && clearArmMs >= debounceMs && minimumNearMs >= debounceMs)
            require(maximumNearMs >= minimumNearMs && cooldownMs >= 0)
            require(mode != Mode.BINARY || isBinaryPair(clearRaw, nearRaw))
        }

        fun level(raw: Float): Int = (((raw.toDouble() - clearRaw.toDouble()) /
            (nearRaw.toDouble() - clearRaw.toDouble())) * 100.0).coerceIn(0.0, 100.0).roundToInt()
    }

    data class Result(
        val stage: Stage?,
        val message: String,
        val accepted: Int,
        val near: Boolean?,
        val level: Int?,
        val mode: Mode?,
        val available: Boolean,
        val wakeReady: Boolean,
        val active: Boolean,
        val gesture: Boolean,
        val generation: Long,
        val calibration: Calibration?,
        val needsTick: Boolean,
    )

    private var calibration = initial
    private var detector = initial?.let(::Detector)
    private var candidate: Calibration? = null
    private var candidateDetector: Detector? = null
    private var stage: Stage? = null
    private var message = ""
    private var accepted = 0
    private var generation = 0L
    private var lastTime: Long? = null
    private var lastRaw: Float? = null
    private var lastRawCapture = false
    private var available = false
    private var sessionStarted = 0L
    private var stageStarted = 0L
    private var stableSince = 0L
    private var stableRaw: Float? = null
    private var returnClearSince: Long? = null
    private val clearSamples = Samples()
    private val nearSamples = Samples()
    private var observedNonBinary = false

    @Synchronized fun current(): Result = result()

    @Synchronized fun needsTick(): Boolean = active() || detector?.needsTick() == true

    /** Service admission can reject an emitted gesture; its provisional cooldown must then be released. */
    @Synchronized fun releaseGestureCooldown() { detector?.releaseCooldown() }

    @Synchronized fun start(now: Long): Result {
        if (!admit(now)) return result()
        if (active()) return result()
        generation++
        detector?.reset()
        discardCandidate()
        accepted = 0
        sessionStarted = now
        lastRawCapture = false
        transition(Stage.INTRO, now, "Proximity setup. Stand in front of the panel, then tap Begin.")
        return result()
    }

    @Synchronized fun action(action: String, now: Long): Result {
        if (!admit(now)) return result()
        expire(now)
        when (action.lowercase()) {
            "begin", "advance" -> if (stage == Stage.INTRO) {
                transition(Stage.CLEAR, now, "Step 1: Move away. Keep the area in front of the panel clear.")
            }
            "retry" -> if (!active()) return start(now)
            "cancel" -> if (active()) finish(Stage.CANCELLED, now, "Setup cancelled. Your previous calibration is unchanged.")
            "save" -> if (stage == Stage.REVIEW) {
                val proposed = candidate
                if (proposed == null || !available) {
                    finish(Stage.FAILED, now, "Sensor unavailable. Your previous calibration is unchanged.")
                } else if (runCatching { commit(proposed) }.getOrDefault(false)) {
                    calibration = proposed
                    detector = Detector(proposed)
                    finish(Stage.SAVED, now, "Calibration saved. Move clear before testing. Wake on wave uses these settings when enabled.")
                } else {
                    finish(Stage.FAILED, now, "Could not save calibration. Your previous calibration is unchanged.")
                }
            }
        }
        return result()
    }

    @Synchronized fun observe(raw: Float, now: Long, live: Boolean = true, calibrationLive: Boolean = live): Result {
        if (!admit(now)) return result()
        expire(now)
        if (!raw.isFinite()) return loseSource(now, "Invalid sensor reading. Your previous calibration is unchanged.")
        // A binary calibration cannot interpret an unexpected third value as a near/far edge.
        if (!active() && calibration?.mode == Mode.BINARY && raw != 0f && raw != 1f) {
            return loseSource(now, "The sensor representation changed. Start setup again.")
        }
        available = true
        lastRaw = raw
        lastRawCapture = calibrationLive
        if (active() && candidate?.let { !compatible(it, raw) } == true) {
            return loseSource(now, "The sensor representation changed. Your previous calibration is unchanged.")
        }
        if (active() && calibrationLive && raw != 0f && raw != 1f) observedNonBinary = true
        if (active()) {
            // A backfill can establish the operational state, but is never calibration evidence.
            detector?.observe(raw, now, false)
            if (calibrationLive) {
                advanceWizard(raw, now, true)
            } else {
                candidateDetector?.observe(raw, now, false)
                returnClearSince = null
                stableRaw = null
                // Do not let a timer turn retained state into capture evidence. Keep already
                // completed stages, and wait for a live observation before resuming this capture.
                if (stage == Stage.CLEAR) clearSamples.clear()
                if (stage == Stage.NEAR) nearSamples.clear()
            }
            return result()
        }
        if (!live) generation++
        val gesture = detector?.observe(raw, now, live) == true
        return result(gesture)
    }

    /** Confirm only previously observed edges: timers never manufacture raw samples or live provenance. */
    @Synchronized fun tick(now: Long): Result {
        if (!admit(now)) return result()
        expire(now)
        val gesture = detector?.tick(now) == true
        if (active() && available && lastRawCapture) lastRaw?.let { advanceWizard(it, now, false) }
        return result(gesture && !active())
    }

    @Synchronized fun sourceUnavailable(now: Long): Result {
        if (!admit(now)) return result()
        return loseSource(now, "Sensor connection lost. Your previous calibration is unchanged.")
    }

    @Synchronized fun reset(calibration: Calibration?, now: Long): Result {
        if (!admit(now)) return result()
        this.calibration = calibration
        detector = calibration?.let(::Detector)
        available = false
        lastRaw = null
        lastRawCapture = false
        generation++
        discardCandidate()
        stage = null
        message = ""
        accepted = 0
        return result()
    }

    private fun advanceWizard(raw: Float, now: Long, live: Boolean) {
        when (stage) {
            Stage.CLEAR, Stage.NEAR -> {
                if (now - stageStarted < COUNTDOWN_MS) return
                if (stage == Stage.NEAR) {
                    val clear = clearSamples.median() ?: return
                    val departure = max(separationFloor(raw, clear), clearSamples.spread() * 1.5f)
                    if (abs(raw - clear) <= departure) {
                        // Ordinary clear-state noise is not an approach. Wait without failing,
                        // and discard a partial near window if the hand moves away again.
                        nearSamples.clear()
                        stableRaw = null
                        return
                    }
                }
                val buffer = if (stage == Stage.CLEAR) clearSamples else nearSamples
                // Measure a bounded window instead of waiting for raw values to stop changing.
                // Noise is assessed against the observed clear-to-near separation below.
                if (stableRaw == null) {
                    stableRaw = raw
                    stableSince = now
                }
                if (live || buffer.size == 0) buffer.add(raw)
                if (now - stableSince < CAPTURE_HOLD_MS) return
                if (stage == Stage.CLEAR) {
                    transition(Stage.NEAR, now, "Step 2: Hold your hand at the distance where you want detection.")
                } else {
                    val clear = clearSamples.median() ?: return
                    val near = nearSamples.median() ?: return
                    val span = abs(near - clear)
                    val clearSpread = clearSamples.spread()
                    val nearSpread = nearSamples.spread()
                    if (!span.isFinite() || span <= separationFloor(clear, near) ||
                        clearSpread > span * .50f || nearSpread > span * .50f ||
                        clearSpread + nearSpread > span * .75f) {
                        finish(Stage.FAILED, now, "The clear and near readings were too noisy or too similar. Keep the area clear and try again.")
                        return
                    }
                    val mode = if (!observedNonBinary && isBinaryPair(clear, near)) Mode.BINARY else Mode.RANGED
                    candidate = Calibration(mode = mode, clearRaw = clear, nearRaw = near)
                    candidateDetector = Detector(checkNotNull(candidate))
                    transition(Stage.RETURN_CLEAR, now,
                        if (mode == Mode.BINARY) "Step 3: Move your hand away. This sensor detects near or clear; its detection distance cannot be tuned."
                        else "Step 3: Move your hand away until the area is clear.")
                }
            }
            Stage.RETURN_CLEAR -> {
                val proposed = candidate ?: return
                if (!compatible(proposed, raw)) {
                    finish(Stage.FAILED, now, "The sensor values changed. Your previous calibration is unchanged.")
                    return
                }
                if (proposed.level(raw) <= proposed.clearExit * 100f) {
                    if (returnClearSince == null) returnClearSince = now
                    candidateDetector?.observe(raw, now, false)
                    if (now - checkNotNull(returnClearSince) >= CAPTURE_HOLD_MS) {
                        transition(Stage.WAVES, now, "Step 4: Wave toward the panel, then move clear. Repeat three times, pausing between waves.")
                    }
                } else returnClearSince = null
            }
            Stage.WAVES -> {
                val proposed = candidate ?: return
                if (!compatible(proposed, raw)) {
                    finish(Stage.FAILED, now, "The sensor values changed. Your previous calibration is unchanged.")
                    return
                }
                val found = if (live) candidateDetector?.observe(raw, now, true) == true else {
                    candidateDetector?.tick(now) == true
                }
                if (found) {
                    accepted++
                    if (accepted == REQUIRED_WAVES) {
                        transition(Stage.REVIEW, now,
                            if (proposed.mode == Mode.BINARY) "Three waves detected. Detection distance is fixed by this sensor. Tap Save to use this calibration."
                            else "Three waves detected. Tap Save to use this calibration.")
                    } else message = "Wave $accepted of $REQUIRED_WAVES detected. Move clear, pause, then wave again."
                }
            }
            else -> Unit
        }
    }

    private fun transition(next: Stage, now: Long, text: String) {
        stage = next
        stageStarted = now
        stableSince = now
        stableRaw = null
        returnClearSince = null
        message = text
    }

    private fun finish(next: Stage, now: Long, text: String) {
        generation++
        detector?.reset()
        discardCandidate()
        transition(next, now, text)
    }

    private fun discardCandidate() {
        candidate = null
        candidateDetector = null
        clearSamples.clear()
        nearSamples.clear()
        stableRaw = null
        returnClearSince = null
        observedNonBinary = false
    }

    private fun expire(now: Long) {
        if (active() && now - sessionStarted >= SESSION_TIMEOUT_MS) {
            finish(Stage.TIMED_OUT, now, "Setup timed out. Your previous calibration is unchanged.")
        }
    }

    private fun loseSource(now: Long, text: String): Result {
        available = false
        lastRaw = null
        lastRawCapture = false
        if (active()) finish(Stage.FAILED, now, text) else {
            generation++
            detector?.reset()
            message = text
        }
        return result()
    }

    private fun admit(now: Long): Boolean {
        if (now < 0 || lastTime?.let { now < it } == true) {
            loseSource(lastTime ?: 0L, "Sensor timing was invalid. Your previous calibration is unchanged.")
            return false
        }
        lastTime = now
        return true
    }

    private fun active() = when (stage) {
        Stage.INTRO, Stage.CLEAR, Stage.NEAR, Stage.RETURN_CLEAR, Stage.WAVES, Stage.REVIEW -> true
        else -> false
    }

    private fun result(gesture: Boolean = false): Result {
        val interpreting = if (active()) candidate else calibration
        val state = if (active()) candidateDetector else detector
        return Result(
            stage, message, accepted,
            if (available) state?.near else null,
            if (available) interpreting?.let { c -> lastRaw?.let(c::level) } else null,
            interpreting?.mode, available,
            available && !active() && calibration != null && detector?.hasClearEvidence == true,
            active(), gesture, generation, calibration, needsTick(),
        )
    }

    /** Hysteretic reporting plus pulse-duration debounce; cooldown begins only on an accepted cycle. */
    private class Detector(private val calibration: Calibration) {
        var near: Boolean? = null
            private set
        var hasClearEvidence = false
            private set
        private var rawNear: Boolean? = null
        private var pendingSince = 0L
        private var clearSince: Long? = null
        private var nearSince: Long? = null
        private var eligible = false
        private var cooldownUntil = 0L
        private var pendingGesture = false

        fun reset() {
            near = null
            rawNear = null
            hasClearEvidence = false
            clearSince = null
            nearSince = null
            eligible = false
            cooldownUntil = 0L
            pendingGesture = false
        }

        fun observe(raw: Float, now: Long, live: Boolean): Boolean {
            val level = calibration.level(raw)
            val next = when {
                level >= calibration.nearEnter * 100f -> true
                level <= calibration.clearExit * 100f -> false
                else -> rawNear
            }
            if (next == null) return false
            if (!live) {
                eligible = false
                nearSince = null
                pendingGesture = false
            }
            if (next != rawNear) {
                pendingSince = now
                pendingGesture = false
                if (next) {
                    eligible = live && clearSince?.let { now - it >= calibration.clearArmMs } == true && now >= cooldownUntil
                    nearSince = if (live) now else null
                    clearSince = null
                } else {
                    val duration = nearSince?.let { now - it }
                    pendingGesture = live && eligible && duration != null &&
                        duration >= max(calibration.minimumNearMs, calibration.debounceMs) && duration <= calibration.maximumNearMs
                    eligible = false
                    nearSince = null
                    clearSince = now
                }
                rawNear = next
            }
            return tick(now)
        }

        fun releaseCooldown() { cooldownUntil = 0L }

        fun needsTick(): Boolean = pendingGesture || rawNear != near || nearSince != null

        fun tick(now: Long): Boolean {
            var gesture = false
            if (rawNear != null && now - pendingSince >= calibration.debounceMs) {
                near = rawNear
                if (near == false) {
                    hasClearEvidence = true
                    if (pendingGesture) {
                        pendingGesture = false
                        cooldownUntil = now + calibration.cooldownMs
                        gesture = true
                    }
                }
            }
            if (nearSince?.let { now - it > calibration.maximumNearMs } == true) {
                nearSince = null
                eligible = false
            }
            return gesture
        }
    }

    private class Samples {
        private val values = FloatArray(32)
        var size = 0
            private set
        private var next = 0
        fun add(raw: Float) {
            values[next] = raw
            next = (next + 1) % values.size
            size = (size + 1).coerceAtMost(values.size)
        }
        fun clear() { size = 0; next = 0 }
        fun median(): Float? {
            if (size == 0) return null
            val sorted = values.copyOf(size).apply { sort() }
            return sorted[size / 2]
        }
        fun spread(): Float {
            if (size == 0) return 0f
            val sorted = values.copyOf(size).apply { sort() }
            return if (size < 10) sorted[size - 1] - sorted[0]
            else sorted[(size - 1) * 9 / 10] - sorted[(size - 1) / 10]
        }
    }

    companion object {
        const val SESSION_TIMEOUT_MS = 300_000L
        const val COUNTDOWN_MS = 1_200L
        const val CAPTURE_HOLD_MS = 800L
        const val REQUIRED_WAVES = 3
        private fun isBinaryPair(clear: Float, near: Float) = (clear == 0f && near == 1f) || (clear == 1f && near == 0f)
        private fun compatible(calibration: Calibration, raw: Float) = calibration.mode != Mode.BINARY || raw == 0f || raw == 1f
        private fun separationFloor(a: Float, b: Float) = max(Math.ulp(a), Math.ulp(b)) * 4f
    }
}
