package io.github.maxlyth.hapaneld.panelassistant

import android.util.Log
import io.github.maxlyth.hapaneld.HaAuthOwner
import io.github.maxlyth.hapaneld.sensors.HaApiSessionProvider
import io.github.maxlyth.hapaneld.sensors.HaAuthenticationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** One authenticated socket to Home Assistant, already past `auth_ok`. */
internal interface PanelAssistantTransportConnection {
    suspend fun send(text: String)

    /**
     * The next text frame, or null when [timeoutMs] passes first. A timeout must not consume a frame:
     * losing a `session_closed` event at a timeout boundary would leave a session-less socket that
     * still answers pings, and nothing would ever reconnect it.
     */
    suspend fun receive(timeoutMs: Long): String?

    suspend fun close()
}

internal fun interface PanelAssistantTransportConnector {
    /** Opens and authenticates; throws [HaAuthenticationException] when Home Assistant rejects the token. */
    suspend fun connect(baseUrl: String, accessToken: String): PanelAssistantTransportConnection
}

/** Everything a session is opened for. Equal demand never disturbs a live session. */
internal data class PanelAssistantTransportDemand(
    /**
     * The credential's stable identity, deliberately not the access token: an access-token refresh by
     * any consumer leaves this equal, so a healthy session is never recycled because a token rolled.
     */
    val credential: HaAuthOwner,
    val identity: PanelAssistantHelloIdentity,
)

/**
 * Demand for the transport, or null when there is nothing to connect with: no Home Assistant address,
 * no credential, or no panel identity for `hello` to carry.
 */
internal fun panelAssistantTransportDemand(
    credential: HaAuthOwner,
    accessTokenPresent: Boolean,
    identity: PanelAssistantHelloIdentity,
): PanelAssistantTransportDemand? {
    if (credential.url.isBlank()) return null
    if (credential.refreshToken.isBlank() && !accessTokenPresent) return null
    if (identity.did == null) return null
    return PanelAssistantTransportDemand(credential, identity)
}

internal enum class PanelAssistantTransportPhase { STOPPED, CONNECTING, HANDSHAKING, CONNECTED, WAITING }

internal data class PanelAssistantTransportStatus(
    val phase: PanelAssistantTransportPhase = PanelAssistantTransportPhase.STOPPED,
    /** Consecutive attempts since the last accepted `hello`. */
    val attempt: Int = 0,
    /** The code that put the owner on the slow schedule, or the last refusal on the fast one. */
    val refusal: String? = null,
    val slowRetry: Boolean = false,
    val session: PanelAssistantSession? = null,
) {
    /** Log form. Carries no credential, identity or session token. */
    fun describe(): String = buildString {
        append(phase.name.lowercase())
        if (attempt > 0) append(" attempt=").append(attempt)
        refusal?.let { append(" refusal=").append(it) }
        if (slowRetry) append(" slow")
        session?.let {
            append(" protocol=").append(it.protocol)
            append(" authority=").append(it.authority)
            append(" integration=").append(it.integrationVersion)
            append(" capabilities=").append(it.capabilities)
        }
    }
}

/**
 * The panel's long-lived native-transport socket to Panel Assistant (protocol specification section 3).
 *
 * It owns connection, reconnect and backoff: Home Assistant cannot dial a panel, so nothing else ever
 * retries. Unlike the exact-entity stream owner it never parks. Network and protocol failures retry
 * on full-jitter exponential backoff from [backoffBaseMs] to [backoffMaxMs], and the attempt counter
 * resets only on an accepted `hello`. Two conditions move to the fixed [slowRetryMs] schedule instead:
 * a token still rejected after one forced refresh, and a `hello` refusal that retrying cannot change.
 * Every wait, slow or fast, ends early on [nudge] (the default network returned) and on a demand
 * change (the credential or identity moved).
 *
 * `unknown_command` and `unknown_panel` are also what a Home Assistant restart looks like from here:
 * Core accepts WebSocket connections before custom integrations finish loading, and an entry reload
 * briefly has no loaded entry. Within [warmupWindowMs] of demand starting or a session ending, those
 * two codes stay on the fast schedule, so a restart or reload costs seconds rather than the slow
 * interval.
 *
 * Credentials come from the shared [HaApiSessionProvider]; this owner holds no token cache. It sends
 * `hello` and protocol pings only: no observations, no events, no commands.
 */
internal class PanelAssistantTransportOwner(
    private val scope: CoroutineScope,
    private val auth: HaApiSessionProvider,
    private val connector: PanelAssistantTransportConnector,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Returns a value in `[0, bound]`; injected so a test can pin the jitter. */
    private val jitter: (bound: Long) -> Long = { bound -> Random.nextLong(bound + 1) },
    private val backoffBaseMs: Long = 1_000L,
    private val backoffMaxMs: Long = 60_000L,
    private val slowRetryMs: Long = 15L * 60_000L,
    private val warmupWindowMs: Long = 10L * 60_000L,
    private val pingIntervalMs: Long = 30_000L,
    private val pongTimeoutMs: Long = 15_000L,
    private val helloTimeoutMs: Long = 15_000L,
    private val closeTimeoutMs: Long = 2_000L,
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
) : AutoCloseable {
    private val lock = Any()
    private val generation = AtomicLong()
    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private var demand: PanelAssistantTransportDemand? = null
    private var job: Job? = null
    private var stopped = false

    @Volatile
    var status: PanelAssistantTransportStatus = PanelAssistantTransportStatus()
        private set

    /** Start, replace or stop the session to match [next]. An equal demand is a no-op. */
    fun replaceDemand(next: PanelAssistantTransportDemand?) {
        synchronized(lock) {
            if (stopped) return
            if (next == demand && (next == null || job?.isActive == true)) return
            job?.cancel()
            job = null
            demand = next
            val run = generation.incrementAndGet()
            if (next != null) {
                job = scope.launch(workerDispatcher) { runSource(run, next) }
            }
        }
        if (next == null) publish(generation.get(), PanelAssistantTransportStatus())
    }

    /** End any pending wait now; used when the default network becomes available. */
    fun nudge() {
        nudges.trySend(Unit)
    }

    override fun close() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            generation.incrementAndGet()
            job?.cancel()
            job = null
            demand = null
        }
        status = PanelAssistantTransportStatus()
    }

    private sealed interface Retry {
        val refusal: String?

        data class Fast(override val refusal: String? = null) : Retry
        data class Slow(override val refusal: String) : Retry
    }

    private suspend fun runSource(run: Long, demand: PanelAssistantTransportDemand) {
        var attempt = 0
        var forceAuth = false
        var authRefreshed = false
        var warmUntil = monotonicMillis() + warmupWindowMs
        while (generation.get() == run) {
            var connection: PanelAssistantTransportConnection? = null
            val retry: Retry = try {
                publish(run, PanelAssistantTransportStatus(PanelAssistantTransportPhase.CONNECTING, attempt))
                val session = auth.resolve(forceAuth)
                forceAuth = false
                val token = session.accessToken
                when {
                    token == null && session.rejected -> Retry.Slow(REFUSAL_CREDENTIAL_REJECTED)
                    token == null -> Retry.Fast(REFUSAL_CREDENTIAL_UNAVAILABLE)
                    // The credential moved while it was being resolved. The service replaces the
                    // demand for the new credential; until then this generation does not use it.
                    session.owner != demand.credential -> Retry.Fast(REFUSAL_CREDENTIAL_UNAVAILABLE)
                    else -> {
                        val opened = connector.connect(session.baseUrl, token)
                        connection = opened
                        publish(run, PanelAssistantTransportStatus(PanelAssistantTransportPhase.HANDSHAKING, attempt))
                        when (val outcome = handshake(opened, demand.identity)) {
                            is PanelAssistantHelloOutcome.Accepted -> {
                                attempt = 0
                                authRefreshed = false
                                publish(run, PanelAssistantTransportStatus(
                                    PanelAssistantTransportPhase.CONNECTED,
                                    session = outcome.session,
                                ))
                                val reason = holdSession(opened)
                                log("native transport session closed: ${reason.ifEmpty { "unspecified" }}")
                                warmUntil = monotonicMillis() + warmupWindowMs
                                Retry.Fast(REFUSAL_SESSION_CLOSED)
                            }
                            is PanelAssistantHelloOutcome.Refused ->
                                refusalRetry(outcome.code, monotonicMillis() < warmUntil)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: HaAuthenticationException) {
                if (authRefreshed) {
                    Retry.Slow(REFUSAL_AUTH_INVALID)
                } else {
                    authRefreshed = true
                    forceAuth = true
                    Retry.Fast(REFUSAL_AUTH_INVALID)
                }
            } catch (failure: Exception) {
                // Network, TLS, frame bound, closed socket, malformed reply or liveness: never parks.
                Retry.Fast(REFUSAL_TRANSPORT).also {
                    log("native transport attempt failed: ${failure.javaClass.simpleName}")
                }
            } finally {
                connection?.let { open ->
                    withContext(NonCancellable) {
                        withTimeoutOrNull(closeTimeoutMs) { runCatching { open.close() } }
                    }
                }
            }
            if (generation.get() != run) return
            val delayMs = when (retry) {
                is Retry.Fast -> {
                    attempt = nextAttempt(attempt)
                    backoffDelay(attempt)
                }
                is Retry.Slow -> {
                    attempt = nextAttempt(attempt)
                    slowRetryMs
                }
            }
            publish(run, PanelAssistantTransportStatus(
                PanelAssistantTransportPhase.WAITING,
                attempt,
                retry.refusal,
                slowRetry = retry is Retry.Slow,
            ))
            // A nudge that arrived while connected describes a network that is already in use.
            nudges.tryReceive()
            withTimeoutOrNull(delayMs) { nudges.receive() }
        }
    }

    private fun refusalRetry(code: String, warm: Boolean): Retry = when (code) {
        PanelAssistantTransportProtocol.CODE_UNKNOWN_COMMAND,
        PanelAssistantTransportProtocol.CODE_UNKNOWN_PANEL,
        -> if (warm) Retry.Fast(code) else Retry.Slow(code)
        else -> Retry.Slow(code)
    }

    private suspend fun handshake(
        connection: PanelAssistantTransportConnection,
        identity: PanelAssistantHelloIdentity,
    ): PanelAssistantHelloOutcome {
        connection.send(PanelAssistantTransportProtocol.hello(HELLO_ID, identity))
        val deadline = monotonicMillis() + helloTimeoutMs
        while (true) {
            val remaining = deadline - monotonicMillis()
            if (remaining <= 0) throw PanelAssistantProtocolException("hello was not answered")
            val text = connection.receive(remaining)
                ?: throw PanelAssistantProtocolException("hello was not answered")
            PanelAssistantTransportProtocol.helloOutcome(parse(text), HELLO_ID)?.let { return it }
        }
    }

    /**
     * Hold an accepted session until it ends. Returns the `session_closed` reason; throws when the
     * socket fails or stops answering. Pings go out on a fixed cadence, and a ping with no inbound
     * frame of any kind within [pongTimeoutMs] means the socket is dead.
     */
    private suspend fun holdSession(connection: PanelAssistantTransportConnection): String {
        var nextMessageId = HELLO_ID + 1
        var nextPingAt = monotonicMillis() + pingIntervalMs
        var pongDeadline: Long? = null
        while (true) {
            val now = monotonicMillis()
            val awaiting = pongDeadline
            if (awaiting != null && now >= awaiting) {
                throw PanelAssistantProtocolException("Home Assistant stopped answering pings")
            }
            if (awaiting == null && now >= nextPingAt) {
                connection.send(PanelAssistantTransportProtocol.ping(nextMessageId++))
                pongDeadline = now + pongTimeoutMs
                nextPingAt = now + pingIntervalMs
                continue
            }
            val text = connection.receive((awaiting ?: nextPingAt) - now) ?: continue
            pongDeadline = null
            val frame = parse(text)
            when (val event = PanelAssistantTransportProtocol.sessionEvent(frame, HELLO_ID)) {
                is PanelAssistantSessionEvent.Closed -> return event.reason
                is PanelAssistantSessionEvent.Ignored -> log("native transport ignored event kind ${event.kind}")
                null -> Unit
            }
        }
    }

    private fun parse(text: String): JSONObject = try {
        JSONObject(text)
    } catch (malformed: JSONException) {
        throw PanelAssistantProtocolException("Home Assistant sent a malformed frame")
    }

    private fun backoffDelay(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        val ceiling = (backoffBaseMs shl shift).coerceAtMost(backoffMaxMs)
        return jitter(ceiling).coerceIn(MIN_DELAY_MS, ceiling.coerceAtLeast(MIN_DELAY_MS))
    }

    private fun nextAttempt(current: Int): Int = minOf(current, MAX_ATTEMPT - 1) + 1

    private fun publish(run: Long, next: PanelAssistantTransportStatus) {
        val changed = synchronized(lock) {
            if (generation.get() != run) return
            val previous = status
            status = next
            previous.phase != next.phase || previous.refusal != next.refusal ||
                previous.slowRetry != next.slowRetry || previous.session != next.session
        }
        if (changed) log("native transport ${next.describe()}")
    }

    companion object {
        private const val TAG = "PanelAssistantTransport"
        private const val HELLO_ID = 1L
        private const val MAX_ATTEMPT = 1_000
        private const val MIN_DELAY_MS = 250L

        const val REFUSAL_AUTH_INVALID = "auth_invalid"
        const val REFUSAL_CREDENTIAL_REJECTED = "credential_rejected"
        const val REFUSAL_CREDENTIAL_UNAVAILABLE = "credential_unavailable"
        const val REFUSAL_SESSION_CLOSED = "session_closed"
        const val REFUSAL_TRANSPORT = "transport_failure"
    }
}
