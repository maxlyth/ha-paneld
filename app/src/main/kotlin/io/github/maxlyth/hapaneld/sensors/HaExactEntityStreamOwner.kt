package io.github.maxlyth.hapaneld.sensors

import android.util.Log
import io.github.maxlyth.hapaneld.HaAuthOwner
import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import io.ktor.client.HttpClient
import io.github.maxlyth.hapaneld.mqtt.MqttAddressFamilyPolicy
import io.github.maxlyth.hapaneld.util.HaWebSocketClients
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal enum class HaExactEntityConsumer { AMBIENT_LUX }

internal enum class HaExactEntityStreamPhase {
    DISABLED,
    AUTHENTICATING,
    CONNECTING,
    SUBSCRIBING,
    SYNCHRONIZING,
    LIVE,
    AUTH_FAILED,
    RECONNECTING,
    STOPPED,
}

internal data class HaExactEntityStreamStatus(
    val consumer: HaExactEntityConsumer = HaExactEntityConsumer.AMBIENT_LUX,
    val entityId: String? = null,
    val phase: HaExactEntityStreamPhase = HaExactEntityStreamPhase.DISABLED,
    val detail: String = "",
    val reconnectAttempt: Int = 0,
)

internal sealed interface HaExactEntityUpdate {
    val entityId: String
    val initial: Boolean

    data class State(
        override val entityId: String,
        val json: JSONObject,
        override val initial: Boolean,
    ) : HaExactEntityUpdate

    data class Missing(
        override val entityId: String,
        override val initial: Boolean,
    ) : HaExactEntityUpdate
}

internal interface HaExactEntityStreamObserver {
    fun onStatus(status: HaExactEntityStreamStatus)
    fun onUpdate(update: HaExactEntityUpdate)
}

/** Retained even when intermediate snapshots are coalesced, so an ON -> OFF edge is not erased. */
internal data class HaPresenceActivityMarker(
    val sequence: Long,
    val entityId: String,
    val receivedAtMonotonicMs: Long,
)

internal data class HaPresenceFeedSnapshot(
    val generation: Long = 0L,
    val revision: Long = 0L,
    val sourceIds: Set<String> = emptySet(),
    val states: Map<String, HaPresenceValue> = emptyMap(),
    val hydrated: Boolean = false,
    val phase: HaExactEntityStreamPhase = HaExactEntityStreamPhase.DISABLED,
    val detail: String = "",
    val reconnectAttempt: Int = 0,
    val lastOnActivity: HaPresenceActivityMarker? = null,
)

internal fun interface HaPresenceFeedObserver {
    fun onSnapshot(snapshot: HaPresenceFeedSnapshot)
}

internal sealed interface HaExactSocketMessage {
    data class State(val entityId: String, val json: JSONObject) : HaExactSocketMessage {
        constructor(json: JSONObject) : this(json.optString("entity_id"), json)
    }
    data class Missing(val entityId: String) : HaExactSocketMessage
    data class Pong(val id: Int) : HaExactSocketMessage
    data object RegistryChanged : HaExactSocketMessage
    data object Other : HaExactSocketMessage
}

internal interface HaExactEntityConnection {
    suspend fun receive(): HaExactSocketMessage
    suspend fun ping(id: Int)
    suspend fun close()
}

internal interface HaExactEntityStreamTransport {
    suspend fun subscribe(baseUrl: String, accessToken: String, entityIds: Set<String>): HaExactEntityConnection
    suspend fun subscribe(
        baseUrl: String,
        accessToken: String,
        entityIds: Set<String>,
        watchRegistry: Boolean,
    ): HaExactEntityConnection = subscribe(baseUrl, accessToken, entityIds)
    suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject?
}

private class HaExactEntitySetupException(message: String) : RuntimeException(message)

/**
 * The service-owned lifecycle authority for the bounded union of exact Home Assistant entities.
 *
 * Ambient light and automatic sleep share one authenticated exact-entity union, hydration pass,
 * reconnect policy and liveness timer. Empty demand cancels the generation and owns no socket,
 * coroutine or timer. Outbound requests are byte-batched rather than entity-count limited;
 * oversized inbound frames remain a safe transport failure.
 */
internal class HaExactEntityStreamOwner(
    private val scope: CoroutineScope,
    private val auth: HaApiSessionProvider,
    private val transport: HaExactEntityStreamTransport,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val livenessIntervalMs: Long = DEFAULT_LIVENESS_INTERVAL_MS,
    private val pongTimeoutMs: Long = DEFAULT_PONG_TIMEOUT_MS,
    private val reconnectBaseMs: Long = DEFAULT_RECONNECT_BASE_MS,
    private val reconnectMaxMs: Long = DEFAULT_RECONNECT_MAX_MS,
    private val subscribeTimeoutMs: Long = DEFAULT_SUBSCRIBE_TIMEOUT_MS,
    private val hydrationTimeoutMs: Long = DEFAULT_HYDRATION_TIMEOUT_MS,
    private val closeTimeoutMs: Long = DEFAULT_CLOSE_TIMEOUT_MS,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : AutoCloseable {
    private data class BufferedActivity(
        val count: Long,
        val observedAtEpochMs: Long?,
        val receivedAtMonotonicMs: Long,
    )

    private data class Request(
        val ambient: String?,
        val presence: Set<String>,
        val watchRegistry: Boolean = false,
    ) {
        val union: Set<String> = buildSet {
            ambient?.let(::add)
            addAll(presence)
        }
        val active: Boolean get() = union.isNotEmpty() || watchRegistry
    }

    private sealed interface PendingCallback {
        data class AmbientStatus(
            val run: Long,
            val target: HaExactEntityStreamObserver,
            val status: HaExactEntityStreamStatus,
        ) : PendingCallback

        data class AmbientUpdate(
            val run: Long,
            val target: HaExactEntityStreamObserver,
            val update: HaExactEntityUpdate,
        ) : PendingCallback

        data class Presence(
            val run: Long,
            val target: HaPresenceFeedObserver,
            val snapshot: HaPresenceFeedSnapshot,
        ) : PendingCallback

        data class FinalAmbient(
            val target: HaExactEntityStreamObserver,
            val status: HaExactEntityStreamStatus,
        ) : PendingCallback

        data class FinalPresence(
            val target: HaPresenceFeedObserver,
            val snapshot: HaPresenceFeedSnapshot,
        ) : PendingCallback

        data class RegistryChanged(
            val run: Long,
            val target: () -> Unit,
        ) : PendingCallback
    }

    private val generation = AtomicLong()
    private val lock = Any()
    private val callbackQueue = ArrayDeque<PendingCallback>()
    @Volatile private var request = Request(null, emptySet())
    @Volatile private var sourceJob: Job? = null
    @Volatile private var stopped = false
    @Volatile private var ambientObserver: HaExactEntityStreamObserver? = null
    @Volatile private var presenceObserver: HaPresenceFeedObserver? = null
    @Volatile private var registryChangeObserver: (() -> Unit)? = null
    private var drainingCallbacks = false
    private var presenceRevision = 0L
    private var presencePhase = HaExactEntityStreamPhase.DISABLED
    private var presenceDetail = ""
    private var presenceReconnectAttempt = 0
    private var presenceHydrated = false
    private val presenceStates = linkedMapOf<String, HaPresenceValue>()
    private val presenceObservedAt = linkedMapOf<String, Long>()
    private var activitySequence = 0L
    private var lastActivity: HaPresenceActivityMarker? = null

    fun bindAmbient(next: HaExactEntityStreamObserver) {
        synchronized(lock) {
            check(!stopped) { "exact entity stream owner is closed" }
            check(ambientObserver == null || ambientObserver === next) { "ambient exact-entity observer is already bound" }
            ambientObserver = next
        }
    }

    fun unbindAmbient(expected: HaExactEntityStreamObserver) {
        synchronized(lock) {
            if (ambientObserver === expected) ambientObserver = null
        }
    }

    fun bindPresence(next: HaPresenceFeedObserver) {
        var snapshot: HaPresenceFeedSnapshot? = null
        synchronized(lock) {
            check(!stopped) { "exact entity stream owner is closed" }
            check(presenceObserver == null || presenceObserver === next) { "presence exact-entity observer is already bound" }
            presenceObserver = next
            snapshot = presenceSnapshotLocked(generation.get())
        }
        snapshot?.let { safeCallback { next.onSnapshot(it) } }
    }

    fun unbindPresence(expected: HaPresenceFeedObserver) {
        synchronized(lock) {
            if (presenceObserver === expected) presenceObserver = null
        }
    }

    fun bindRegistryChanges(next: () -> Unit) {
        synchronized(lock) {
            check(!stopped) { "exact entity stream owner is closed" }
            check(registryChangeObserver == null || registryChangeObserver === next) {
                "registry-change observer is already bound"
            }
            registryChangeObserver = next
        }
    }

    fun unbindRegistryChanges() {
        synchronized(lock) { registryChangeObserver = null }
    }

    fun replaceAmbientSource(nextEntityId: String?) {
        val normalized = nextEntityId?.trim()?.takeIf(String::isNotEmpty)?.also(::validateEntityId)
        replaceRequest { it.copy(ambient = normalized) }
    }

    fun replacePresenceSources(nextEntityIds: Set<String>) {
        val normalized = nextEntityIds.mapTo(sortedSetOf()) { it.trim().lowercase(Locale.ROOT).also(::validateEntityId) }
        var drain = false
        synchronized(lock) {
            check(!stopped) { "exact entity stream owner is closed" }
            if (normalized == request.presence && sourceJob?.isActive == true) {
                drain = enqueuePresenceLocked(generation.get())
            }
        }
        if (drain) {
            drainCallbacks()
            return
        }
        if (normalized == request.presence && sourceJob?.isActive == true) return
        replaceRequest { it.copy(presence = normalized) }
    }

    /** Keeps the shared HA socket alive for registry events even when no entity source is selected. */
    fun replacePresenceRegistryWatch(enabled: Boolean) {
        replaceRequest { it.copy(watchRegistry = enabled) }
    }

    fun replacePresenceDemand(nextEntityIds: Set<String>, watchRegistry: Boolean) {
        val normalized = nextEntityIds.mapTo(sortedSetOf()) {
            it.trim().lowercase(Locale.ROOT).also(::validateEntityId)
        }
        val same = synchronized(lock) {
            !stopped && request.presence == normalized && request.watchRegistry == watchRegistry
        }
        if (same) {
            replacePresenceSources(normalized)
            return
        }
        replaceRequest { it.copy(presence = normalized, watchRegistry = watchRegistry) }
    }

    private fun replaceRequest(transform: (Request) -> Request) {
        var run = 0L
        var next = Request(null, emptySet())
        var changed = false
        synchronized(lock) {
            check(!stopped) { "exact entity stream owner is closed" }
            next = transform(request)
            if (next == request && sourceJob?.isActive == true) return
            changed = true
            sourceJob?.cancel()
            sourceJob = null
            request = next
            run = generation.incrementAndGet()
            resetPresenceLocked(next.presence)
            if (next.active) sourceJob = scope.launch { runSource(run, next) }
        }
        if (!changed) return
        if (next.ambient == null) publishAmbientStatus(
            run,
            HaExactEntityStreamStatus(phase = HaExactEntityStreamPhase.DISABLED),
        )
        if (next.presence.isEmpty()) publishPresenceStatus(run, HaExactEntityStreamPhase.DISABLED)
    }

    override fun close() {
        var drain = false
        synchronized(lock) {
            if (stopped) return
            stopped = true
            generation.incrementAndGet()
            sourceJob?.cancel()
            sourceJob = null
            request = Request(null, emptySet())
            callbackQueue.clear()
            ambientObserver?.let {
                callbackQueue.addLast(PendingCallback.FinalAmbient(
                    it,
                    HaExactEntityStreamStatus(phase = HaExactEntityStreamPhase.STOPPED),
                ))
            }
            presenceObserver?.let {
                callbackQueue.addLast(PendingCallback.FinalPresence(
                    it,
                    HaPresenceFeedSnapshot(phase = HaExactEntityStreamPhase.STOPPED),
                ))
            }
            ambientObserver = null
            presenceObserver = null
            registryChangeObserver = null
            if (!drainingCallbacks && callbackQueue.isNotEmpty()) {
                drainingCallbacks = true
                drain = true
            }
        }
        if (drain) drainCallbacks()
    }

    private suspend fun runSource(run: Long, expected: Request) {
        var attempt = 0
        var forceAuth = false
        var authRefreshAttempted = false
        var protocolFailures = 0
        var acceptedOwner: HaAuthOwner? = null
        var registryConnectedOnce = false
        while (scope.isActive && current(run, expected)) {
            var connection: HaExactEntityConnection? = null
            try {
                publishTransportStatus(run, expected, HaExactEntityStreamPhase.AUTHENTICATING, attempt = attempt)
                val session = resolveSession(forceAuth)
                val resolvedOwner = checkNotNull(session.owner)
                if (acceptedOwner != null && acceptedOwner != resolvedOwner) {
                    throw HaExactEntitySetupException("Home Assistant credentials changed during stream setup")
                }
                acceptedOwner = resolvedOwner
                forceAuth = false
                publishTransportStatus(run, expected, HaExactEntityStreamPhase.CONNECTING, attempt = attempt)
                publishTransportStatus(run, expected, HaExactEntityStreamPhase.SUBSCRIBING, attempt = attempt)
                connection = withTimeout(subscribeTimeoutMs) {
                    withContext(workerDispatcher) {
                        transport.subscribe(
                            session.baseUrl,
                            checkNotNull(session.accessToken),
                            expected.union,
                            expected.watchRegistry,
                        )
                    }
                }
                if (expected.watchRegistry && registryConnectedOnce) publishRegistryChanged(run)
                registryConnectedOnce = expected.watchRegistry
                publishTransportStatus(run, expected, HaExactEntityStreamPhase.SYNCHRONIZING, attempt = attempt)
                runConnected(run, expected, session, connection) {
                    attempt = 0
                    authRefreshAttempted = false
                    protocolFailures = 0
                }
                throw HaProtocolException("Home Assistant stream closed")
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                attempt = nextAttempt(attempt)
                publishTransportStatus(
                    run, expected, HaExactEntityStreamPhase.RECONNECTING,
                    safeDetail(timeout, "Home Assistant stream timed out"), attempt,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: HaAuthenticationException) {
                if (authRefreshAttempted) {
                    publishTransportStatus(
                        run, expected, HaExactEntityStreamPhase.AUTH_FAILED,
                        "Home Assistant rejected the panel sign-in after credential refresh", attempt,
                    )
                    return
                }
                authRefreshAttempted = true
                forceAuth = true
                attempt = nextAttempt(attempt)
                publishTransportStatus(
                    run, expected, HaExactEntityStreamPhase.AUTH_FAILED,
                    safeDetail(rejected, "Home Assistant authentication failed"), attempt,
                )
            } catch (error: HaExactEntitySetupException) {
                publishTransportStatus(
                    run, expected, HaExactEntityStreamPhase.AUTH_FAILED,
                    safeDetail(error, "Home Assistant stream setup failed"), attempt,
                )
                return
            } catch (error: HaProtocolException) {
                protocolFailures = nextAttempt(protocolFailures)
                attempt = nextAttempt(attempt)
                publishTransportStatus(
                    run, expected, HaExactEntityStreamPhase.RECONNECTING,
                    safeDetail(error, "Home Assistant stream unavailable"), attempt,
                )
                if (protocolFailures >= MAX_PROTOCOL_ATTEMPTS) return
            } catch (error: Exception) {
                attempt = nextAttempt(attempt)
                publishTransportStatus(
                    run, expected, HaExactEntityStreamPhase.RECONNECTING,
                    safeDetail(error, "Home Assistant stream unavailable"), attempt,
                )
                if (!isTransient(error)) return
            } finally {
                withContext(NonCancellable + workerDispatcher) {
                    withTimeoutOrNull(closeTimeoutMs) {
                        try {
                            connection?.close()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.w(TAG, "HA exact-entity connection close failed: ${error.javaClass.simpleName}")
                        }
                    }
                }
            }
            if (!current(run, expected)) return
            delay(reconnectDelay(attempt))
        }
    }

    private suspend fun runConnected(
        run: Long,
        expected: Request,
        session: HaApiSession,
        connection: HaExactEntityConnection,
        onLive: () -> Unit,
    ) = coroutineScope {
        val bufferedAmbient = ArrayDeque<HaExactSocketMessage>()
        val bufferedPresence = linkedMapOf<String, HaExactSocketMessage>()
        val bufferedPresenceStates = expected.presence.associateWithTo(linkedMapOf()) {
            HaPresenceValue.UNAVAILABLE
        }
        val bufferedActivities = linkedMapOf<String, BufferedActivity>()
        val messages = Channel<HaExactSocketMessage>(STREAM_BUFFER_CAPACITY)
        val reader = launch(workerDispatcher) {
            while (isActive) messages.send(connection.receive())
        }
        val initial = async {
            withTimeout(hydrationTimeoutMs) {
                withContext(workerDispatcher) {
                    expected.union.associateWith { id ->
                        transport.state(session.baseUrl, checkNotNull(session.accessToken), id)
                    }
                }
            }
        }
        try {
            var hydration: Map<String, JSONObject?>? = null
            while (hydration == null) {
                select<Unit> {
                    initial.onAwait { hydration = it }
                    messages.onReceive { message ->
                        if (message == HaExactSocketMessage.RegistryChanged) {
                            publishRegistryChanged(run)
                            return@onReceive
                        }
                        if (message is HaExactSocketMessage.State || message is HaExactSocketMessage.Missing) {
                            val entityId = when (message) {
                                is HaExactSocketMessage.State -> message.entityId
                                is HaExactSocketMessage.Missing -> message.entityId
                            }
                            if (entityId in expected.presence) {
                                val previousMessage = bufferedPresence[entityId]
                                val previousObserved = (previousMessage as? HaExactSocketMessage.State)
                                    ?.json?.let(::observedAtEpochMs)
                                val nextObserved = (message as? HaExactSocketMessage.State)
                                    ?.json?.let(::observedAtEpochMs)
                                val admitMessage = previousMessage == null || message is HaExactSocketMessage.Missing ||
                                    previousMessage is HaExactSocketMessage.Missing || nextObserved == null ||
                                    previousObserved == null || nextObserved >= previousObserved
                                if (admitMessage) {
                                    bufferedPresence[entityId] = message
                                    val value = when (message) {
                                        is HaExactSocketMessage.State ->
                                            HaPresenceProtocol.value(message.json.optString("state"))
                                        is HaExactSocketMessage.Missing -> HaPresenceValue.UNAVAILABLE
                                    }
                                    if (value == HaPresenceValue.ON &&
                                        bufferedPresenceStates[entityId] != HaPresenceValue.ON
                                    ) {
                                        val previous = bufferedActivities[entityId]
                                        val count = if (previous == null || previous.count == Long.MAX_VALUE) {
                                            previous?.count ?: 1L
                                        } else previous.count + 1L
                                        bufferedActivities[entityId] = BufferedActivity(
                                            count,
                                            nextObserved,
                                            monotonicMillis(),
                                        )
                                    }
                                    bufferedPresenceStates[entityId] = value
                                }
                            }
                            if (entityId == expected.ambient) {
                                if (bufferedAmbient.size == STREAM_BUFFER_CAPACITY) bufferedAmbient.removeFirst()
                                bufferedAmbient.addLast(message)
                            }
                        }
                    }
                }
            }
            checkNotNull(hydration).forEach { (entityId, state) ->
                deliverEntity(
                    run,
                    expected,
                    state?.let { HaExactEntityUpdate.State(entityId, it, initial = true) }
                        ?: HaExactEntityUpdate.Missing(entityId, initial = true),
                    publishPresence = false,
                )
            }
            markPresenceHydrated(run, expected)
            recordBufferedActivity(run, expected, bufferedActivities)
            bufferedAmbient.forEach { message ->
                applyMessage(
                    run, expected, message, initial = false,
                    publishPresence = false, recordActivity = false,
                )
            }
            bufferedPresence.values.forEach { message ->
                applyMessage(
                    run, expected, message, initial = false,
                    publishPresence = false, recordActivity = false,
                )
            }
            if (bufferedPresence.isNotEmpty()) publishBufferedPresence(run, expected)
            onLive()
            publishTransportStatus(run, expected, HaExactEntityStreamPhase.LIVE)

            var pingId = FIRST_PING_ID
            while (isActive && current(run, expected)) {
                val message = withTimeoutOrNull(livenessIntervalMs) { messages.receive() }
                if (message != null) {
                    applyMessage(run, expected, message, initial = false)
                    continue
                }
                val expectedPong = pingId++
                connection.ping(expectedPong)
                val alive = awaitPong(messages, expectedPong, run, expected)
                if (!alive) throw HaProtocolException("Home Assistant stream liveness check timed out")
            }
        } finally {
            reader.cancel()
            initial.cancel()
            messages.close()
        }
    }

    private suspend fun awaitPong(
        messages: Channel<HaExactSocketMessage>,
        expectedPong: Int,
        run: Long,
        expected: Request,
    ): Boolean = withTimeoutOrNull(pongTimeoutMs) {
        while (current(run, expected)) {
            when (val message = messages.receive()) {
                is HaExactSocketMessage.Pong -> if (message.id == expectedPong) return@withTimeoutOrNull true
                else -> applyMessage(run, expected, message, initial = false)
            }
        }
        false
    } ?: false

    private fun applyMessage(
        run: Long,
        expected: Request,
        message: HaExactSocketMessage,
        initial: Boolean,
        publishPresence: Boolean = true,
        recordActivity: Boolean = true,
    ) {
        when (message) {
            is HaExactSocketMessage.State -> deliverEntity(
                run, expected, HaExactEntityUpdate.State(message.entityId, message.json, initial),
                publishPresence = publishPresence,
                recordActivity = recordActivity,
            )
            is HaExactSocketMessage.Missing -> deliverEntity(
                run, expected, HaExactEntityUpdate.Missing(message.entityId, initial),
                publishPresence = publishPresence,
                recordActivity = recordActivity,
            )
            HaExactSocketMessage.RegistryChanged -> publishRegistryChanged(run)
            else -> Unit
        }
    }

    private fun publishRegistryChanged(run: Long) {
        var drain = false
        synchronized(lock) {
            if (stopped || generation.get() != run || !request.watchRegistry) return
            val target = registryChangeObserver ?: return
            callbackQueue.removeAll { it is PendingCallback.RegistryChanged && it.run == run && it.target === target }
            callbackQueue.addLast(PendingCallback.RegistryChanged(run, target))
            if (!drainingCallbacks) {
                drainingCallbacks = true
                drain = true
            }
        }
        if (drain) drainCallbacks()
    }

    private suspend fun resolveSession(force: Boolean): HaApiSession = withContext(workerDispatcher) {
        val session = auth.resolve(force)
        when {
            session.rejected -> throw HaAuthenticationException("Home Assistant rejected the configured refresh credentials")
            session.baseUrl.isBlank() -> throw HaExactEntitySetupException("Home Assistant URL is not configured")
            session.accessToken.isNullOrBlank() -> throw HaAuthenticationException("Home Assistant access credentials are unavailable")
            session.owner == null -> throw HaExactEntitySetupException("Home Assistant credentials changed during stream setup")
            else -> session
        }
    }

    private fun publishTransportStatus(
        run: Long,
        expected: Request,
        phase: HaExactEntityStreamPhase,
        detail: String = "",
        attempt: Int = 0,
    ) {
        expected.ambient?.let { entityId ->
            publishAmbientStatus(run, HaExactEntityStreamStatus(
                entityId = entityId,
                phase = phase,
                detail = detail,
                reconnectAttempt = attempt,
            ))
        }
        if (expected.presence.isNotEmpty()) publishPresenceStatus(run, phase, detail, attempt)
    }

    private fun publishAmbientStatus(run: Long, next: HaExactEntityStreamStatus) {
        var drain = false
        synchronized(lock) {
            if (stopped || generation.get() != run) return
            val target = ambientObserver ?: return
            callbackQueue.addLast(PendingCallback.AmbientStatus(run, target, next.copy(detail = next.detail.take(MAX_DETAIL_CHARS))))
            if (!drainingCallbacks) {
                drainingCallbacks = true
                drain = true
            }
        }
        if (drain) drainCallbacks()
    }

    private fun publishPresenceStatus(
        run: Long,
        phase: HaExactEntityStreamPhase,
        detail: String = "",
        attempt: Int = 0,
    ) {
        var drain = false
        synchronized(lock) {
            if (stopped || generation.get() != run) return
            presencePhase = phase
            if (phase == HaExactEntityStreamPhase.CONNECTING ||
                phase == HaExactEntityStreamPhase.SUBSCRIBING ||
                phase == HaExactEntityStreamPhase.SYNCHRONIZING
            ) presenceHydrated = false
            presenceDetail = detail.take(MAX_DETAIL_CHARS)
            presenceReconnectAttempt = attempt
            presenceRevision++
            drain = enqueuePresenceLocked(run)
        }
        if (drain) drainCallbacks()
    }

    private fun markPresenceHydrated(run: Long, expected: Request) {
        var drain = false
        synchronized(lock) {
            if (stopped || generation.get() != run || request != expected || expected.presence.isEmpty()) return
            presenceHydrated = true
            presenceRevision++
            drain = enqueuePresenceLocked(run)
        }
        if (drain) drainCallbacks()
    }

    private fun deliverEntity(
        run: Long,
        expected: Request,
        update: HaExactEntityUpdate,
        publishPresence: Boolean = true,
        recordActivity: Boolean = true,
    ) {
        var drain = false
        synchronized(lock) {
            if (stopped || generation.get() != run || request != expected) return
            if (update.entityId == expected.ambient) {
                ambientObserver?.let { callbackQueue.addLast(PendingCallback.AmbientUpdate(run, it, update)) }
            }
            if (update.entityId in expected.presence) {
                val nextValue = when (update) {
                    is HaExactEntityUpdate.State -> HaPresenceProtocol.value(update.json.optString("state"))
                    is HaExactEntityUpdate.Missing -> HaPresenceValue.UNAVAILABLE
                }
                val observed = (update as? HaExactEntityUpdate.State)?.json?.let(::observedAtEpochMs)
                val previousObserved = presenceObservedAt[update.entityId]
                val stale = observed != null && previousObserved != null && observed < previousObserved
                if (!stale) {
                    val previous = presenceStates[update.entityId]
                    presenceStates[update.entityId] = nextValue
                    if (observed != null) presenceObservedAt[update.entityId] = observed
                    if (recordActivity && nextValue == HaPresenceValue.ON && previous != HaPresenceValue.ON) {
                        lastActivity = HaPresenceActivityMarker(
                            ++activitySequence,
                            update.entityId,
                            monotonicMillis(),
                        )
                    }
                    if (publishPresence) {
                        presenceRevision++
                        drain = enqueuePresenceLocked(run) || drain
                    }
                }
            }
            if (!drainingCallbacks && callbackQueue.isNotEmpty()) {
                drainingCallbacks = true
                drain = true
            }
        }
        if (drain) drainCallbacks()
    }

    private fun recordBufferedActivity(
        run: Long,
        expected: Request,
        activities: Map<String, BufferedActivity>,
    ) {
        if (activities.isEmpty()) return
        synchronized(lock) {
            if (stopped || generation.get() != run || request != expected) return
            val valid = activities.filter { (entityId, activity) ->
                activity.observedAtEpochMs == null ||
                    (presenceObservedAt[entityId] ?: Long.MIN_VALUE) <= activity.observedAtEpochMs
            }
            val latest = valid.maxByOrNull { it.value.receivedAtMonotonicMs } ?: return
            val count = valid.values.fold(0L) { total, activity ->
                if (total > Long.MAX_VALUE - activity.count) Long.MAX_VALUE else total + activity.count
            }
            activitySequence = if (activitySequence > Long.MAX_VALUE - count) Long.MAX_VALUE
                else activitySequence + count
            lastActivity = HaPresenceActivityMarker(
                activitySequence,
                latest.key,
                latest.value.receivedAtMonotonicMs,
            )
        }
    }

    private fun publishBufferedPresence(run: Long, expected: Request) {
        var drain = false
        synchronized(lock) {
            if (stopped || generation.get() != run || request != expected) return
            presenceRevision++
            drain = enqueuePresenceLocked(run)
        }
        if (drain) drainCallbacks()
    }

    private fun resetPresenceLocked(sourceIds: Set<String>) {
        presenceRevision = 0L
        presencePhase = if (sourceIds.isEmpty()) HaExactEntityStreamPhase.DISABLED
            else HaExactEntityStreamPhase.AUTHENTICATING
        presenceDetail = ""
        presenceReconnectAttempt = 0
        presenceHydrated = false
        presenceStates.clear()
        sourceIds.forEach { presenceStates[it] = HaPresenceValue.UNAVAILABLE }
        presenceObservedAt.clear()
        lastActivity = null
    }

    private fun enqueuePresenceLocked(run: Long): Boolean {
        val target = presenceObserver ?: return false
        val snapshot = presenceSnapshotLocked(run)
        callbackQueue.removeAll { it is PendingCallback.Presence && it.run == run && it.target === target }
        callbackQueue.addLast(PendingCallback.Presence(run, target, snapshot))
        if (!drainingCallbacks) {
            drainingCallbacks = true
            return true
        }
        return false
    }

    private fun presenceSnapshotLocked(run: Long) = HaPresenceFeedSnapshot(
        generation = run,
        revision = presenceRevision,
        sourceIds = request.presence.toSet(),
        states = presenceStates.toMap(),
        hydrated = presenceHydrated,
        phase = presencePhase,
        detail = presenceDetail,
        reconnectAttempt = presenceReconnectAttempt,
        lastOnActivity = lastActivity,
    )

    private fun drainCallbacks() {
        try {
            while (true) {
                val next = synchronized(lock) {
                    var accepted: PendingCallback? = null
                    while (callbackQueue.isNotEmpty() && accepted == null) {
                        val candidate = callbackQueue.removeFirst()
                        accepted = when (candidate) {
                            is PendingCallback.FinalAmbient, is PendingCallback.FinalPresence -> candidate
                            is PendingCallback.AmbientStatus -> candidate.takeIf {
                                !stopped && generation.get() == it.run && ambientObserver === it.target
                            }
                            is PendingCallback.AmbientUpdate -> candidate.takeIf {
                                !stopped && generation.get() == it.run && ambientObserver === it.target
                            }
                            is PendingCallback.Presence -> candidate.takeIf {
                                !stopped && generation.get() == it.run && presenceObserver === it.target
                            }
                            is PendingCallback.RegistryChanged -> candidate.takeIf {
                                !stopped && generation.get() == it.run && registryChangeObserver === it.target
                            }
                        }
                    }
                    if (accepted == null) drainingCallbacks = false
                    accepted
                } ?: return
                when (next) {
                    is PendingCallback.FinalAmbient -> safeCallback { next.target.onStatus(next.status) }
                    is PendingCallback.FinalPresence -> safeCallback { next.target.onSnapshot(next.snapshot) }
                    is PendingCallback.AmbientStatus -> safeCallback { next.target.onStatus(next.status) }
                    is PendingCallback.AmbientUpdate -> safeCallback { next.target.onUpdate(next.update) }
                    is PendingCallback.Presence -> safeCallback { next.target.onSnapshot(next.snapshot) }
                    is PendingCallback.RegistryChanged -> safeCallback(next.target)
                }
            }
        } catch (error: Error) {
            synchronized(lock) {
                callbackQueue.clear()
                drainingCallbacks = false
            }
            throw error
        }
    }

    private fun current(run: Long, expected: Request): Boolean =
        !stopped && generation.get() == run && request == expected

    private fun reconnectDelay(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        return (reconnectBaseMs * (1L shl shift)).coerceAtMost(reconnectMaxMs)
    }

    private fun nextAttempt(current: Int): Int = minOf(current, MAX_RECONNECT_ATTEMPT - 1) + 1

    private fun isTransient(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .any {
            it is IOException || it is ClosedChannelException ||
                it is ClosedReceiveChannelException || it is ClosedSendChannelException
        }

    private fun safeDetail(error: Throwable, fallback: String): String =
        error.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.trim()?.take(MAX_DETAIL_CHARS)
            ?.takeIf(String::isNotBlank) ?: fallback

    private inline fun safeCallback(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            Log.w(TAG, "HA exact-entity callback failed: ${error.javaClass.simpleName}")
        }
    }

    private fun observedAtEpochMs(state: JSONObject): Long? =
        sequenceOf(state.optString("last_updated"), state.optString("last_changed"))
            .firstOrNull(String::isNotBlank)
            ?.let(::parseHaTimestampEpochMs)

    private companion object {
        const val TAG = "HaExactEntity"
        const val MAX_DETAIL_CHARS = 240
        const val STREAM_BUFFER_CAPACITY = 256
        const val MAX_PROTOCOL_ATTEMPTS = 3
        const val MAX_RECONNECT_ATTEMPT = 1_000_000
        const val FIRST_PING_ID = 10
        const val DEFAULT_LIVENESS_INTERVAL_MS = 45_000L
        const val DEFAULT_PONG_TIMEOUT_MS = 15_000L
        const val DEFAULT_RECONNECT_BASE_MS = 1_000L
        const val DEFAULT_RECONNECT_MAX_MS = 60_000L
        const val DEFAULT_SUBSCRIBE_TIMEOUT_MS = 35_000L
        const val DEFAULT_HYDRATION_TIMEOUT_MS = 20_000L
        const val DEFAULT_CLOSE_TIMEOUT_MS = 5_000L
    }
}

internal class KtorHaExactEntityStreamTransport(
    private val rest: HaAmbientTransport = KtorHaAmbientTransport(),
    private val socketFamilyPolicy: () -> MqttAddressFamilyPolicy = { MqttAddressFamilyPolicy.AUTOMATIC },
) : HaExactEntityStreamTransport {
    override suspend fun subscribe(
        baseUrl: String,
        accessToken: String,
        entityIds: Set<String>,
    ): HaExactEntityConnection = subscribe(baseUrl, accessToken, entityIds, watchRegistry = false)

    override suspend fun subscribe(
        baseUrl: String,
        accessToken: String,
        entityIds: Set<String>,
        watchRegistry: Boolean,
    ): HaExactEntityConnection = withContext(Dispatchers.IO) {
        require(entityIds.isNotEmpty() || watchRegistry)
        val policy = socketFamilyPolicy()
        val client = HaWebSocketClients.client(preferIpv4 = policy.initialPreferIpv4, ipv4Only = policy.ipv4Only)
        var socket: DefaultClientWebSocketSession? = null
        try {
            val active = withTimeout(CONNECT_TIMEOUT_MS) {
                client.webSocketSession(EntityFilterProtocol.upstreamWebSocketUrl(baseUrl))
            }
            socket = active
            authenticate(active, accessToken)
            val batches = presenceSubscriptionBatches(entityIds)
            batches.forEachIndexed { index, batch ->
                active.send(Frame.Text(JSONObject()
                    .put("id", SUBSCRIPTION_ID + index)
                    .put("type", "subscribe_entities")
                    .put("entity_ids", JSONArray(batch.sorted()))
                    .toString()))
            }
            val registryIds = if (watchRegistry) REGISTRY_EVENTS.mapIndexed { index, eventType ->
                val id = batches.size + index + 1
                active.send(Frame.Text(JSONObject()
                    .put("id", id)
                    .put("type", "subscribe_events")
                    .put("event_type", eventType)
                    .toString()))
                id
            }.toSet() else emptySet()
            KtorExactEntityConnection(
                client,
                active,
                HaCompressedEntityProjection(entityIds),
                registryIds,
                batches.size + registryIds.size,
            )
        } catch (error: Exception) {
            runCatching { socket?.close() }
            client.close()
            throw error
        }
    }

    override suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject? =
        rest.state(baseUrl, accessToken, entityId)

    private suspend fun authenticate(socket: DefaultClientWebSocketSession, accessToken: String) {
        withTimeout(AUTH_TIMEOUT_MS) {
            val required = readJson(socket)
            if (required.optString("type") != "auth_required") {
                throw HaProtocolException("Home Assistant did not request WebSocket authentication")
            }
            socket.send(Frame.Text(JSONObject().put("type", "auth").put("access_token", accessToken).toString()))
            when (readJson(socket).optString("type")) {
                "auth_ok" -> Unit
                "auth_invalid" -> throw HaAuthenticationException("Home Assistant rejected the access token")
                else -> throw HaProtocolException("Unexpected Home Assistant authentication response")
            }
        }
    }

    private suspend fun readJson(socket: DefaultClientWebSocketSession): JSONObject {
        while (true) {
            val frame = socket.incoming.receive()
            if (frame is Frame.Text) return JSONObject(frame.readText())
        }
    }

    private class KtorExactEntityConnection(
        private val client: HttpClient,
        private val socket: DefaultClientWebSocketSession,
        private val projection: HaCompressedEntityProjection,
        private val registrySubscriptionIds: Set<Int>,
        private val pingIdOffset: Int,
    ) : HaExactEntityConnection {
        private val pending = ArrayDeque<HaExactSocketMessage>()

        override suspend fun receive(): HaExactSocketMessage {
            while (true) {
                if (pending.isNotEmpty()) return pending.removeFirst()
                val frame = socket.incoming.receive()
                if (frame !is Frame.Text) continue
                val json = JSONObject(frame.readText())
                return when (json.optString("type")) {
                    "event" -> {
                        if (json.optInt("id") in registrySubscriptionIds) {
                            return HaExactSocketMessage.RegistryChanged
                        }
                        pending.addAll(projection.applyAll(json.optJSONObject("event") ?: JSONObject()))
                        if (pending.isEmpty()) HaExactSocketMessage.Other else pending.removeFirst()
                    }
                    "result" -> if (json.optBoolean("success")) HaExactSocketMessage.Other else {
                        val message = json.optJSONObject("error")?.optString("message")
                            ?.take(MAX_ERROR_CHARS).orEmpty()
                        throw HaProtocolException(message.ifBlank { "Home Assistant rejected the entity subscription" })
                    }
                    "pong" -> HaExactSocketMessage.Pong(
                        (json.optLong("id", -1L) - pingIdOffset.toLong()).toInt(),
                    )
                    else -> HaExactSocketMessage.Other
                }
            }
        }

        override suspend fun ping(id: Int) {
            socket.send(Frame.Text(JSONObject()
                .put("id", id.toLong() + pingIdOffset.toLong())
                .put("type", "ping")
                .toString()))
        }

        override suspend fun close() {
            try {
                socket.close()
            } finally {
                client.close()
            }
        }
    }

    private companion object {
        const val SUBSCRIPTION_ID = 1
        val REGISTRY_EVENTS = listOf(
            "device_registry_updated",
            "entity_registry_updated",
            "area_registry_updated",
        )
        const val MAX_ERROR_CHARS = 240
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val AUTH_TIMEOUT_MS = 15_000L
    }
}

/** Expands HA's permission-aware exact-entity diff stream into ordinary state objects. */
internal class HaCompressedEntityProjection(entityIds: Set<String>) {
    constructor(entityId: String) : this(setOf(entityId))

    private val entityIds = entityIds.toSet()
    private val states = linkedMapOf<String, JSONObject>()

    fun apply(event: JSONObject): HaExactSocketMessage =
        applyAll(event).firstOrNull() ?: HaExactSocketMessage.Other

    fun applyAll(event: JSONObject): List<HaExactSocketMessage> {
        val updates = ArrayList<HaExactSocketMessage>(entityIds.size)
        event.optJSONObject("a")?.let { addedRoot ->
            entityIds.forEach { entityId -> addedRoot.optJSONObject(entityId)?.let { added ->
                states[entityId] = JSONObject()
                    .put("entity_id", entityId)
                    .put("state", added.optString("s"))
                    .put("attributes", added.optJSONObject("a") ?: JSONObject())
                    .also { applyTimes(it, added) }
                updates += HaExactSocketMessage.State(entityId, JSONObject(checkNotNull(states[entityId]).toString()))
            } }
        }
        val removed = event.optJSONArray("r")
        if (removed != null) {
            for (index in 0 until removed.length()) {
                val entityId = removed.optString(index)
                if (entityId in entityIds) {
                    states.remove(entityId)
                    updates += HaExactSocketMessage.Missing(entityId)
                }
            }
        }
        event.optJSONObject("c")?.let { changedRoot ->
            entityIds.forEach { entityId -> changedRoot.optJSONObject(entityId)?.let { changed ->
                states[entityId]?.let { current ->
                    changed.optJSONObject("+")?.let { additions ->
                        if (additions.has("s")) current.put("state", additions.optString("s"))
                        additions.optJSONObject("a")?.let { addedAttributes ->
                            val attributes = current.optJSONObject("attributes")
                                ?: JSONObject().also { current.put("attributes", it) }
                            val keys = addedAttributes.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                attributes.put(key, addedAttributes.get(key))
                            }
                        }
                        applyTimes(current, additions)
                    }
                    changed.optJSONObject("-")?.optJSONArray("a")?.let { removedAttributes ->
                        val attributes = current.optJSONObject("attributes") ?: JSONObject()
                        for (index in 0 until removedAttributes.length()) {
                            attributes.remove(removedAttributes.optString(index))
                        }
                    }
                    updates += HaExactSocketMessage.State(entityId, JSONObject(current.toString()))
                }
            } }
        }
        return updates
    }

    private fun applyTimes(target: JSONObject, compressed: JSONObject) {
        if (compressed.has("lc")) {
            val changed = epochSeconds(compressed.optDouble("lc", Double.NaN)) ?: return
            target.put("last_changed", changed)
            target.put("last_updated", changed)
        }
        if (compressed.has("lu")) {
            epochSeconds(compressed.optDouble("lu", Double.NaN))?.let { target.put("last_updated", it) }
        }
    }

    private fun epochSeconds(value: Double): String? = value.takeIf(Double::isFinite)
        ?.let { Instant.ofEpochMilli((it * 1_000.0).toLong()).toString() }
}
