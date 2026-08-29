package io.github.maxlyth.hapaneld.assist

import io.github.maxlyth.hapaneld.audio.PcmConsumer
import io.github.maxlyth.hapaneld.audio.PcmFrame
import io.github.maxlyth.hapaneld.sensors.HaApiSession
import io.github.maxlyth.hapaneld.sensors.HaApiSessionProvider
import io.github.maxlyth.hapaneld.sensors.HaAuthenticationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The run driver against a scripted socket. Every case is stepped with [runCurrent], so nothing here
 * depends on wall time and a frame is only ever sent because a coroutine actually ran.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AssistPipelineClientTest {

    @Test fun `audio captured before the handler id arrives is flushed in order behind it`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket)
        val run = harness.start(this)
        runCurrent()

        assertEquals(1, harness.connects.get())
        assertEquals(AssistPipelineJson.RUN_TYPE, org.json.JSONObject(socket.sentText.single()).getString("type"))

        // Capture starts before the request is answered, which is what keeps the first syllable.
        harness.consume(pcmFrame(1, -2))
        harness.consume(pcmFrame(3, 4))
        harness.consume(pcmFrame(5, 6))
        runCurrent()
        assertTrue("no audio may leave before Home Assistant names a handler", socket.sentBinary.isEmpty())

        socket.deliver(runStart(200))
        runCurrent()

        assertEquals(3, socket.sentBinary.size)
        assertEquals(3, harness.client.sentAudioFrames)
        assertEquals(0, harness.client.droppedAudioFrames)
        // Oldest first, each behind the one-byte handler id, samples little-endian signed 16-bit.
        assertEquals(listOf(200, 200, 200), socket.sentBinary.map { it[0].toInt() and 0xFF })
        assertEquals(listOf(1, 3, 5), socket.sentBinary.map { it.sampleAt(0) })
        assertEquals(listOf(-2, 4, 6), socket.sentBinary.map { it.sampleAt(1) })

        finish(socket, run)
    }

    @Test fun `the capture callback never writes to the socket itself`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket)
        val run = harness.start(this)
        runCurrent()
        socket.deliver(runStart(9))
        runCurrent()

        harness.consume(pcmFrame(7, 7))

        // The frame is now queued. If onFrame sent it inline it would be on the socket already, and
        // the capture thread would be blocked behind a network write that stalls every other lease.
        assertTrue(socket.sentBinary.isEmpty())
        runCurrent()
        assertEquals(1, socket.sentBinary.size)

        finish(socket, run)
    }

    @Test fun `an overflowing queue keeps the newest audio`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket, queueFrames = 2)
        val run = harness.start(this)
        runCurrent()

        repeat(5) { harness.consume(pcmFrame(it, it)) }
        runCurrent()
        socket.deliver(runStart(1))
        runCurrent()

        // The end of an utterance carries the request; the lead-in is what may be sacrificed.
        assertEquals(2, socket.sentBinary.size)
        assertEquals(listOf(3, 4), socket.sentBinary.map { it.sampleAt(0) })

        finish(socket, run)
    }

    @Test fun `every frame the queue discards is counted`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket, queueFrames = 2)
        val run = harness.start(this)
        runCurrent()

        repeat(5) { harness.consume(pcmFrame(it, it)) }
        runCurrent()

        // Audio lost to back-pressure is the difference between a clipped answer and a mystery.
        assertEquals(3, harness.client.droppedAudioFrames)

        socket.deliver(runStart(1))
        runCurrent()
        finish(socket, run)
    }

    @Test fun `frames lost inside the capture lease are counted too`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket)
        val run = harness.start(this)
        runCurrent()

        // The shared lease drops on its own queue before this client ever sees a frame; a figure
        // counting only local losses reads as healthy exactly when capture is starving.
        harness.dropUpstream(2)
        assertEquals(2, harness.client.droppedAudioFrames)

        socket.deliver(runStart(1))
        runCurrent()
        finish(socket, run)
    }

    @Test fun `voice activity end flushes the utterance then terminates it with one byte`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket)
        val run = harness.start(this)
        runCurrent()
        socket.deliver(runStart(200))
        runCurrent()

        harness.consume(pcmFrame(11, 12))
        harness.consume(pcmFrame(13, 14))
        socket.deliver(event("stt-vad-end"))
        runCurrent()

        val terminator = socket.sentBinary.last()
        assertEquals(1, terminator.size)
        assertEquals(200, terminator[0].toInt() and 0xFF)
        // Everything captured precedes the terminator: the terminator is what tells Home Assistant
        // the utterance is complete, so a frame after it would be audio the pipeline never hears.
        assertEquals(2, socket.sentBinary.count { it.size > 1 })
        assertEquals(1, socket.sentBinary.count { it.size == 1 })
        assertEquals(1, harness.closes.get())

        finish(socket, run)
    }

    @Test fun `a stop asked for before streaming still delivers what was captured`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket)
        val run = harness.start(this)
        runCurrent()

        harness.consume(pcmFrame(21, 22))
        harness.client.requestStop()
        runCurrent()
        assertTrue(socket.sentBinary.isEmpty())

        socket.deliver(runStart(5))
        runCurrent()

        assertEquals(2, socket.sentBinary.size)
        assertEquals(21, socket.sentBinary.first().sampleAt(0))
        assertEquals(byteArrayOf(5).toList(), socket.sentBinary.last().toList())

        finish(socket, run)
    }

    @Test fun `a completed run returns the transcript, reply and an absolute reply url`() = runTest {
        val socket = FakeAssistSocket()
        val played = mutableListOf<String>()
        val harness = harness(socket, playback = { played += it })
        val run = harness.start(this)
        runCurrent()
        socket.deliver(runStart(200))
        socket.deliver(event("stt-end", """{"stt_output":{"text":"turn on the lamp"}}"""))
        socket.deliver(
            event(
                "intent-end",
                """{"intent_output":{"response":{"speech":{"plain":{"speech":"Done"}}},""" +
                    """"conversation_id":"conv-3","continue_conversation":true}}""",
            ),
        )
        socket.deliver(event("tts-end", """{"tts_output":{"url":"/api/tts_proxy/reply.mp3"}}"""))
        socket.deliver(event("run-end"))
        runCurrent()

        assertTrue(run.isCompleted)
        val outcome = run.await()
        assertEquals("turn on the lamp", outcome.sttText)
        assertEquals("Done", outcome.responseText)
        assertEquals("conv-3", outcome.conversationId)
        assertTrue(outcome.continueConversation)
        // Home Assistant returns a site-relative path; playback needs the panel's own endpoint.
        assertEquals(listOf("https://ha.example/api/tts_proxy/reply.mp3"), played)
        assertEquals("https://ha.example/api/tts_proxy/reply.mp3", outcome.ttsUrl)
        assertNull(outcome.error)
        assertEquals(1, harness.closes.get())
        assertEquals(1, socket.closes)
    }

    @Test fun `a pipeline error ends the run and releases the microphone`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket)
        val run = harness.start(this)
        runCurrent()
        socket.deliver(runStart(200))
        socket.deliver(event("error", """{"code":"duplicate_wake_up_detected","message":"twice"}"""))
        runCurrent()

        assertTrue(run.isCompleted)
        val outcome = run.await()
        assertEquals("duplicate_wake_up_detected", outcome.error?.code)
        assertTrue(outcome.error?.silent == true)
        assertEquals(1, harness.closes.get())
        assertEquals(1, socket.closes)
        // A run Home Assistant abandoned is never told where the utterance ended.
        assertTrue(socket.sentBinary.none { it.size == 1 })
    }

    @Test fun `a socket that closes before the run ends fails the run`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket)
        val run = harness.start(this)
        runCurrent()
        socket.deliver(runStart(200))
        runCurrent()
        socket.endOfStream()
        runCurrent()

        assertTrue(run.isCompleted)
        assertEquals(AssistPipelineClient.CODE_CLOSED, run.await().error?.code)
        assertEquals(1, harness.closes.get())
    }

    @Test fun `a rejected credential fails without opening a socket`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket, session = HaApiSession("https://ha.example", null, rejected = true))
        val run = harness.start(this)
        runCurrent()

        assertEquals(AssistPipelineClient.CODE_AUTH_REJECTED, run.await().error?.code)
        assertEquals(0, harness.connects.get())
        assertEquals(listOf(false), harness.forces)
        // No microphone is opened for a run that cannot reach Home Assistant.
        assertEquals(0, harness.attachments.get())
    }

    @Test fun `an untried credential and a missing one fail apart from each other`() = runTest {
        val untried = harness(
            FakeAssistSocket(),
            session = HaApiSession("https://ha.example", null, notAttempted = true),
        )
        val absent = harness(FakeAssistSocket(), session = HaApiSession("https://ha.example", null))

        val untriedRun = untried.start(this)
        val absentRun = absent.start(this)
        runCurrent()

        assertEquals(AssistPipelineClient.CODE_CREDENTIALS_UNAVAILABLE, untriedRun.await().error?.code)
        assertEquals(AssistPipelineClient.CODE_NOT_CONFIGURED, absentRun.await().error?.code)
        assertEquals(0, untried.connects.get())
        assertEquals(0, absent.connects.get())
    }

    @Test fun `a refused token is retried once with a forced refresh`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket, refuseFirstConnections = 1)
        val run = harness.start(this)
        runCurrent()

        assertEquals(2, harness.connects.get())
        assertEquals(listOf(false, true), harness.forces)

        socket.deliver(runStart(200))
        socket.deliver(event("run-end"))
        runCurrent()
        assertNull(run.await().error)
    }

    @Test fun `a token refused twice stops instead of storming`() = runTest {
        val harness = harness(FakeAssistSocket(), refuseFirstConnections = 2)
        val run = harness.start(this)
        runCurrent()

        assertEquals(AssistPipelineClient.CODE_AUTH_REJECTED, run.await().error?.code)
        assertEquals(2, harness.connects.get())
        assertEquals(listOf(false, true), harness.forces)
    }

    @Test fun `one client drives one run`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket)
        val first = harness.start(this)
        runCurrent()
        socket.deliver(runStart(200))
        socket.deliver(event("run-end"))
        runCurrent()
        assertNull(first.await().error)

        val second = harness.start(this)
        runCurrent()
        assertEquals(AssistPipelineClient.CODE_ALREADY_RUN, second.await().error?.code)
        assertEquals(1, harness.connects.get())
    }

    @Test fun `listing pipelines returns the catalog and closes its socket`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket)
        val listing: Deferred<AssistCatalogResult> = async { harness.client.listPipelines() }
        runCurrent()

        assertEquals(
            AssistPipelineJson.LIST_TYPE,
            org.json.JSONObject(socket.sentText.single()).getString("type"),
        )
        socket.deliver(
            """{"id":1,"type":"result","success":true,"result":{"pipelines":[{"id":"01","name":"Home Assistant"}],""" +
                """"preferred_pipeline":"01"}}""",
        )
        runCurrent()

        val catalog = (listing.await() as AssistCatalogResult.Catalog).catalog
        assertEquals(listOf(AssistPipeline("01", "Home Assistant")), catalog.pipelines)
        assertEquals("01", catalog.preferredId)
        assertEquals(1, socket.closes)
    }

    @Test fun `a refused listing reports the server's own reason`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket)
        val listing = async { harness.client.listPipelines() }
        runCurrent()
        socket.deliver("""{"id":1,"type":"result","success":false,"error":{"code":"unauthorized","message":"no"}}""")
        runCurrent()

        assertEquals(AssistError("unauthorized", "no"), (listing.await() as AssistCatalogResult.Failed).error)
    }

    @Test fun `a microphone that cannot be leased ends the run before it asks`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket, attachFails = true)
        val run = harness.start(this)
        runCurrent()

        assertTrue(run.isCompleted)
        assertEquals(AssistPipelineClient.CODE_MICROPHONE_UNAVAILABLE, run.await().error?.code)
        // Asking a speech pipeline to listen to a microphone that refused to open can only time out.
        assertTrue(socket.sentText.isEmpty())
        assertEquals(1, socket.closes)
    }

    @Test fun `a socket that fails mid-run becomes an outcome, not a crash`() = runTest {
        val socket = FakeAssistSocket()
        socket.failSends = true
        val harness = harness(socket)
        val run = harness.start(this)
        runCurrent()

        assertTrue(run.isCompleted)
        // This client runs inside an always-on service: a dead socket must not propagate.
        assertEquals(AssistPipelineClient.CODE_UNAVAILABLE, run.await().error?.code)
        assertEquals(1, harness.closes.get())
        assertEquals(1, socket.closes)
    }

    private fun TestScope.harness(
        socket: FakeAssistSocket,
        session: HaApiSession = HaApiSession("https://ha.example", "token"),
        queueFrames: Int = 200,
        refuseFirstConnections: Int = 0,
        attachFails: Boolean = false,
        playback: (String) -> Unit = {},
    ): Harness = Harness(socket, session, queueFrames, refuseFirstConnections, attachFails, playback, testScheduler)

    /** Ends a run a case left mid-utterance, so every test tears the driver down deterministically. */
    private fun TestScope.finish(socket: FakeAssistSocket, run: Deferred<AssistOutcome>) {
        socket.deliver(event("run-end"))
        runCurrent()
        if (!run.isCompleted) {
            socket.endOfStream()
            runCurrent()
        }
        assertTrue("the run must reach a terminal outcome", run.isCompleted)
    }

    private class Harness(
        private val socket: FakeAssistSocket,
        private val session: HaApiSession,
        queueFrames: Int,
        private val refuseFirstConnections: Int,
        private val attachFails: Boolean,
        private val playback: (String) -> Unit,
        scheduler: TestCoroutineScheduler,
    ) {
        val forces = mutableListOf<Boolean>()
        val connects = AtomicInteger()
        val attachments = AtomicInteger()
        val closes = AtomicInteger()

        @Volatile
        private var consumer: PcmConsumer? = null

        val client = AssistPipelineClient(
            auth = HaApiSessionProvider { force ->
                forces += force
                session
            },
            transport = AssistTransport { _, _ ->
                if (connects.incrementAndGet() <= refuseFirstConnections) {
                    throw HaAuthenticationException("Home Assistant rejected the access token")
                }
                socket
            },
            dispatcher = StandardTestDispatcher(scheduler),
            queueFrames = queueFrames,
        )

        fun start(scope: TestScope): Deferred<AssistOutcome> = scope.async {
            client.run(
                AssistRunRequest(),
                attachAudio = { pcm ->
                    attachments.incrementAndGet()
                    if (attachFails) throw IllegalStateException("the microphone is already in use")
                    consumer = pcm
                    object : AutoCloseable {
                        override fun close() {
                            closes.incrementAndGet()
                        }
                    }
                },
                playback = { url -> playback(url) },
            )
        }

        fun consume(frame: PcmFrame) {
            checkNotNull(consumer).onFrame(frame)
        }

        fun dropUpstream(count: Int) {
            checkNotNull(consumer).onDropped(count)
        }
    }

    private class FakeAssistSocket : AssistSocket {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        val sentText = mutableListOf<String>()
        val sentBinary = mutableListOf<ByteArray>()
        var failSends = false
        var closes = 0
            private set

        fun deliver(raw: String) {
            inbound.trySend(raw)
        }

        fun endOfStream() {
            inbound.close()
        }

        override suspend fun receiveText(): String? = inbound.receiveCatching().getOrNull()

        override suspend fun sendText(text: String) {
            if (failSends) throw java.io.IOException("broken pipe")
            sentText += text
        }

        override suspend fun sendBinary(bytes: ByteArray) {
            sentBinary += bytes
        }

        override suspend fun close() {
            closes++
            inbound.close()
        }
    }

    private companion object {
        fun pcmFrame(first: Int, second: Int) =
            PcmFrame(shortArrayOf(first.toShort(), second.toShort()), timestampNs = 0L)

        /** Reads sample [index] back out of a sent frame, past the one-byte handler prefix. */
        fun ByteArray.sampleAt(index: Int): Int {
            val low = this[1 + index * 2].toInt() and 0xFF
            val high = this[2 + index * 2].toInt()
            return (high shl 8) or low
        }

        fun runStart(handlerId: Int) =
            event("run-start", """{"runner_data":{"stt_binary_handler_id":$handlerId,"timeout":300}}""")

        fun event(name: String, data: String = "{}") =
            """{"id":1,"type":"event","event":{"type":"$name","data":$data,"timestamp":1.0}}"""
    }
}
