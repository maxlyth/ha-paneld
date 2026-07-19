package io.github.maxlyth.hapaneld.sensors

import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

enum class HaAmbientSampleOrigin { REST_INITIAL, WEBSOCKET }

data class HaAmbientLuxSample(
    val entityId: String,
    val lux: Double,
    val observedAtEpochMs: Long,
    val receivedAtEpochMs: Long,
    val origin: HaAmbientSampleOrigin,
)

data class HaAmbientLuxCandidate(
    val entityId: String,
    val friendlyName: String,
    val unit: String,
    val currentLux: Double?,
    val available: Boolean,
    val lastUpdatedEpochMs: Long?,
)

data class HaAmbientCandidateProjection(
    val items: List<HaAmbientLuxCandidate> = emptyList(),
    val refreshedAtEpochMs: Long = 0L,
    val error: String = "",
)

enum class HaAmbientSourcePhase {
    DISABLED,
    CONNECTING,
    AUTHENTICATING,
    SUBSCRIBING,
    SYNCHRONIZING,
    LIVE,
    SOURCE_MISSING,
    SOURCE_UNAVAILABLE,
    RECONNECTING,
    AUTH_FAILED,
    STOPPED,
}

data class HaAmbientSourceStatus(
    val entityId: String? = null,
    val phase: HaAmbientSourcePhase = HaAmbientSourcePhase.DISABLED,
    val detail: String = "",
    val lastSampleAtEpochMs: Long? = null,
    val reconnectAttempt: Int = 0,
)

/**
 * Streams one exact Home Assistant illuminance entity without subscribing to the state firehose.
 * Lifecycle methods are non-blocking and may be called from Android's main thread; auth, REST and
 * WebSocket work run on [workerDispatcher]. Callbacks run on the supplied [scope].
 */
class HaAmbientLuxSubscriber internal constructor(
    private val scope: CoroutineScope,
    private val auth: HaApiSessionProvider,
    private val transport: HaAmbientTransport,
    private val onSample: (HaAmbientLuxSample) -> Unit,
    private val onStatus: (HaAmbientSourceStatus) -> Unit,
    private val onCandidates: (HaAmbientCandidateProjection) -> Unit,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val livenessIntervalMs: Long = DEFAULT_LIVENESS_INTERVAL_MS,
    private val pongTimeoutMs: Long = DEFAULT_PONG_TIMEOUT_MS,
    private val reconnectBaseMs: Long = DEFAULT_RECONNECT_BASE_MS,
    private val reconnectMaxMs: Long = DEFAULT_RECONNECT_MAX_MS,
) : AutoCloseable {

    constructor(
        scope: CoroutineScope,
        config: Config,
        onSample: (HaAmbientLuxSample) -> Unit,
        onStatus: (HaAmbientSourceStatus) -> Unit = {},
        onCandidates: (HaAmbientCandidateProjection) -> Unit = {},
    ) : this(
        scope = scope,
        auth = DashboardHaApiSessionProvider(config),
        transport = KtorHaAmbientTransport(),
        onSample = onSample,
        onStatus = onStatus,
        onCandidates = onCandidates,
    )

    private val generation = AtomicLong()
    private val candidateGeneration = AtomicLong()
    private val lock = Any()
    @Volatile private var sourceEntityId: String? = null
    @Volatile private var stopped = false
    @Volatile private var sourceJob: Job? = null
    @Volatile private var candidateJob: Job? = null
    @Volatile private var status = HaAmbientSourceStatus()
    @Volatile private var candidates = HaAmbientCandidateProjection()

    fun latestStatus(): HaAmbientSourceStatus = status

    fun latestCandidates(): HaAmbientCandidateProjection = candidates
    fun candidateRefreshInFlight(): Boolean = candidateJob?.isActive == true

    /** Blank/null disables the HA source. A replacement cancels the old socket generation first. */
    fun setSource(entityId: String?) {
        val normalized = entityId?.trim()?.takeIf(String::isNotEmpty)?.also(::validateEntityId)
        synchronized(lock) {
            check(!stopped) { "subscriber is closed" }
            if (normalized == sourceEntityId && sourceJob?.isActive == true) return
            sourceJob?.cancel()
            sourceEntityId = normalized
            val run = generation.incrementAndGet()
            if (normalized == null) {
                publishStatus(run, HaAmbientSourceStatus(phase = HaAmbientSourcePhase.DISABLED))
                sourceJob = null
            } else {
                sourceJob = scope.launch {
                    runSource(run, normalized)
                }
            }
        }
    }

    /** Refreshes the bounded illuminance-candidate projection without disturbing a live source. */
    fun refreshCandidates(force: Boolean = false) {
        synchronized(lock) {
            check(!stopped) { "subscriber is closed" }
            if (candidateJob?.isActive == true) return
            val age = epochMillis() - candidates.refreshedAtEpochMs
            val ttl = if (candidates.error.isBlank()) CANDIDATE_TTL_MS else CANDIDATE_ERROR_TTL_MS
            if (!force && candidates.refreshedAtEpochMs > 0L && age in 0 until ttl) return
            val refresh = candidateGeneration.incrementAndGet()
            candidateJob = scope.launch {
                val projection = runCatching { discoverCandidates() }.fold(
                    onSuccess = { HaAmbientCandidateProjection(it, epochMillis()) },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        HaAmbientCandidateProjection(
                            items = candidates.items,
                            refreshedAtEpochMs = epochMillis(),
                            error = safeDetail(error, "Home Assistant candidate discovery failed"),
                        )
                    },
                )
                if (!stopped && candidateGeneration.get() == refresh) {
                    candidates = projection
                    safeCallback { onCandidates(projection) }
                }
            }
        }
    }

    fun invalidateCandidates() {
        synchronized(lock) {
            candidateGeneration.incrementAndGet()
            candidateJob?.cancel()
            candidateJob = null
            candidates = HaAmbientCandidateProjection()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            generation.incrementAndGet()
            candidateGeneration.incrementAndGet()
            sourceJob?.cancel()
            candidateJob?.cancel()
            sourceJob = null
            candidateJob = null
            sourceEntityId = null
            val final = status.copy(
                entityId = null,
                phase = HaAmbientSourcePhase.STOPPED,
                detail = "",
                lastSampleAtEpochMs = null,
                reconnectAttempt = 0,
            )
            status = final
            safeCallback { onStatus(final) }
        }
    }

    private suspend fun discoverCandidates(): List<HaAmbientLuxCandidate> {
        var session = resolveSession(force = false)
        val states = try {
            transport.states(session.baseUrl, checkNotNull(session.accessToken))
        } catch (rejected: HaAuthenticationException) {
            session = resolveSession(force = true)
            transport.states(session.baseUrl, checkNotNull(session.accessToken))
        }
        return HaAmbientLuxProtocol.candidates(states)
    }

    private suspend fun runSource(run: Long, entityId: String) {
        var attempt = 0
        var forceAuth = false
        while (scope.isActive && current(run, entityId)) {
            var connection: HaTriggerConnection? = null
            try {
                publishStatus(run, statusFor(entityId, HaAmbientSourcePhase.AUTHENTICATING, attempt = attempt))
                val session = resolveSession(forceAuth)
                forceAuth = false
                publishStatus(run, statusFor(entityId, HaAmbientSourcePhase.CONNECTING, attempt = attempt))
                publishStatus(run, statusFor(entityId, HaAmbientSourcePhase.SUBSCRIBING, attempt = attempt))
                connection = withContext(workerDispatcher) {
                    transport.subscribe(session.baseUrl, checkNotNull(session.accessToken), entityId)
                }
                publishStatus(run, statusFor(entityId, HaAmbientSourcePhase.SYNCHRONIZING, attempt = attempt))
                runConnected(run, entityId, session, connection)
                throw HaProtocolException("Home Assistant stream closed")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: HaAuthenticationException) {
                forceAuth = true
                attempt++
                publishStatus(
                    run,
                    statusFor(entityId, HaAmbientSourcePhase.AUTH_FAILED, safeDetail(rejected, "Home Assistant authentication failed"), attempt),
                )
            } catch (error: Throwable) {
                attempt++
                publishStatus(
                    run,
                    statusFor(entityId, HaAmbientSourcePhase.RECONNECTING, safeDetail(error, "Home Assistant stream unavailable"), attempt),
                )
            } finally {
                withContext(NonCancellable + workerDispatcher) { runCatching { connection?.close() } }
            }
            if (!current(run, entityId)) return
            delay(reconnectDelay(attempt))
        }
    }

    private suspend fun runConnected(
        run: Long,
        entityId: String,
        session: HaApiSession,
        connection: HaTriggerConnection,
    ) = coroutineScope {
        val gate = HaLuxSampleGate(entityId, epochMillis) { sample ->
            if (current(run, entityId)) emitSample(sample)
        }
        val buffered = ArrayList<HaSocketMessage>()
        val messages = Channel<HaSocketMessage>(STREAM_BUFFER_CAPACITY)
        val reader = launch(workerDispatcher) {
            while (isActive) messages.send(connection.receive())
        }
        val initial = async(workerDispatcher) {
            transport.state(session.baseUrl, checkNotNull(session.accessToken), entityId)
        }
        var initialState: JSONObject? = null
        while (true) {
            val hydrated = select {
                initial.onAwait {
                    initialState = it
                    true
                }
                messages.onReceive { message ->
                    if (message is HaSocketMessage.State || message == HaSocketMessage.SourceMissing) {
                        if (buffered.size == STREAM_BUFFER_CAPACITY) buffered.removeAt(0)
                        buffered += message
                    }
                    false
                }
            }
            if (hydrated) break
        }
        val hydratedState = initialState
        if (hydratedState == null) {
            publishStatus(run, statusFor(entityId, HaAmbientSourcePhase.SOURCE_MISSING, "Entity is not currently available"))
        } else {
            if (!gate.accept(hydratedState, HaAmbientSampleOrigin.REST_INITIAL) &&
                !HaAmbientLuxProtocol.hasUsableLux(hydratedState, entityId)
            ) {
                publishStatus(run, statusFor(entityId, HaAmbientSourcePhase.SOURCE_UNAVAILABLE, "Entity does not currently have a numeric lux value"))
            } else {
                publishStatus(run, statusFor(entityId, HaAmbientSourcePhase.LIVE))
            }
        }
        buffered.forEach { message -> applyStreamMessage(message, gate, run, entityId) }

        var pingId = FIRST_PING_ID
        try {
            while (isActive && current(run, entityId)) {
                val message = withTimeoutOrNull(livenessIntervalMs) { messages.receive() }
                if (message != null) {
                    applyStreamMessage(message, gate, run, entityId)
                    continue
                }

                val expectedPong = pingId++
                connection.ping(expectedPong)
                val alive = awaitPong(messages, gate, expectedPong, run, entityId)
                if (!alive) throw HaProtocolException("Home Assistant stream liveness check timed out")
            }
        } finally {
            reader.cancel()
            messages.close()
        }
    }

    private suspend fun awaitPong(
        messages: Channel<HaSocketMessage>,
        gate: HaLuxSampleGate,
        expectedPong: Int,
        run: Long,
        entityId: String,
    ): Boolean {
        return withTimeoutOrNull(pongTimeoutMs) {
            while (current(run, entityId)) {
                when (val message = messages.receive()) {
                    is HaSocketMessage.Pong -> if (message.id == expectedPong) return@withTimeoutOrNull true
                    else -> applyStreamMessage(message, gate, run, entityId)
                }
            }
            false
        } ?: false
    }

    private fun applyStreamMessage(
        message: HaSocketMessage,
        gate: HaLuxSampleGate,
        run: Long,
        entityId: String,
    ) {
        when (message) {
            is HaSocketMessage.State -> when {
                gate.accept(message.json, HaAmbientSampleOrigin.WEBSOCKET) ->
                    publishStatus(run, statusFor(entityId, HaAmbientSourcePhase.LIVE))
                !HaAmbientLuxProtocol.hasUsableLux(message.json, entityId) ->
                    publishStatus(run, statusFor(entityId, HaAmbientSourcePhase.SOURCE_UNAVAILABLE, "Entity does not currently have a numeric lux value"))
            }
            HaSocketMessage.SourceMissing ->
                publishStatus(run, statusFor(entityId, HaAmbientSourcePhase.SOURCE_MISSING, "Entity is not currently available"))
            else -> Unit
        }
    }

    private suspend fun resolveSession(force: Boolean): HaApiSession = withContext(workerDispatcher) {
        val session = auth.resolve(force)
        when {
            session.rejected -> throw HaAuthenticationException("Home Assistant rejected the configured refresh credentials")
            session.baseUrl.isBlank() -> throw HaProtocolException("Home Assistant URL is not configured")
            session.accessToken.isNullOrBlank() -> throw HaAuthenticationException("Home Assistant access credentials are unavailable")
            else -> session
        }
    }

    private fun emitSample(sample: HaAmbientLuxSample) {
        status = status.copy(lastSampleAtEpochMs = sample.receivedAtEpochMs)
        safeCallback { onSample(sample) }
    }

    private fun publishStatus(run: Long, next: HaAmbientSourceStatus) {
        if (run != generation.get() || stopped) return
        val withLastSample = next.copy(
            lastSampleAtEpochMs = if (status.entityId == next.entityId) status.lastSampleAtEpochMs
            else next.lastSampleAtEpochMs,
        )
        status = withLastSample
        safeCallback { onStatus(withLastSample) }
    }

    private fun statusFor(
        entityId: String,
        phase: HaAmbientSourcePhase,
        detail: String = "",
        attempt: Int = 0,
    ) = HaAmbientSourceStatus(
        entityId,
        phase,
        detail.take(MAX_DETAIL_CHARS),
        status.lastSampleAtEpochMs.takeIf { status.entityId == entityId },
        attempt,
    )

    private fun current(run: Long, entityId: String): Boolean =
        !stopped && generation.get() == run && sourceEntityId == entityId

    private fun reconnectDelay(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        val scaled = reconnectBaseMs * (1L shl shift)
        return scaled.coerceAtMost(reconnectMaxMs)
    }

    private fun safeDetail(error: Throwable, fallback: String): String =
        error.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.trim()?.take(MAX_DETAIL_CHARS)
            ?.takeIf(String::isNotBlank) ?: fallback

    private inline fun safeCallback(block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "HA ambient callback failed: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "HaAmbientLux"
        const val MAX_DETAIL_CHARS = 240
        const val STREAM_BUFFER_CAPACITY = 256
        const val FIRST_PING_ID = 10
        const val DEFAULT_LIVENESS_INTERVAL_MS = 45_000L
        const val DEFAULT_PONG_TIMEOUT_MS = 15_000L
        const val DEFAULT_RECONNECT_BASE_MS = 1_000L
        const val DEFAULT_RECONNECT_MAX_MS = 60_000L
        const val CANDIDATE_TTL_MS = 10L * 60_000L
        const val CANDIDATE_ERROR_TTL_MS = 60_000L
    }
}

internal object HaAmbientLuxProtocol {
    private val LUX_UNITS = setOf("lx", "lux")

    fun subscribeTrigger(entityId: String, id: Int = 1): JSONObject {
        validateEntityId(entityId)
        return JSONObject()
            .put("id", id)
            .put("type", "subscribe_trigger")
            .put(
                "trigger",
                JSONObject()
                    .put("platform", "state")
                    .put("entity_id", entityId),
            )
    }

    fun triggerState(message: JSONObject): JSONObject? =
        message.optJSONObject("event")
            ?.optJSONObject("variables")
            ?.optJSONObject("trigger")
            ?.optJSONObject("to_state")

    fun candidates(states: JSONArray): List<HaAmbientLuxCandidate> {
        val projected = ArrayList<HaAmbientLuxCandidate>()
        val limit = states.length().coerceAtMost(MAX_CANDIDATE_ROWS)
        for (index in 0 until limit) {
            val state = states.optJSONObject(index) ?: continue
            val entityId = state.optString("entity_id")
            if (!entityId.startsWith("sensor.") || !isValidEntityId(entityId)) continue
            val attributes = state.optJSONObject("attributes") ?: JSONObject()
            val unit = attributes.optString("unit_of_measurement").trim()
            val deviceClass = attributes.optString("device_class").lowercase(Locale.ROOT)
            if (deviceClass != "illuminance" && unit.lowercase(Locale.ROOT) !in LUX_UNITS) continue
            val lux = finiteNonNegative(state.optString("state"))
            projected += HaAmbientLuxCandidate(
                entityId = entityId,
                friendlyName = attributes.optString("friendly_name").trim().take(MAX_FRIENDLY_NAME_CHARS)
                    .ifBlank { entityId },
                unit = unit.ifBlank { "lx" }.take(MAX_UNIT_CHARS),
                currentLux = lux,
                available = lux != null,
                lastUpdatedEpochMs = timestamp(state),
            )
        }
        return projected.distinctBy(HaAmbientLuxCandidate::entityId)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, HaAmbientLuxCandidate::friendlyName)
                .thenBy(HaAmbientLuxCandidate::entityId))
    }

    fun sample(state: JSONObject, expectedEntityId: String): Pair<Double, Long>? {
        if (state.optString("entity_id") != expectedEntityId) return null
        val lux = finiteNonNegative(state.optString("state")) ?: return null
        return lux to (timestamp(state) ?: return null)
    }

    fun hasUsableLux(state: JSONObject, expectedEntityId: String): Boolean =
        state.optString("entity_id") == expectedEntityId && finiteNonNegative(state.optString("state")) != null

    private fun timestamp(state: JSONObject): Long? {
        val raw = state.optString("last_updated").ifBlank { state.optString("last_changed") }
        if (raw.isBlank()) return null
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun finiteNonNegative(raw: String): Double? =
        raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_LUX }

    private const val MAX_CANDIDATE_ROWS = 100_000
    private const val MAX_FRIENDLY_NAME_CHARS = 256
    private const val MAX_UNIT_CHARS = 32
    private const val MAX_LUX = 10_000_000.0
}

internal class HaLuxSampleGate(
    private val entityId: String,
    private val epochMillis: () -> Long,
    private val emit: (HaAmbientLuxSample) -> Unit,
) {
    private var newestObservedAt = Long.MIN_VALUE
    private var newestLux: Double? = null

    /** Timestamp ordering makes subscribe-first + REST hydration race-safe and suppresses duplicates. */
    fun accept(state: JSONObject, origin: HaAmbientSampleOrigin): Boolean {
        val (lux, observedAt) = HaAmbientLuxProtocol.sample(state, entityId) ?: return false
        if (observedAt < newestObservedAt || observedAt == newestObservedAt && lux == newestLux) return false
        newestObservedAt = max(newestObservedAt, observedAt)
        newestLux = lux
        emit(HaAmbientLuxSample(entityId, lux, observedAt, epochMillis(), origin))
        return true
    }
}

internal fun validateEntityId(entityId: String) {
    require(entityId.length <= 255 && EntityFilterProtocol.normalize(listOf(entityId)).single() == entityId) {
        "invalid Home Assistant entity_id"
    }
}

private fun isValidEntityId(entityId: String): Boolean =
    runCatching { validateEntityId(entityId) }.isSuccess
