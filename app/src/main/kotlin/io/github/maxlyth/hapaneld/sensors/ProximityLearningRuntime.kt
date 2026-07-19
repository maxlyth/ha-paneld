package io.github.maxlyth.hapaneld.sensors

import android.content.Context
import android.os.SystemClock
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Lifecycle, onboarding, persistence, and fleet-reporting boundary around [ProximityLearningEngine].
 * Raw samples stay in memory; only a compact model and five-minute aggregates reach SQLite.
 */
internal class ProximityLearningRuntime(
    context: Context,
    config: Config,
    sourceIdentity: String,
    private val sparseLearningSource: Boolean,
    private val legacySeedEligible: Boolean,
) : AutoCloseable {
    /** Borrowed result; callers consume it before invoking the runtime again. */
    class Decision internal constructor() {
        var reportMask: Int = ProximityReportGate.NONE
            internal set
        var near: Boolean? = null
            internal set
        var normalizedLevel: Int? = null
            internal set
        var deliberateGesture: Boolean = false
            internal set
    }

    private enum class SessionKind { TEACH, TEST }

    private data class Session(
        val kind: SessionKind,
        val expiresAt: Long,
        var accepted: Int = 0,
        var message: String,
    )

    /** Mutable mirror of the engine's borrowed output. Keeping one instance avoids per-sample churn. */
    private class View {
        var learning = ProximityLearningEngine.LearningStatus.LEARNING_FAR
        var health = ProximityLearningEngine.HealthStatus.NO_DATA
        var mode = ProximityLearningEngine.Mode.UNKNOWN
        var polarity = ProximityLearningEngine.Polarity.UNKNOWN
        var readiness = 0
        var normalizedLevel: Int? = null
        var near: Boolean? = null
        var completedExcursions = 0
        var deliberateExamples = 0
        var modelSequence = 0L
    }

    private data class Rollup(
        var bucket: Long = Long.MIN_VALUE,
        var samples: Int = 0,
        var min: Double = Double.POSITIVE_INFINITY,
        var max: Double = Double.NEGATIVE_INFINITY,
        var sum: Double = 0.0,
        var squareSum: Double = 0.0,
        var excursions: Int = 0,
        var gestures: Int = 0,
    )

    private data class PendingEvidence(
        val rollups: List<EntityCatalogStore.ProximityRollupRow>,
        val episodes: List<EntityCatalogStore.ProximityEpisodeRow>,
    )

    private val appContext = context.applicationContext
    private val fingerprint = fingerprint(sourceIdentity)
    private val engine = ProximityLearningEngine(learningPolicy(sparseLearningSource))
    private val store = EntityCatalogStore(appContext)
    private val persistence = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(8),
        { task -> Thread(task, "proximity-persistence").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private val view = View()
    private var guidedReady = false
    private var sourceAvailable = false
    private var session: Session? = null
    private var lastSessionMessage = ""
    private val reportGate = ProximityReportGate()
    private var rollup = Rollup()
    private val evidenceLock = Any()
    private val pendingRollups = ArrayList<EntityCatalogStore.ProximityRollupRow>(4)
    private val pendingEpisodes = ArrayList<EntityCatalogStore.ProximityEpisodeRow>(4)
    private var lastDurableReadyModel: EntityCatalogStore.ProximityModelRow? = null
    private val borrowedDecision = Decision()
    private var lastObservedRaw = Float.NaN
    private var lastObservedAt = 0L
    private var closed = false
    private val closeCompletion = CompletableFuture<Unit>()

    init {
        // Always consume the retired configuration. A durable adaptive model wins, while a complete
        // old user capture is a one-time warm start only when no compatible model exists.
        val legacySeed = config.consumeLegacyProximityLearningSeed()
        val stored = runCatching { store.readProximityModel(fingerprint) }.getOrNull()
            ?.takeIf { isRestorableModel(it, fingerprint) }
        val restored = stored?.let { restore(it.snapshotJson) } == true
        if (restored) lastDurableReadyModel = stored
        // Old captures have no firmware/source fingerprint. Consume and purge them above, but warm only
        // a continuous HAL whose repeated live readings can disprove stale or inverted anchors.
        eligibleLegacySeed(restored, legacySeed, legacySeedEligible)?.let { seed ->
            engine.warmSeed(
                ProximityLearningEngine.WarmSeed(
                    farRaw = seed.farRaw,
                    nearRaw = seed.nearRaw,
                ),
            )
        }
        copy(engine.current())
    }

    @Synchronized
    fun observe(
        raw: Float,
        elapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
        sparseReporting: Boolean = sparseLearningSource,
    ): Decision {
        if (closed) return result(ProximityReportGate.NONE, null, null, false)
        refreshSession(elapsedRealtimeMs)
        val activeSession = session
        val wasWaveReady = waveReadyLocked()
        val priorModelSequence = view.modelSequence
        val priorCompletedExcursions = view.completedExcursions

        sourceAvailable = raw.isFinite()
        if (sourceAvailable) {
            lastObservedRaw = raw
            lastObservedAt = elapsedRealtimeMs
        }
        val sampleCost = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.PROXIMITY_SAMPLE)
        var sampleOutcome = FeatureCostOutcome.SUCCESS
        val output = try {
            engine.observe(
                raw,
                elapsedRealtimeMs,
                teachingObservationLabel(activeSession?.kind == SessionKind.TEACH),
                continuousEvidence = !sparseReporting,
            )
        } catch (error: Throwable) {
            sampleOutcome = FeatureCostOutcome.FAILURE
            throw error
        } finally {
            FeatureCosts.registry.finishSynchronous(
                FeatureCostOperation.PROXIMITY_SAMPLE,
                sampleCost,
                outcome = sampleOutcome,
                workUnits = 1L,
            )
        }
        copy(output)
        val modelChanged = output.modelSequence != priorModelSequence
        val excursionCompleted = output.completedExcursions > priorCompletedExcursions
        if (output.completedEpisode) {
            addPendingEpisode(EntityCatalogStore.ProximityEpisodeRow(
                fingerprint = fingerprint,
                startedAt = System.currentTimeMillis() - output.episodeDurationMs,
                durationMs = output.episodeDurationMs,
                peakLevel = output.episodePeakLevel,
                completed = true,
                guided = activeSession?.kind == SessionKind.TEACH,
            ))
        }
        recordRollup(raw, excursionCompleted, output.gesture)

        var actuate = output.gesture && activeSession == null && wasWaveReady
        if (activeSession?.kind == SessionKind.TEACH && output.deliberateExample) {
            actuate = false
            activeSession.accepted++
            if (activeSession.accepted >= GUIDED_GESTURES_REQUIRED) {
                guidedReady = true
                lastSessionMessage = "Teaching complete. Deliberate wake gestures are ready."
                session = null
            } else {
                activeSession.message =
                    "Wave accepted (${activeSession.accepted}/$GUIDED_GESTURES_REQUIRED). Move clear, wait two seconds, then wave again."
            }
        } else if (activeSession?.kind == SessionKind.TEACH && excursionCompleted) {
            actuate = false
            activeSession.message =
                "Movement seen, but it was partial, too quick, or held too long. Move clear, wait two seconds, then try again."
        } else if (output.gesture && activeSession?.kind == SessionKind.TEST) {
            actuate = false
            lastSessionMessage = "Wave detected successfully."
            session = null
        } else if (activeSession?.kind == SessionKind.TEST && excursionCompleted) {
            actuate = false
            activeSession.message =
                "Movement seen, but it was not an armed deliberate wave. Move clear for two seconds, then try again."
        }

        if (output.completedEpisode || modelChanged) {
            requestPersist()
        }

        val reportLevel = levelForReport(view.near, view.normalizedLevel)
        val reportMask = reportGate.project(view.near, reportLevel, elapsedRealtimeMs, sparseReporting)
        return result(reportMask, view.near, reportLevel, actuate)
    }

    @Synchronized
    fun sourceUnavailable(elapsedRealtimeMs: Long = SystemClock.elapsedRealtime()): Decision {
        sourceAvailable = false
        copy(engine.sourceUnavailable())
        return result(reportGate.project(null, null, elapsedRealtimeMs, sparseReporting = false), null, null, false)
    }

    @Synchronized
    fun tick(
        elapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
        sparseReporting: Boolean = sparseLearningSource,
    ): Decision {
        if (closed) return result(ProximityReportGate.NONE, null, null, false)
        val output = engine.tick(elapsedRealtimeMs)
        copy(output)
        if (output.health == ProximityLearningEngine.HealthStatus.STALE) sourceAvailable = false
        val reportLevel = levelForReport(view.near, view.normalizedLevel)
        return result(
            reportGate.project(view.near, reportLevel, elapsedRealtimeMs, sparseReporting),
            view.near,
            reportLevel,
            false,
        )
    }

    @Synchronized
    fun startTeach(now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (closed || session != null || !teachSourceReadyLocked()) return false
        // Bind the explicit teaching action to the already-held clear value. Never label an unknown
        // future callback FAR: on an on-change source that callback may be the user's first near edge.
        if (
            lastObservedRaw.isFinite() &&
            (view.learning == ProximityLearningEngine.LearningStatus.LEARNING_FAR ||
                view.learning == ProximityLearningEngine.LearningStatus.VERIFYING_SEED)
        ) {
            copy(engine.observe(lastObservedRaw, maxOf(now, lastObservedAt), ProximityLearningEngine.GuidedLabel.FAR))
        }
        session = Session(
            kind = SessionKind.TEACH,
            expiresAt = now + TEACH_TIMEOUT_MS,
            message = "Leave the area clear briefly, then make a deliberate wave and move clear again.",
        )
        lastSessionMessage = ""
        return true
    }

    @Synchronized
    fun startTest(now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (closed || session != null || !waveReadyLocked()) return false
        session = Session(
            kind = SessionKind.TEST,
            expiresAt = now + TEST_TIMEOUT_MS,
            message = "Move clear for two seconds, then wave once and move clear again.",
        )
        lastSessionMessage = ""
        return true
    }

    @Synchronized
    fun cancelSession(): Boolean {
        val current = session ?: return false
        lastSessionMessage = if (current.kind == SessionKind.TEACH) "Teaching cancelled." else "Wave test cancelled."
        session = null
        return true
    }

    @Synchronized
    fun relearn(now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (closed) return false
        // Drain every older checkpoint before clearing on the caller's explicit action. If the
        // bounded queue cannot accept the barrier, fail closed without altering the learned model.
        val drained = CountDownLatch(1)
        runCatching { persistence.execute { drained.countDown() } }.getOrElse {
            lastSessionMessage = "Could not safely clear proximity history; the existing model is unchanged."
            return false
        }
        if (!runCatching { drained.await(PERSISTENCE_RESET_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
            lastSessionMessage = "Could not safely clear proximity history; the existing model is unchanged."
            return false
        }
        if (runCatching { store.clearProximityLearning(fingerprint) }.isFailure) {
            lastSessionMessage = "Could not clear proximity history; the existing model is unchanged."
            return false
        }
        lastDurableReadyModel = null
        val reusableFar = sourceAvailable && view.near == false && lastObservedRaw.isFinite()
        engine.reset()
        if (reusableFar) {
            engine.observe(lastObservedRaw, maxOf(now, lastObservedAt), ProximityLearningEngine.GuidedLabel.FAR)
        }
        copy(engine.current())
        guidedReady = false
        session = null
        lastSessionMessage = "Learned proximity history cleared. Learning has restarted."
        reportGate.reset()
        clearPendingEvidence()
        rollup = Rollup()
        return true
    }

    @Synchronized
    fun isReady(): Boolean = sourceAvailable && view.learning == ProximityLearningEngine.LearningStatus.READY &&
        view.health == ProximityLearningEngine.HealthStatus.HEALTHY && view.near != null

    @Synchronized
    fun isWaveReady(): Boolean = waveReadyLocked()

    /** Signal-shape capability, independent of current source health. A fingerprint-matching restored
     *  graded model remains evidence that this source is ranged; relearn/model-shift resets it to false. */
    @Synchronized
    fun isRangedSignal(): Boolean = view.mode == ProximityLearningEngine.Mode.GRADED

    @Synchronized
    fun signalMode(): ProximityLearningEngine.Mode = view.mode

    @Synchronized
    fun summary(): String = when {
        !sourceAvailable -> "Waiting for the proximity source's first trustworthy reading"
        !isReady() && view.learning == ProximityLearningEngine.LearningStatus.VERIFYING_SEED ->
            "Checking the previous learned range against live readings"
        view.learning == ProximityLearningEngine.LearningStatus.LEARNING_FAR ->
            "Learning the clear-room baseline"
        view.learning == ProximityLearningEngine.LearningStatus.LEARNING_EXCURSION ->
            "Baseline learned; waiting for complete near-and-clear movements"
        view.learning == ProximityLearningEngine.LearningStatus.RELEARNING ->
            "Adapting safely to a changed proximity signal"
        isWaveReady() -> "Ready · ${view.mode.name.lowercase(Locale.ROOT)} · normalized 0–100"
        isReady() -> "Presence ready; learning deliberate wake gestures"
        else -> "Waiting for trustworthy proximity readings"
    }

    @Synchronized
    fun json(raw: Float, ageSeconds: Long?): String {
        refreshSession(SystemClock.elapsedRealtime())
        val phase = phaseLocked()
        val message = messageLocked()
        val accepted = when {
            guidedReady -> GUIDED_GESTURES_REQUIRED
            session?.kind == SessionKind.TEACH -> session!!.accepted
            else -> view.deliberateExamples.coerceAtMost(PASSIVE_GESTURES_REQUIRED)
        }
        return JSONObject().apply {
            put("present", true)
            put("learning", phase)
            put("phase", phase)
            put("health", if (sourceAvailable) view.health.name.lowercase(Locale.ROOT) else "source_unavailable")
            put("message", message)
            put("signalMode", view.mode.name.lowercase(Locale.ROOT))
            put("mode", view.mode.name.lowercase(Locale.ROOT))
            put("rangedEligible", view.mode == ProximityLearningEngine.Mode.GRADED)
            put("polarity", view.polarity.name.lowercase(Locale.ROOT))
            put("acceptedGestures", accepted)
            put("requiredGestures", if (session?.kind == SessionKind.TEACH || guidedReady) {
                GUIDED_GESTURES_REQUIRED
            } else {
                PASSIVE_GESTURES_REQUIRED
            })
            put("readiness", view.readiness)
            put("ready", isReady())
            put("wakeReady", waveReadyLocked())
            put("canTeach", teachSourceReadyLocked() && (session == null || session?.kind == SessionKind.TEACH))
            put("canTest", (session == null && waveReadyLocked()) || session?.kind == SessionKind.TEST)
            put("normalizedLevel", view.normalizedLevel ?: JSONObject.NULL)
            put("near", view.near ?: JSONObject.NULL)
            put("raw", if (raw.isFinite()) raw else JSONObject.NULL)
            put("age_s", ageSeconds ?: JSONObject.NULL)
            put("session", JSONObject().apply {
                val current = session
                put("active", current != null)
                if (current != null) {
                    put("kind", current.kind.name.lowercase(Locale.ROOT))
                    put("accepted", current.accepted)
                    put("message", current.message)
                }
            })
            put("lastSessionMessage", lastSessionMessage)
        }.toString()
    }

    private fun waveReadyLocked(): Boolean = isReady() &&
        (guidedReady || view.deliberateExamples >= PASSIVE_GESTURES_REQUIRED)

    private fun teachSourceReadyLocked(): Boolean = sourceAvailable && lastObservedRaw.isFinite() &&
        canTeachDuring(view.learning) &&
        view.health !in arrayOf(
            ProximityLearningEngine.HealthStatus.NO_DATA,
            ProximityLearningEngine.HealthStatus.INVALID_SAMPLE,
            ProximityLearningEngine.HealthStatus.CLOCK_REGRESSION,
            ProximityLearningEngine.HealthStatus.STALE,
        )

    private fun phaseLocked(): String = when {
        !sourceAvailable || view.health == ProximityLearningEngine.HealthStatus.NO_DATA -> "waiting_for_reading"
        view.health in setOf(
            ProximityLearningEngine.HealthStatus.INVALID_SAMPLE,
            ProximityLearningEngine.HealthStatus.CLOCK_REGRESSION,
            ProximityLearningEngine.HealthStatus.STALE,
        ) -> "degraded"
        view.learning == ProximityLearningEngine.LearningStatus.LEARNING_FAR -> "learning_baseline"
        view.learning == ProximityLearningEngine.LearningStatus.LEARNING_EXCURSION -> "learning_gestures"
        view.learning == ProximityLearningEngine.LearningStatus.VERIFYING_SEED -> "validating"
        view.learning == ProximityLearningEngine.LearningStatus.RELEARNING -> "rebasing"
        waveReadyLocked() -> "ready"
        else -> "validating"
    }

    private fun messageLocked(): String = session?.message ?: when {
        !sourceAvailable -> "Waiting for a trustworthy reading from the panel's proximity source."
        view.learning == ProximityLearningEngine.LearningStatus.LEARNING_FAR ->
            "No setup is required. Keep the area clear briefly so the panel can learn its baseline."
        view.learning == ProximityLearningEngine.LearningStatus.LEARNING_EXCURSION ->
            "Use the panel normally, or choose Teach a wave to finish sooner."
        view.learning == ProximityLearningEngine.LearningStatus.VERIFYING_SEED ->
            "A previous range was found and is being checked against live clear-room readings."
        view.learning == ProximityLearningEngine.LearningStatus.RELEARNING ->
            "The signal changed. HA reporting and wave wake are paused while a new safe range is learned."
        waveReadyLocked() ->
            "Proximity is normalized for HA and wake requires a complete deliberate wave."
        else -> "Presence is normalized for HA. A few more complete gestures will enable wave wake."
    }

    private fun refreshSession(now: Long) {
        val current = session ?: return
        if (now < current.expiresAt) return
        lastSessionMessage = when (current.kind) {
            SessionKind.TEACH -> "Teaching timed out. Nothing unsafe was enabled; try again when convenient."
            SessionKind.TEST -> "No complete wave was detected during the test."
        }
        session = null
    }

    private fun recordRollup(raw: Float, excursion: Boolean, gesture: Boolean) {
        if (!raw.isFinite()) return
        val bucket = System.currentTimeMillis() / ROLLUP_MS
        if (rollup.bucket != bucket) {
            if (rollup.samples > 0) addPendingRollup(rollup.toRow(fingerprint))
            rollup = Rollup(bucket = bucket)
            requestPersist()
        }
        val value = raw.toDouble()
        rollup.samples++
        rollup.min = minOf(rollup.min, value)
        rollup.max = maxOf(rollup.max, value)
        rollup.sum += value
        rollup.squareSum += value * value
        if (excursion) rollup.excursions++
        if (gesture) rollup.gestures++
    }

    private fun Rollup.toRow(fingerprint: String) = EntityCatalogStore.ProximityRollupRow(
        fingerprint = fingerprint,
        bucket = bucket,
        sampleCount = samples,
        rawMin = min,
        rawMax = max,
        rawSum = sum,
        rawSquareSum = squareSum,
        excursionCount = excursions,
        gestureCount = gestures,
    )

    /** Capture immutable state on the caller thread; SQLite work stays away from sensor callbacks. */
    private fun requestPersist() {
        val row = modelRowLocked()
        val evidence = drainPendingEvidence()
        val accepted = runCatching {
            persistence.execute {
                if (!writeProximityBatch(row, evidence)) {
                    restorePendingEvidence(evidence)
                }
            }
        }.isSuccess
        if (!accepted) {
            restorePendingEvidence(evidence)
        }
    }

    private fun modelRowLocked(): EntityCatalogStore.ProximityModelRow {
        val snapshot = engine.snapshot()
        if (snapshot == null) {
            // Verification and behavioral-epoch changes are transient. Keep the last trustworthy
            // checkpoint crash-recoverable until a replacement has itself become trustworthy.
            lastDurableReadyModel?.let { return it }
        }
        val json = JSONObject().apply {
            put("schema", PERSISTENCE_SCHEMA)
            put("guidedReady", guidedReady)
            if (snapshot != null) {
                put("engineSchema", snapshot.schemaVersion)
                put("farRaw", snapshot.farRaw.toDouble())
                put("nearRaw", snapshot.nearRaw.toDouble())
                put("noise", snapshot.noise.toDouble())
                put("mode", snapshot.mode.name)
                put("polarity", snapshot.polarity.name)
                put("completedExcursions", snapshot.completedExcursions)
                put("deliberateExamples", snapshot.deliberateExamples)
            }
        }.toString()
        val row = EntityCatalogStore.ProximityModelRow(
            fingerprint = fingerprint,
            algorithmVersion = ALGORITHM_VERSION,
            behaviorSignature = "${view.mode.name}:${view.polarity.name}",
            snapshotJson = json,
            ready = snapshot != null,
            updatedAt = System.currentTimeMillis(),
        )
        if (snapshot != null) lastDurableReadyModel = row
        return row
    }

    private fun addPendingRollup(row: EntityCatalogStore.ProximityRollupRow) = synchronized(evidenceLock) {
        pendingRollups += row
        trimOldest(pendingRollups, MAX_PENDING_ROLLUPS)
    }

    private fun addPendingEpisode(row: EntityCatalogStore.ProximityEpisodeRow) = synchronized(evidenceLock) {
        pendingEpisodes += row
        trimOldest(pendingEpisodes, MAX_PENDING_EPISODES)
    }

    private fun drainPendingEvidence(): PendingEvidence = synchronized(evidenceLock) {
        PendingEvidence(pendingRollups.toList(), pendingEpisodes.toList()).also {
            pendingRollups.clear()
            pendingEpisodes.clear()
        }
    }

    private fun restorePendingEvidence(evidence: PendingEvidence) = synchronized(evidenceLock) {
        pendingRollups.addAll(0, evidence.rollups)
        pendingEpisodes.addAll(0, evidence.episodes)
        trimOldest(pendingRollups, MAX_PENDING_ROLLUPS)
        trimOldest(pendingEpisodes, MAX_PENDING_EPISODES)
    }

    private fun clearPendingEvidence() = synchronized(evidenceLock) {
        pendingRollups.clear()
        pendingEpisodes.clear()
    }

    private fun <T> trimOldest(rows: ArrayList<T>, maximum: Int) {
        val excess = rows.size - maximum
        if (excess > 0) rows.subList(0, excess).clear()
    }

    private fun restore(raw: String): Boolean = runCatching {
        val json = JSONObject(raw)
        check(json.getInt("schema") == PERSISTENCE_SCHEMA)
        val snapshot = ProximityLearningEngine.Snapshot(
            schemaVersion = json.getInt("engineSchema"),
            farRaw = json.getDouble("farRaw").toFloat(),
            nearRaw = json.getDouble("nearRaw").toFloat(),
            noise = json.getDouble("noise").toFloat(),
            mode = ProximityLearningEngine.Mode.valueOf(json.getString("mode")),
            polarity = ProximityLearningEngine.Polarity.valueOf(json.getString("polarity")),
            completedExcursions = json.getInt("completedExcursions"),
            deliberateExamples = json.optInt("deliberateExamples", 0),
        )
        if (!engine.restore(snapshot)) return@runCatching false
        guidedReady = json.optBoolean("guidedReady", false)
        true
    }.getOrDefault(false)

    private fun copy(output: ProximityLearningEngine.Output) {
        view.learning = output.learning
        view.health = output.health
        view.mode = output.mode
        view.polarity = output.polarity
        view.readiness = output.readiness
        view.normalizedLevel = output.normalizedLevel
        view.near = output.presence
        view.completedExcursions = output.completedExcursions
        view.deliberateExamples = output.deliberateExamples
        view.modelSequence = output.modelSequence
    }

    private fun result(reportMask: Int, near: Boolean?, level: Int?, gesture: Boolean): Decision {
        borrowedDecision.reportMask = reportMask
        borrowedDecision.near = near
        borrowedDecision.normalizedLevel = level
        borrowedDecision.deliberateGesture = gesture
        return borrowedDecision
    }

    override fun close() {
        closeAsync()
    }

    @Synchronized
    fun closeAsync(): CompletableFuture<Unit> {
        if (closed) return closeCompletion
        closed = true
        if (rollup.samples > 0) addPendingRollup(rollup.toRow(fingerprint))
        val finalRow = modelRowLocked()
        persistence.shutdown()
        val drained = runCatching {
            persistence.awaitTermination(PERSISTENCE_CLOSE_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!drained) {
            // Never cancel admitted evidence-owning tasks. A daemon closer waits without holding the
            // runtime monitor, then checkpoints any failed/requeued batches before closing SQLite.
            Thread({
                runCatching { persistence.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS) }
                finishCloseAndComplete(finalRow)
            }, "proximity-persistence-close").apply { isDaemon = true }.start()
            return closeCompletion
        }
        finishCloseAndComplete(finalRow)
        return closeCompletion
    }

    private fun finishCloseAndComplete(finalRow: EntityCatalogStore.ProximityModelRow) {
        if (finishClose(finalRow)) closeCompletion.complete(Unit)
        else closeCompletion.completeExceptionally(
            IllegalStateException("final proximity persistence did not complete"),
        )
    }

    private fun finishClose(finalRow: EntityCatalogStore.ProximityModelRow): Boolean {
        val evidence = drainPendingEvidence()
        return finishProximityPersistence(
            attempts = FINAL_CLOSE_WRITE_ATTEMPTS,
            write = { writeProximityBatch(finalRow, evidence) },
            close = store::close,
        )
    }

    private fun writeProximityBatch(
        row: EntityCatalogStore.ProximityModelRow,
        evidence: PendingEvidence,
    ): Boolean {
        val persistCost = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.PROXIMITY_PERSIST_BATCH)
        var outcome = FeatureCostOutcome.SUCCESS
        return try {
            store.writeProximityBatch(
                row,
                evidence.rollups,
                evidence.episodes,
                System.currentTimeMillis(),
            )
            true
        } catch (_: Throwable) {
            outcome = FeatureCostOutcome.FAILURE
            false
        } finally {
            FeatureCosts.registry.finishSynchronous(
                FeatureCostOperation.PROXIMITY_PERSIST_BATCH,
                persistCost,
                outcome = outcome,
                workUnits = 1L + evidence.rollups.size + evidence.episodes.size,
            )
        }
    }

    companion object {
        const val ALGORITHM_VERSION = 4
        private const val PERSISTENCE_SCHEMA = 1
        private const val GUIDED_GESTURES_REQUIRED = 3
        private const val PASSIVE_GESTURES_REQUIRED = 5
        private const val TEACH_TIMEOUT_MS = 90_000L
        private const val TEST_TIMEOUT_MS = 30_000L
        private const val FAR_REPORT_QUIET_LEVEL = 15
        private const val MAX_PENDING_ROLLUPS = 2_016
        private const val MAX_PENDING_EPISODES = 512
        private const val FINAL_CLOSE_WRITE_ATTEMPTS = 3
        private const val ROLLUP_MS = 5 * 60_000L
        private const val PERSISTENCE_CLOSE_MS = 1_000L
        private const val PERSISTENCE_RESET_MS = 3_000L

        internal fun learningPolicy(sparseSource: Boolean) = ProximityLearningEngine.Policy(
            baselineSamples = if (sparseSource) 1 else 8,
            guidedFarSamples = if (sparseSource) 1 else 3,
            baselineDurationMs = if (sparseSource) 0L else 30_000L,
            guidedBaselineDurationMs = if (sparseSource) 0L else 100L,
            seedFarSamples = if (sparseSource) 1 else 4,
            guidedSeedFarSamples = if (sparseSource) 1 else 2,
            seedVerifyDurationMs = if (sparseSource) 0L else 200L,
            farArmMs = 2_000L,
            minimumExcursionMs = 250L,
            continuousEvidenceDwellMs = if (sparseSource) 0L else ProximityReportGate.DENSE_PRESENCE_STABILITY_MS,
            maximumGestureMs = 4_000L,
            cooldownMs = 2_000L,
            staleAfterMs = 60_000L,
            changePointHoldMs = 30_000L,
            farArmLevel = FAR_REPORT_QUIET_LEVEL,
        )

        internal fun canTeachDuring(learning: ProximityLearningEngine.LearningStatus): Boolean =
            learning == ProximityLearningEngine.LearningStatus.LEARNING_EXCURSION ||
                learning == ProximityLearningEngine.LearningStatus.VERIFYING_SEED ||
                learning == ProximityLearningEngine.LearningStatus.READY

        internal fun teachingObservationLabel(teaching: Boolean): ProximityLearningEngine.GuidedLabel =
            if (teaching) ProximityLearningEngine.GuidedLabel.NEAR else ProximityLearningEngine.GuidedLabel.NONE

        /** Far-side normalized movement below the learner's arming threshold is noise, not useful
         * fleet telemetry. Keep the full local level for learning/UI while projecting a stable zero
         * to MQTT. Sparse edges stay immediate; dense presence and numeric level have distinct gates. */
        internal fun levelForReport(near: Boolean?, level: Int?): Int? =
            if (near == false && level != null && level <= FAR_REPORT_QUIET_LEVEL) 0 else level

        internal fun isRestorableModel(
            row: EntityCatalogStore.ProximityModelRow,
            expectedFingerprint: String,
        ): Boolean = row.ready && row.algorithmVersion == ALGORITHM_VERSION && row.fingerprint == expectedFingerprint

        internal fun <T> eligibleLegacySeed(restored: Boolean, seed: T?, eligible: Boolean): T? =
            seed?.takeIf { !restored && eligible }

        fun fingerprint(
            sourceIdentity: String,
            algorithmVersion: Int = ALGORITHM_VERSION,
        ): String {
            require(algorithmVersion > 0)
            return MessageDigest.getInstance("SHA-256")
                .digest("adaptive-proximity-v$algorithmVersion|$sourceIdentity".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        }
    }
}

/** Final persistence is successful only when both the model/evidence batch and SQLite close succeed. */
internal fun finishProximityPersistence(
    attempts: Int,
    write: () -> Boolean,
    close: () -> Unit,
): Boolean {
    require(attempts > 0)
    var written = false
    repeat(attempts) {
        if (!written) written = runCatching(write).getOrDefault(false)
    }
    val closed = runCatching(close).isSuccess
    return written && closed
}
