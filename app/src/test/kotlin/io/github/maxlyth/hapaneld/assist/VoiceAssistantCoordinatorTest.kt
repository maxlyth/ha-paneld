package io.github.maxlyth.hapaneld.assist

import io.github.maxlyth.hapaneld.audio.FakeMicrophoneSource
import io.github.maxlyth.hapaneld.audio.MicPurpose
import io.github.maxlyth.hapaneld.audio.PcmConsumer
import io.github.maxlyth.hapaneld.audio.PcmFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class VoiceAssistantCoordinatorTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mic = FakeMicrophoneSource()
    private var sourceRequests = 0
    private val state = VoiceStateAuthority()
    private val foregroundCalls = CopyOnWriteArrayList<Boolean>()
    private var foregroundAccepts = true
    private var settings = VoiceSettings(enabled = true, wakeWords = listOf("okay_nabu"), pipelines = mapOf("hey_jarvis" to "pipe-2"))
    private var hasMicrophone = true

    private class FakeEngine(val onActivation: (WakeWordActivation) -> Unit) : WakeWordEngine {
        var closed = false
        override fun onFrame(frame: PcmFrame) {}
        override fun close() { closed = true }
    }

    private val engines = CopyOnWriteArrayList<FakeEngine>()
    private var engineAvailable = true
    private val engineFactory = WakeWordEngineFactory { _, onActivation ->
        if (!engineAvailable) null else FakeEngine(onActivation).also(engines::add)
    }

    /** Gates a run's unwinding, modelling a client whose teardown is NonCancellable. */
    private var teardownGate: CompletableDeferred<Unit>? = null

    private inner class ScriptedRunner : AssistRunner {
        val requests = CopyOnWriteArrayList<AssistRunRequest>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<AssistOutcome>()
        var attachedPurpose: MicPurpose? = null
        override suspend fun run(
            request: AssistRunRequest,
            attachAudio: (PcmConsumer) -> AutoCloseable,
            playback: AssistPlayback,
        ): AssistOutcome {
            requests += request
            val attachment = attachAudio(object : PcmConsumer { override fun onFrame(frame: PcmFrame) {} })
            started.complete(Unit)
            return try {
                release.await()
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    teardownGate?.await()
                    attachment.close()
                }
            }
        }
    }

    private val runners = CopyOnWriteArrayList<ScriptedRunner>()
    private val playback = AssistPlayback { }

    private fun coordinator(retryMs: Long = 60_000, maxTurns: Int = 5) = VoiceAssistantCoordinator(
        scope = scope,
        settings = { settings },
        microphoneAvailable = { hasMicrophone },
        source = { sourceRequests += 1; mic },
        engineFactory = engineFactory,
        runnerFactory = { ScriptedRunner().also(runners::add) },
        playback = playback,
        foregroundMicrophone = { active -> foregroundCalls += active; foregroundAccepts },
        state = state,
        foregroundRetryMs = retryMs,
        maxConversationTurns = maxTurns,
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun awaitRunner(index: Int): ScriptedRunner = runBlocking {
        withTimeout(2_000) {
            while (runners.size <= index) kotlinx.coroutines.delay(5)
            runners[index].also { it.started.await() }
        }
    }

    private fun awaitState(expected: VoiceState) = runBlocking {
        withTimeout(2_000) { while (state.current() != expected) kotlinx.coroutines.delay(5) }
    }

    @Test
    fun `start arms the engine on a wake-word lease and reports idle`() {
        val c = coordinator()
        c.start()
        assertEquals(1, engines.size)
        assertEquals(listOf(MicPurpose.WAKE_WORD), mic.activeLeases.map { it.purpose })
        assertEquals(VoiceState.IDLE, state.current())
        assertEquals(listOf(true), foregroundCalls)
    }

    @Test
    fun `disabled settings stand the coordinator down and release the microphone`() {
        val c = coordinator()
        c.start()
        settings = settings.copy(enabled = false)
        c.start()
        assertTrue(mic.activeLeases.isEmpty())
        assertTrue(engines.single().closed)
        assertEquals(VoiceState.OFF, state.current())
        assertEquals(listOf(true, false), foregroundCalls)
    }

    @Test
    fun `no microphone capability means nothing is armed`() {
        hasMicrophone = false
        val c = coordinator()
        c.start()
        assertTrue(engines.isEmpty())
        assertTrue(mic.leases.isEmpty())
        assertEquals(VoiceState.OFF, state.current())
    }

    @Test
    fun `an activation pauses the wake lease, runs the mapped pipeline with the phrase, then resumes`() {
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("hey_jarvis", "hey jarvis"))
        val runner = awaitRunner(0)
        assertEquals("pipe-2", runner.requests.single().pipelineId)
        assertEquals("hey jarvis", runner.requests.single().wakeWordPhrase)
        assertTrue(mic.leases[0].paused)
        assertEquals(MicPurpose.ASSIST, mic.leases[1].purpose)
        assertEquals(VoiceState.LISTENING, state.current())
        runner.release.complete(AssistOutcome(sttText = "turn on the lights"))
        awaitState(VoiceState.IDLE)
        assertFalse(mic.leases[0].paused)
        assertTrue(mic.leases[1].closed)
    }

    @Test
    fun `an unmapped wake word runs the preferred pipeline`() {
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        val runner = awaitRunner(0)
        assertNull(runner.requests.single().pipelineId)
        runner.release.complete(AssistOutcome())
        awaitState(VoiceState.IDLE)
    }

    @Test
    fun `a second activation during a run is ignored`() {
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        val runner = awaitRunner(0)
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        assertEquals(VoiceTestTrigger.Result.Refused("a voice run is already in progress"), c.trigger())
        assertEquals(1, runners.size)
        runner.release.complete(AssistOutcome())
        awaitState(VoiceState.IDLE)
    }

    @Test
    fun `a continued conversation carries the id and stops at the turn bound`() {
        val c = coordinator(maxTurns = 2)
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        val first = awaitRunner(0)
        first.release.complete(AssistOutcome(conversationId = "conv-1", continueConversation = true))
        val second = awaitRunner(1)
        assertEquals("conv-1", second.requests.single().conversationId)
        assertNull(second.requests.single().wakeWordPhrase)
        second.release.complete(AssistOutcome(conversationId = "conv-1", continueConversation = true))
        awaitState(VoiceState.IDLE)
        assertEquals(2, runners.size)
    }

    @Test
    fun `a failed run reports error, a silent duplicate does not`() {
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        awaitRunner(0).release.complete(AssistOutcome(error = AssistError("timeout", "no reply")))
        awaitState(VoiceState.ERROR)
        assertFalse(mic.leases[0].paused)
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        val second = awaitRunner(1)
        assertEquals(VoiceState.LISTENING, state.current())
        second.release.complete(AssistOutcome(error = AssistError(AssistError.DUPLICATE_WAKE_UP, "dup")))
        awaitState(VoiceState.IDLE)
    }

    @Test
    fun `tap to talk without an engine leases the microphone only for the run`() {
        engineAvailable = false
        val c = coordinator()
        c.start()
        assertTrue(mic.leases.isEmpty())
        assertEquals(VoiceState.IDLE, state.current())
        assertTrue("no lease, so no foreground claim", foregroundCalls.isEmpty())
        assertEquals(VoiceTestTrigger.Result.Accepted, c.trigger())
        val runner = awaitRunner(0)
        assertEquals(listOf(MicPurpose.ASSIST), mic.activeLeases.map { it.purpose })
        assertEquals(listOf(true), foregroundCalls)
        runner.release.complete(AssistOutcome())
        awaitState(VoiceState.IDLE)
        assertTrue(mic.activeLeases.isEmpty())
        assertEquals(listOf(true, false), foregroundCalls)
    }

    @Test
    fun `a refused foreground claim reports error, holds no lease and is retried`() {
        foregroundAccepts = false
        val c = coordinator(retryMs = 20)
        c.start()
        assertEquals(VoiceState.ERROR, state.current())
        assertTrue(mic.leases.isEmpty())
        foregroundAccepts = true
        awaitState(VoiceState.IDLE)
        assertEquals(1, mic.activeLeases.size)
    }

    @Test
    fun `trigger is refused while disabled and unavailable without a microphone`() {
        val c = coordinator()
        settings = settings.copy(enabled = false)
        assertEquals(VoiceTestTrigger.Result.Refused("voice assistant is disabled"), c.trigger())
        settings = settings.copy(enabled = true)
        hasMicrophone = false
        assertEquals(VoiceTestTrigger.Result.Unavailable("this panel has no microphone"), c.trigger())
    }

    @Test
    fun `shutdown cancels a run, releases everything and reports off`() {
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        awaitRunner(0)
        assertTrue(c.shutdown(1_000))
        awaitState(VoiceState.OFF)
        runBlocking { withTimeout(2_000) { while (mic.activeLeases.isNotEmpty()) kotlinx.coroutines.delay(5) } }
        assertTrue(engines.single().closed)
        assertEquals(false, foregroundCalls.last())
        c.start()
        assertTrue(mic.activeLeases.isEmpty())
    }

    @Test
    fun `shutdown waits for an in-flight run to unwind before reporting complete`() {
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        val runner = awaitRunner(0)
        // The run only finishes once released, so a shutdown that did not wait would report true here.
        scope.launch {
            kotlinx.coroutines.delay(150)
            runner.release.complete(AssistOutcome())
        }
        assertTrue("shutdown must wait for the run", c.shutdown(5_000))
        assertFalse(c.running)
        assertTrue(mic.activeLeases.isEmpty())
    }

    @Test
    fun `shutdown reports incomplete when a run does not finish within the deadline`() {
        val gate = CompletableDeferred<Unit>()
        teardownGate = gate
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        awaitRunner(0)
        // The run cannot finish unwinding while the gate is closed, so teardown must report the truth.
        assertFalse("a run still unwinding is not a completed teardown", c.shutdown(200))
        gate.complete(Unit)
    }

    @Test
    fun `shutdown never asks for a microphone source that was never obtained`() {
        settings = settings.copy(enabled = false)
        val c = coordinator()
        c.start()
        val before = sourceRequests
        assertTrue(c.shutdown(1_000))
        assertEquals("teardown must not open a microphone to close it", before, sourceRequests)
    }

    @Test
    fun `settings parsing tolerates malformed json and caps active wake words`() {
        val parsed = VoiceSettings.parse(true, "[\"okay_nabu\",\"hey_jarvis\",\"alexa\"]", "{\"hey_jarvis\":\"p2\",\"alexa\":\"\"}")
        assertEquals(listOf("okay_nabu", "hey_jarvis"), parsed.wakeWords)
        assertEquals("p2", parsed.pipelineFor("hey_jarvis"))
        assertNull(parsed.pipelineFor("alexa"))
        assertNull(parsed.pipelineFor("okay_nabu"))
        val broken = VoiceSettings.parse(true, "not json", "[1,2]")
        assertTrue(broken.wakeWords.isEmpty())
        assertTrue(broken.pipelines.isEmpty())
    }
}
