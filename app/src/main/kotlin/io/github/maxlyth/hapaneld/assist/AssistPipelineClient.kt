package io.github.maxlyth.hapaneld.assist

import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.audio.PcmConsumer
import io.github.maxlyth.hapaneld.audio.PcmFrame
import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import io.github.maxlyth.hapaneld.mqtt.MqttAddressFamilyPolicy
import io.github.maxlyth.hapaneld.sensors.DashboardHaApiSessionProvider
import io.github.maxlyth.hapaneld.sensors.HaApiSession
import io.github.maxlyth.hapaneld.sensors.HaApiSessionProvider
import io.github.maxlyth.hapaneld.sensors.HaAuthenticationException
import io.github.maxlyth.hapaneld.sensors.HaProtocolException
import io.github.maxlyth.hapaneld.util.HaTransportFault
import io.github.maxlyth.hapaneld.util.HaWebSocketClients
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The panel's end of one authenticated Assist WebSocket, with the handshake already done. */
internal interface AssistSocket {
    /** The next inbound text frame, or null once the peer stopped sending. */
    suspend fun receiveText(): String?
    suspend fun sendText(text: String)
    suspend fun sendBinary(bytes: ByteArray)
    suspend fun close()
}

/**
 * Opens an authenticated Assist socket. The handshake lives behind this seam, exactly as the exact
 * entity stream puts it behind its own transport, so a rejected token surfaces the same way whether
 * it was refused at `auth_invalid` or by the connection never being made.
 */
internal fun interface AssistTransport {
    /** @throws HaAuthenticationException when Home Assistant refuses the token. */
    suspend fun connect(baseUrl: String, accessToken: String): AssistSocket
}

/**
 * Plays one reply to completion.
 *
 * The contract is narrow because the run reports its outcome from it: [play] returns **only** when
 * the audio has actually finished playing. Handing the url to a queue and returning is not
 * completion, and an implementation that does so makes every run claim the panel spoke when it may
 * not have.
 *
 * A reply that failed, or that a later announcement superseded before it finished, is not a
 * successful reply: throw [AssistPlaybackException] to say which. The panel plays announcements
 * through one coordinator that keeps only the newest, so supersession is an ordinary outcome rather
 * than a fault, and it still means this run's answer was never heard.
 *
 * Cancelling the parent run is not a playback failure — it unwinds normally and reports nothing.
 * Cancellation of the playback alone, while the run is still live, is a reply that did not play.
 */
internal fun interface AssistPlayback {
    suspend fun play(url: String)
}

/**
 * A reply that could not be played to completion. [code] reaches the run's outcome unchanged, so it
 * must be one of the playback codes on [AssistPipelineClient].
 */
internal class AssistPlaybackException(
    val code: String = AssistPipelineClient.CODE_PLAYBACK_FAILED,
    message: String,
) : RuntimeException(message)

internal sealed interface AssistCatalogResult {
    data class Catalog(val catalog: AssistPipelineCatalog) : AssistCatalogResult
    data class Failed(val error: AssistError) : AssistCatalogResult
}

/**
 * Drives exactly one Assist pipeline run over one WebSocket.
 *
 * The run is three concurrent things — a reader turning frames into inputs, a sender draining
 * captured audio, and playback of the reply — sequenced by the pure [AssistRun] machine. The
 * microphone callback never touches the socket: [PcmConsumer.onFrame] runs on the capture thread and
 * only enqueues, because a stalled send on that thread would stall the shared capture for every
 * other lease. The queue is bounded and drops its oldest frame under back-pressure, counting what it
 * lost, so a slow uplink costs a syllable rather than the run.
 *
 * Audio is buffered from before the run request is sent and forwarded only once Home Assistant
 * returns a binary handler id, which is what keeps the first syllable of an utterance intact.
 *
 * One instance drives one run. [listPipelines] may be called on its own instance and is independent.
 */
internal class AssistPipelineClient(
    private val auth: HaApiSessionProvider,
    private val transport: AssistTransport = KtorAssistTransport(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Bounds the pre-buffer and the send queue together; 200 frames is 2 s at the canonical rate. */
    private val queueFrames: Int = DEFAULT_QUEUE_FRAMES,
    private val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    /** Used only when neither the caller nor Home Assistant names a deadline. */
    private val defaultRunTimeoutMs: Long = DEFAULT_RUN_TIMEOUT_MS,
    /** The panel's own ceiling: no caller or server value may hold the microphone open past this. */
    private val maxRunTimeoutMs: Long = MAX_RUN_TIMEOUT_MS,
    private val playbackTimeoutMs: Long = DEFAULT_PLAYBACK_TIMEOUT_MS,
) {
    constructor(config: Config) : this(
        DashboardHaApiSessionProvider(config),
        KtorAssistTransport(socketFamilyPolicy = { MqttAddressFamilyPolicy.fromConfig(config.mqttAddressFamily) }),
    )

    private val started = AtomicBoolean(false)
    private val dropped = AtomicInteger()
    private val sent = AtomicInteger()

    @Volatile
    private var inputs: Channel<AssistInput>? = null

    @Volatile
    private var stopPending = false

    /** Frames lost before they reached Home Assistant, including those the capture lease dropped. */
    val droppedAudioFrames: Int get() = dropped.get()

    /** Audio frames actually written to the socket, excluding the end-of-stream terminator. */
    val sentAudioFrames: Int get() = sent.get()

    /**
     * Ends the utterance early. Safe from any thread and at any time: before the run reaches the
     * streaming stage it is remembered, so the captured audio is still delivered before the
     * terminator rather than being thrown away.
     */
    fun requestStop() {
        val channel = inputs
        if (channel == null || channel.trySend(AssistInput.ConsumerStop).isFailure) stopPending = true
    }

    suspend fun listPipelines(): AssistCatalogResult = withContext(dispatcher) {
        when (val connection = connect()) {
            is Connection.Failed -> AssistCatalogResult.Failed(connection.error)
            is Connection.Open -> try {
                withTimeout(handshakeTimeoutMs) { readCatalog(connection.socket) }
            } catch (_: TimeoutCancellationException) {
                AssistCatalogResult.Failed(AssistError(CODE_TIMEOUT, "Home Assistant did not list its pipelines"))
            } finally {
                // Uncancellable for the same reason the run's teardown is: a cancelled listing must
                // still close its socket and the HTTP client behind it.
                withContext(NonCancellable) { connection.socket.close() }
            }
        }
    }

    suspend fun run(
        request: AssistRunRequest,
        attachAudio: (PcmConsumer) -> AutoCloseable,
        playback: AssistPlayback,
    ): AssistOutcome = withContext(dispatcher) {
        if (!started.compareAndSet(false, true)) {
            return@withContext AssistOutcome(
                error = AssistError(CODE_ALREADY_RUN, "This client has already driven a run"),
            )
        }
        when (val connection = connect()) {
            is Connection.Failed -> AssistOutcome(error = connection.error)
            is Connection.Open -> try {
                // The caller gets the same playable url playback was handed, never the site-relative
                // path Home Assistant reported: a run's reply should not need resolving twice.
                drive(request, connection, attachAudio, playback).withResolvedUrl(connection.baseUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // A socket that dies mid-run is an outcome, not a crash: this runs inside an
                // always-on service, and the exception type is kept so the failure stays diagnosable.
                AssistOutcome(error = AssistError(CODE_UNAVAILABLE, error.javaClass.simpleName.take(MAX_DETAIL_CHARS)))
            } finally {
                // A cancelled run still has to close: without this the first suspension point in
                // close() throws and the socket and its HTTP client are left to the collector.
                withContext(NonCancellable) { connection.socket.close() }
            }
        }
    }

    private fun AssistOutcome.withResolvedUrl(baseUrl: String): AssistOutcome {
        val url = ttsUrl ?: return this
        return copy(ttsUrl = AssistPipelineJson.resolveMediaUrl(baseUrl, url))
    }

    private suspend fun readCatalog(socket: AssistSocket): AssistCatalogResult {
        socket.sendText(AssistPipelineJson.listMessage(LIST_REQUEST_ID))
        while (true) {
            val raw = socket.receiveText()
                ?: return AssistCatalogResult.Failed(AssistError(CODE_CLOSED, "Home Assistant closed the socket"))
            val message = runCatching { AssistPipelineJson.parseMessage(raw) }.getOrNull() ?: continue
            if (message !is AssistMessage.Result || message.id != LIST_REQUEST_ID) continue
            val result = message.result
            return if (!message.success || result == null) {
                AssistCatalogResult.Failed(
                    AssistError(
                        message.code ?: CODE_LIST_FAILED,
                        message.message ?: "Home Assistant refused to list its pipelines",
                    ),
                )
            } else {
                AssistCatalogResult.Catalog(AssistPipelineJson.parseCatalog(result))
            }
        }
    }

    private suspend fun drive(
        request: AssistRunRequest,
        connection: Connection.Open,
        attachAudio: (PcmConsumer) -> AutoCloseable,
        playback: AssistPlayback,
    ): AssistOutcome = coroutineScope {
        val socket = connection.socket
        val machine = AssistRun(request, RUN_REQUEST_ID)
        val queue = PcmSendQueue(queueFrames, dropped)
        val channel = Channel<AssistInput>(Channel.UNLIMITED)
        inputs = channel
        var attachment: AutoCloseable? = null
        var senderJob: Job? = null
        var playbackJob: Job? = null
        var deadlineJob: Job? = null
        var playbackError: Throwable? = null
        var armedSeconds: Int? = null
        var deadlineArmed = false
        var outcome: AssistOutcome? = null

        // The deadline is a coroutine rather than a wrapper around the loop, because the run learns
        // its real deadline late: the caller may name one, Home Assistant reports its own at
        // run-start, and either can replace the panel's default while the run is already open.
        fun armDeadline() {
            val seconds = request.timeoutSeconds ?: machine.serverTimeoutSeconds
            if (deadlineArmed && seconds == armedSeconds) return
            armedSeconds = seconds
            deadlineArmed = true
            deadlineJob?.cancel()
            val boundedMs = boundedDeadlineMs(seconds)
            deadlineJob = launch {
                delay(boundedMs)
                channel.trySend(AssistInput.Aborted(CODE_TIMEOUT, "The Assist pipeline did not finish in time"))
            }
        }

        // Every command the machine emits is performed here and nowhere else, so the run's whole
        // effect on the socket, the microphone and playback is one readable list.
        suspend fun execute(commands: List<AssistCommand>): AssistOutcome? {
            var finished: AssistOutcome? = null
            for (command in commands) {
                when (command) {
                    is AssistCommand.SendText -> socket.sendText(command.json)
                    is AssistCommand.StreamAudio -> senderJob = launch {
                        forwardAudio(socket, queue, command.handlerId)
                    }
                    // Closing the queue is what ends the utterance: the sender drains what is still
                    // buffered and only then writes the terminator, so no captured audio is lost.
                    is AssistCommand.EndAudio -> queue.close()
                    AssistCommand.CloseAudio -> {
                        // Releasing a lease twice, or one whose owner already failed, must not end
                        // the run; the field is cleared so teardown cannot repeat it either.
                        runCatching { attachment?.close() }
                        attachment = null
                    }
                    is AssistCommand.PlayTts -> {
                        val url = AssistPipelineJson.resolveMediaUrl(connection.baseUrl, command.url)
                        // A reply the panel could not speak is not a successful run: the failure is
                        // kept and reported beside the transcript rather than swallowed here.
                        playbackJob = launch {
                            try {
                                playback.play(url)
                            } catch (cancelled: CancellationException) {
                                // Recorded AND rethrown: the job must still complete as cancelled,
                                // but a playback cancelled out from under a live run is a reply the
                                // panel never spoke, not a success.
                                playbackError = cancelled
                                throw cancelled
                            } catch (error: Throwable) {
                                // Not rethrown: a reply that would not play must fail this run's
                                // outcome, never tear down the scope that is still reporting it.
                                playbackError = error
                            }
                        }
                    }
                    is AssistCommand.Finish -> finished = command.outcome
                }
            }
            return finished
        }

        val reader = launch {
            try {
                while (true) {
                    val raw = socket.receiveText() ?: break
                    // A frame this build cannot parse is skipped, never fatal: a core that adds a
                    // field must not be able to end a run that is otherwise proceeding normally.
                    // Every event and the terminal result echo the id the run message carried. A
                    // frame belonging to any other command on this socket must not be able to
                    // finish, fail or restart this run, so it is dropped before the machine sees it.
                    when (val message = runCatching { AssistPipelineJson.parseMessage(raw) }.getOrNull()) {
                        is AssistMessage.Event ->
                            if (message.id == RUN_REQUEST_ID) channel.send(AssistInput.Event(message.event))
                        is AssistMessage.Result ->
                            if (message.id == RUN_REQUEST_ID) {
                                channel.send(AssistInput.Result(message.success, message.code, message.message))
                            }
                        else -> Unit
                    }
                }
            } finally {
                channel.close()
            }
        }

        try {
            attachment = try {
                attachAudio(microphone(queue))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Capture can be refused outright (no permission, hardware in use). There is nothing
                // to say to a speech pipeline without audio, so the run ends here rather than
                // sending a request that could only ever time out.
                return@coroutineScope AssistOutcome(
                    error = AssistError(CODE_MICROPHONE_UNAVAILABLE, error.javaClass.simpleName.take(MAX_DETAIL_CHARS)),
                )
            }
            outcome = execute(machine.start())
            armDeadline()
            if (stopPending) {
                stopPending = false
                channel.trySend(AssistInput.ConsumerStop)
            }
            if (outcome == null) {
                for (input in channel) {
                    outcome = execute(machine.on(input))
                    if (outcome != null || machine.awaitingPlayback) break
                    // run-start may have replaced the panel's default with the server's deadline.
                    armDeadline()
                }
            }
            if (outcome == null && machine.awaitingPlayback) {
                // The reply is waited for on its own bound, so a long answer is never reported as a
                // pipeline that timed out — and a reply that never finishes is never reported as
                // one that did.
                val completed = withTimeoutOrNull(playbackTimeoutMs) { playbackJob?.join() }
                val failure = playbackError
                outcome = execute(
                    machine.on(
                        when {
                            completed == null ->
                                AssistInput.Aborted(CODE_PLAYBACK_TIMEOUT, "The reply did not finish playing in time")
                            // Reaching this line at all means the run itself was never cancelled —
                            // a cancelled run unwinds through the join above and reports no outcome.
                            // So a cancellation recorded here happened to the playback alone.
                            failure is CancellationException ->
                                AssistInput.Aborted(CODE_PLAYBACK_CANCELLED, "The reply was cancelled before it finished")
                            failure is AssistPlaybackException ->
                                AssistInput.Aborted(failure.code, failure.message ?: "The reply did not play")
                            failure != null ->
                                AssistInput.Aborted(CODE_PLAYBACK_FAILED, failure.javaClass.simpleName.take(MAX_DETAIL_CHARS))
                            else -> AssistInput.PlaybackFinished
                        },
                    ),
                )
            }
            outcome ?: AssistOutcome(
                error = AssistError(CODE_CLOSED, "Home Assistant closed the socket before the run ended"),
            )
        } finally {
            inputs = null
            // Cancel before closing the queue: a cancelled run must not write a terminator that
            // tells Home Assistant an abandoned utterance ended normally.
            senderJob?.cancel()
            playbackJob?.cancel()
            deadlineJob?.cancel()
            reader.cancel()
            queue.close()
            channel.close()
            runCatching { attachment?.close() }
        }
    }

    /**
     * How long this run may take. The caller's own deadline wins, then the one Home Assistant
     * reported at run-start, then the panel's default; the result is always bounded by
     * [maxRunTimeoutMs] so neither a caller nor a server can hold the microphone and the socket open
     * indefinitely, and floored so a zero cannot end a run before it starts.
     */
    private fun boundedDeadlineMs(seconds: Int?): Long {
        val requested = seconds?.let { it.toLong() * 1000L } ?: defaultRunTimeoutMs
        return requested.coerceAtLeast(MIN_RUN_TIMEOUT_MS).coerceAtMost(maxRunTimeoutMs)
    }

    /**
     * The capture-thread end of the run. It only ever enqueues: socket work here would block the
     * shared capture loop, and the microphone contract requires this callback to return promptly.
     */
    private fun microphone(queue: PcmSendQueue): PcmConsumer = object : PcmConsumer {
        override fun onFrame(frame: PcmFrame) {
            queue.offer(encodePcm(frame))
        }

        // The capture lease drops on its own bounded queue before this client ever sees a frame;
        // counting both losses keeps the reported figure honest under a slow uplink.
        override fun onDropped(count: Int) {
            queue.recordUpstreamDrops(count)
        }
    }

    private suspend fun forwardAudio(socket: AssistSocket, queue: PcmSendQueue, handlerId: Int) {
        val prefix = (handlerId and 0xFF).toByte()
        while (true) {
            val frame = queue.take() ?: break
            val payload = ByteArray(frame.size + 1)
            payload[0] = prefix
            frame.copyInto(payload, destinationOffset = 1)
            socket.sendBinary(payload)
            sent.incrementAndGet()
        }
        // Exactly the handler id and nothing else: Home Assistant reads a one-byte binary frame as
        // the end of the utterance.
        socket.sendBinary(byteArrayOf(prefix))
    }

    private suspend fun connect(): Connection {
        var session = auth.resolve(false)
        if (session.rejected) {
            return Connection.Failed(AssistError(CODE_AUTH_REJECTED, "Home Assistant rejected the panel's token"))
        }
        if (session.baseUrl.isBlank() || session.accessToken.isNullOrBlank()) {
            return Connection.Failed(credentialFailure(session))
        }
        return try {
            open(session)
        } catch (_: HaAuthenticationException) {
            // Exactly one forced refresh and one retry. A token can expire between resolve and
            // handshake; anything beyond one retry is a storm against a server already saying no.
            session = auth.resolve(true)
            if (session.rejected) {
                Connection.Failed(AssistError(CODE_AUTH_REJECTED, "Home Assistant rejected the panel's token"))
            } else if (session.accessToken.isNullOrBlank()) {
                // A refresh that failed in transport left no token, but the credential is not the
                // thing that is wrong.
                Connection.Failed(credentialFailure(session))
            } else {
                try {
                    open(session)
                } catch (_: HaAuthenticationException) {
                    Connection.Failed(AssistError(CODE_AUTH_REJECTED, "Home Assistant rejected the panel's token"))
                }
            }
        }
    }

    /**
     * Why this panel has no usable token. The three reasons are kept apart on purpose: a panel whose
     * network is broken must not tell its owner to sign in again, and a refresh that was never
     * attempted is not the same fact as one that was refused. The classified evidence is reported
     * rather than [HaApiSession.transientDetail], which is raw platform text and can embed the
     * configured host.
     */
    private fun credentialFailure(session: HaApiSession): AssistError = when {
        session.transientDetail != null || session.transientEvidence.fault != HaTransportFault.NONE ->
            AssistError(
                CODE_HA_UNREACHABLE,
                "Home Assistant could not be reached: ${session.transientEvidence.orUnclassified().fault.wire}",
            )
        session.notAttempted ->
            AssistError(CODE_CREDENTIALS_UNAVAILABLE, "No Home Assistant credential was tried")
        else -> AssistError(CODE_NOT_CONFIGURED, "This panel has no Home Assistant credential")
    }

    private suspend fun open(session: HaApiSession): Connection = try {
        val socket = withTimeout(handshakeTimeoutMs) {
            transport.connect(session.baseUrl, checkNotNull(session.accessToken))
        }
        Connection.Open(socket, session.baseUrl)
    } catch (_: TimeoutCancellationException) {
        Connection.Failed(AssistError(CODE_TIMEOUT, "Home Assistant did not accept the connection in time"))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: HaAuthenticationException) {
        throw rejected
    } catch (error: Throwable) {
        Connection.Failed(AssistError(CODE_UNAVAILABLE, error.javaClass.simpleName.take(MAX_DETAIL_CHARS)))
    }

    private sealed interface Connection {
        data class Open(val socket: AssistSocket, val baseUrl: String) : Connection
        data class Failed(val error: AssistError) : Connection
    }

    internal companion object {
        const val LIST_REQUEST_ID = 1
        const val RUN_REQUEST_ID = 1
        const val DEFAULT_QUEUE_FRAMES = 200
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000L
        const val DEFAULT_RUN_TIMEOUT_MS = 60_000L

        /**
         * The panel's ceiling on any derived deadline. Matches Home Assistant's own default run
         * timeout, so an unmodified server's value is honoured in full while a misconfigured or
         * hostile one still cannot pin the microphone open.
         */
        const val MAX_RUN_TIMEOUT_MS = 300_000L
        const val MIN_RUN_TIMEOUT_MS = 1_000L
        const val DEFAULT_PLAYBACK_TIMEOUT_MS = 60_000L
        const val CODE_AUTH_REJECTED = "auth_rejected"
        const val CODE_CREDENTIALS_UNAVAILABLE = "credentials_unavailable"
        const val CODE_NOT_CONFIGURED = "not_configured"
        const val CODE_UNAVAILABLE = "unavailable"
        const val CODE_TIMEOUT = "timeout"
        const val CODE_CLOSED = "closed"
        const val CODE_LIST_FAILED = "list_failed"
        const val CODE_ALREADY_RUN = "run_already_used"
        const val CODE_MICROPHONE_UNAVAILABLE = "microphone_unavailable"
        const val CODE_PLAYBACK_FAILED = "playback_failed"
        const val CODE_PLAYBACK_TIMEOUT = "playback_timeout"
        const val CODE_PLAYBACK_CANCELLED = "playback_cancelled"

        /** A later announcement replaced this reply before it finished; the answer was not heard. */
        const val CODE_PLAYBACK_SUPERSEDED = "playback_superseded"

        /** The panel has a credential but could not reach Home Assistant to use or refresh it. */
        const val CODE_HA_UNREACHABLE = "ha_unreachable"
        const val MAX_DETAIL_CHARS = 120

        /** Canonical capture is signed 16-bit; Home Assistant reads it little-endian. */
        fun encodePcm(frame: PcmFrame): ByteArray {
            val out = ByteArray(frame.samples.size * 2)
            var index = 0
            for (sample in frame.samples) {
                val value = sample.toInt()
                out[index++] = (value and 0xFF).toByte()
                out[index++] = ((value shr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}

/**
 * Bounded hand-off from the capture thread to the sending coroutine.
 *
 * Doubles as the pre-buffer: frames captured before Home Assistant returns a handler id stay here
 * and are sent oldest-first the moment forwarding starts. Full means the oldest frame is discarded,
 * never the newest — the end of an utterance matters more than its lead-in — and every discard is
 * counted so a run can report what it lost.
 */
internal class PcmSendQueue(private val capacityFrames: Int, private val dropped: AtomicInteger) {
    private val lock = Any()
    private val frames = ArrayDeque<ByteArray>()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private var closed = false

    /** Called on the capture thread; never blocks and never performs I/O. */
    fun offer(frame: ByteArray) {
        synchronized(lock) {
            if (closed) return
            while (frames.size >= capacityFrames) {
                frames.removeFirst()
                dropped.incrementAndGet()
            }
            frames.addLast(frame)
        }
        wakeup.trySend(Unit)
    }

    fun recordUpstreamDrops(count: Int) {
        if (count > 0) dropped.addAndGet(count)
    }

    /** No further frames are accepted; [take] returns null once what is queued has been drained. */
    fun close() {
        synchronized(lock) { closed = true }
        wakeup.trySend(Unit)
    }

    /** The next frame, or null once the queue is closed and empty. */
    suspend fun take(): ByteArray? {
        while (true) {
            synchronized(lock) { frames.removeFirstOrNull() }?.let { return it }
            if (synchronized(lock) { closed }) return null
            wakeup.receive()
        }
    }
}

/** The one production transport: a Ktor OkHttp session that has completed the HA auth handshake. */
internal class KtorAssistTransport(
    private val socketFamilyPolicy: () -> MqttAddressFamilyPolicy = { MqttAddressFamilyPolicy.AUTOMATIC },
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
) : AssistTransport {
    override suspend fun connect(baseUrl: String, accessToken: String): AssistSocket = withContext(Dispatchers.IO) {
        val policy = socketFamilyPolicy()
        val client = HaWebSocketClients.client(preferIpv4 = policy.initialPreferIpv4, ipv4Only = policy.ipv4Only)
        var socket: DefaultClientWebSocketSession? = null
        try {
            val active = withTimeout(connectTimeoutMs) {
                HaWebSocketClients.open(client, EntityFilterProtocol.upstreamWebSocketUrl(baseUrl), MAX_WS_FRAME_BYTES)
            }
            socket = active
            authenticate(active, accessToken)
            KtorAssistSocket(client, active)
        } catch (error: Throwable) {
            runCatching { socket?.close() }
            client.close()
            throw error
        }
    }

    private suspend fun authenticate(socket: DefaultClientWebSocketSession, accessToken: String) {
        withTimeout(connectTimeoutMs) {
            if (readJson(socket).optString("type") != "auth_required") {
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

    private class KtorAssistSocket(
        private val client: HttpClient,
        private val socket: DefaultClientWebSocketSession,
    ) : AssistSocket {
        override suspend fun receiveText(): String? {
            while (true) {
                val frame = try {
                    socket.incoming.receive()
                } catch (_: ClosedReceiveChannelException) {
                    return null
                }
                if (frame is Frame.Text) return frame.readText()
            }
        }

        override suspend fun sendText(text: String) = socket.send(Frame.Text(text))

        override suspend fun sendBinary(bytes: ByteArray) = socket.send(Frame.Binary(true, bytes))

        override suspend fun close() {
            runCatching { socket.close() }
            client.close()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** Event frames are small; this matches the bound the exact entity stream applies. */
        const val MAX_WS_FRAME_BYTES = 2L * 1024L * 1024L
    }
}
