package io.github.maxlyth.hapaneld.assist

import io.github.maxlyth.hapaneld.audio.MicLease
import io.github.maxlyth.hapaneld.audio.MicPurpose
import io.github.maxlyth.hapaneld.audio.MicrophoneSource
import io.github.maxlyth.hapaneld.audio.MicrophoneSourceLifecycle
import io.github.maxlyth.hapaneld.audio.PcmConsumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** The voice settings the coordinator acts on, parsed once per (re)start. */
data class VoiceSettings(
    val enabled: Boolean,
    /** Bundled wake-word model ids to arm, in order; at most [MAX_ACTIVE_WAKE_WORDS] are used. */
    val wakeWords: List<String>,
    /** Wake-word model id to Assist pipeline id; an absent or blank value means the preferred pipeline. */
    val pipelines: Map<String, String>,
) {
    fun pipelineFor(modelId: String?): String? = modelId?.let { pipelines[it] }?.takeIf { it.isNotBlank() }

    companion object {
        const val MAX_ACTIVE_WAKE_WORDS = 2

        /** Tolerant of malformed JSON: the registry validates on write, but a hand-edited store must not crash the service. */
        fun parse(enabled: Boolean, wakeWordsJson: String?, pipelinesJson: String?): VoiceSettings {
            val words = runCatching {
                val array = JSONArray(wakeWordsJson ?: "[]")
                (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
            }.getOrDefault(emptyList()).distinct().take(MAX_ACTIVE_WAKE_WORDS)
            val map = runCatching {
                val obj = JSONObject(pipelinesJson ?: "{}")
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }.getOrDefault(emptyMap())
            return VoiceSettings(enabled, words, map)
        }
    }
}

/** A wake-word activation as the coordinator sees it: which model fired and the phrase it is trained on. */
data class WakeWordActivation(val modelId: String, val phrase: String)

/** An armed wake-word listener: a microphone consumer that reports activations until closed. */
interface WakeWordEngine : PcmConsumer, AutoCloseable

/**
 * Builds an engine for the requested bundled model ids, or returns null when no engine can run here
 * (no native library for this ABI, no bundled model, nothing requested). A null engine leaves the
 * feature in tap-to-talk mode: nothing holds the microphone until a run is asked for.
 */
fun interface WakeWordEngineFactory {
    fun create(modelIds: List<String>, onActivation: (WakeWordActivation) -> Unit): WakeWordEngine?

    companion object {
        val NONE = WakeWordEngineFactory { _, _ -> null }
    }
}

/** One Assist pipeline run. A fresh runner is created per run, mirroring [AssistPipelineClient]. */
internal fun interface AssistRunner {
    suspend fun run(
        request: AssistRunRequest,
        attachAudio: (PcmConsumer) -> AutoCloseable,
        playback: AssistPlayback,
    ): AssistOutcome
}

/**
 * Owns the voice assistant's lifecycle on the panel: arms the wake-word engine on the shared
 * microphone, turns an activation (or a tap-to-talk trigger) into one Assist pipeline run, plays the
 * reply, follows a continued conversation for a bounded number of turns, and reports the phase
 * through [VoiceStateAuthority].
 *
 * Microphone discipline: the engine holds one `WAKE_WORD` lease while idle; a run pauses that lease
 * and takes its own `ASSIST` lease so the capture never stops between the wake word and the
 * utterance. No code here blocks the calling thread; every wait is a coroutine on [scope].
 *
 * Foreground policy: the microphone foreground-service type is claimed immediately before a lease is
 * taken and released as soon as no lease remains, through [foregroundMicrophone], so the type is
 * never held over a closed device. Android 14 refuses that claim when the
 * app is in the background, so a refused claim is retried on a bounded timer rather than treated as
 * terminal, and is retried immediately by [retryStart] when the panel's own activity comes forward.
 */
class VoiceAssistantCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val settings: () -> VoiceSettings,
    private val microphoneAvailable: () -> Boolean,
    private val source: () -> MicrophoneSource?,
    private val engineFactory: WakeWordEngineFactory,
    private val runnerFactory: () -> AssistRunner,
    private val playback: AssistPlayback,
    private val foregroundMicrophone: (Boolean) -> Boolean,
    private val state: VoiceStateAuthority,
    private val foregroundRetryMs: Long = DEFAULT_FOREGROUND_RETRY_MS,
    private val maxConversationTurns: Int = DEFAULT_MAX_CONVERSATION_TURNS,
) : AutoCloseable {

    private val lock = Any()
    private var armed = false
    private var engine: WakeWordEngine? = null
    private var wakeLease: MicLease? = null
    private var runJob: Job? = null
    private var retryJob: Job? = null
    private var foregroundClaimed = false
    private val closed = AtomicBoolean(false)

    // Bumped whenever the listener is replaced or torn down. A callback carries the generation it was
    // armed with, so a hit delivered by an engine that has since been closed or replaced is refused
    // instead of starting a run for a listener the panel no longer has.
    private var engineGeneration = 0L

    // Settings that arrived while a run was in flight. Reconfiguring underneath a run would close the
    // paused wake lease and open an unpaused one alongside the run's own, putting two consumers on one
    // capture; the run's teardown applies this instead.
    private var pendingReconfigure = false

    // True from the moment a run is admitted until its capture attachment has been closed. The
    // microphone foreground-service type must outlive that attachment: dropping it while the run is
    // still unwinding tells the platform the microphone is closed while it is still being read.
    private var runHoldsCapture = false

    // Identifies the run that currently owns the coordinator's run state. A cancelled run unwinds
    // after `stop` has already let go of it, and a replacement can be admitted in between, so the
    // retiring run must prove it is still the owner before clearing anything or releasing the claim.
    private var runGeneration = 0L

    // The source this coordinator actually obtained. Teardown shuts down what was opened and never
    // asks for a source: the supplier builds one on demand, so calling it here would open the
    // microphone on a panel that never used it, purely to close it again.
    private var obtainedSource: MicrophoneSource? = null

    /** True while the wake-word engine holds the microphone. */
    val listening: Boolean get() = synchronized(lock) { wakeLease != null }

    /** True while a pipeline run is in flight. */
    val running: Boolean get() = synchronized(lock) { runJob?.isActive == true }

    /**
     * Apply the current settings: arm when enabled on a microphone-capable panel, otherwise stand
     * down. Safe to call repeatedly; a settings change is applied by calling it again.
     */
    fun start() {
        if (closed.get()) return
        val current = settings()
        if (!current.enabled || !microphoneAvailable()) {
            stop()
            return
        }
        synchronized(lock) {
            armed = true
            if (runJob?.isActive == true) {
                // Apply it when the run drains, not underneath it.
                pendingReconfigure = true
                return
            }
            disarmLocked()
            armEngineLocked(current)
        }
    }

    /** Retry a start that the platform refused, for a caller that knows the app just came forward. */
    fun retryStart() {
        val shouldRetry = synchronized(lock) { armed && wakeLease == null && engine == null && runJob?.isActive != true }
        if (shouldRetry) start()
    }

    /** Stand down: cancel any run, release the microphone, drop the foreground claim, report `off`. */
    fun stop() {
        val job: Job?
        synchronized(lock) {
            armed = false
            retryJob?.cancel()
            retryJob = null
            job = runJob
            runJob = null
            disarmLocked()
        }
        job?.cancel()
        state.set(VoiceState.OFF)
    }

    /** Press-to-talk: start one run with the preferred pipeline, as if a wake word had fired. */
    fun trigger(): VoiceTestTrigger.Result {
        if (closed.get()) return VoiceTestTrigger.Result.Unavailable("voice assistant is shut down")
        val current = settings()
        if (!current.enabled) return VoiceTestTrigger.Result.Refused("voice assistant is disabled")
        if (!microphoneAvailable()) return VoiceTestTrigger.Result.Unavailable("this panel has no microphone")
        return when (beginRun(activation = null, current)) {
            RunAdmission.STARTED -> VoiceTestTrigger.Result.Accepted
            RunAdmission.BUSY -> VoiceTestTrigger.Result.Refused("a voice run is already in progress")
            // The platform refused the microphone foreground service, which on recent Android happens
            // whenever the panel asks from the background. Saying "busy" would send the operator
            // looking for a run that does not exist, and without a retry a panel that was refused once
            // would stay mute until something else happened to rearm it.
            RunAdmission.FOREGROUND_REFUSED ->
                VoiceTestTrigger.Result.Unavailable("the panel could not claim the microphone; it will retry when the dashboard comes forward")
            RunAdmission.NOT_ELIGIBLE -> VoiceTestTrigger.Result.Refused("the voice assistant is not listening")
        }
    }

    /** Why a run was or was not admitted; the press-to-talk caller reports each of these differently. */
    private enum class RunAdmission { STARTED, BUSY, FOREGROUND_REFUSED, NOT_ELIGIBLE }

    /**
     * Proves teardown for the service boundary. Cancels any run and waits, within [timeoutMs], first
     * for that run to finish unwinding and then for the capture thread to release the device.
     * Reports whether both actually completed, because a boundary that is told teardown finished
     * while a coroutine is still running is the case the boundary exists to catch.
     */
    fun shutdown(timeoutMs: Long): Boolean {
        if (!closed.compareAndSet(false, true)) return true
        val job = synchronized(lock) { runJob }
        stop()
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
        val drained = job == null || runBlocking {
            withTimeoutOrNull(remainingMs(deadline)) { job.join() } != null
        }
        val lifecycle = synchronized(lock) { obtainedSource } as? MicrophoneSourceLifecycle
        val released = lifecycle?.shutdown(remainingMs(deadline)) ?: true
        return drained && released
    }

    private fun remainingMs(deadlineNanos: Long): Long =
        ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)

    override fun close() {
        shutdown(DEFAULT_CLOSE_TIMEOUT_MS)
    }

    /** A hit from the listener armed at [generation]; refused once that listener has been replaced. */
    private fun onActivation(generation: Long, activation: WakeWordActivation) {
        beginRun(activation, settings(), generation)
    }

    private fun beginRun(activation: WakeWordActivation?, current: VoiceSettings): RunAdmission =
        beginRun(activation, current, null)

    /**
     * Returns false when the run was refused: one is already in flight, the coordinator has stood
     * down or shut down, the setting is off, or the caller belongs to a superseded listener. The
     * admission decision is taken inside the lock so a callback cannot pass a check that a concurrent
     * stop has already invalidated.
     */
    private fun beginRun(activation: WakeWordActivation?, current: VoiceSettings, generation: Long?): RunAdmission {
        val mic = obtainSource() ?: return RunAdmission.NOT_ELIGIBLE
        synchronized(lock) {
            if (closed.get()) return RunAdmission.NOT_ELIGIBLE
            if (runJob?.isActive == true) return RunAdmission.BUSY
            if (generation != null && (generation != engineGeneration || !armed)) return RunAdmission.NOT_ELIGIBLE
            if (!current.enabled) return RunAdmission.NOT_ELIGIBLE
            if (!claimForegroundLocked()) {
                state.set(VoiceState.ERROR)
                scheduleRetryLocked()
                return RunAdmission.FOREGROUND_REFUSED
            }
            runHoldsCapture = true
            wakeLease?.pause()
            val myGeneration = ++runGeneration
            runJob = scope.launch {
                var failed = false
                try {
                    failed = !converse(mic, activation, current)
                } finally {
                    synchronized(lock) {
                        // A cancelled run unwinds after stop released it, by which time a replacement
                        // may own the coordinator. Clearing state or dropping the claim here would
                        // erase that replacement's run and mute the panel mid-utterance.
                        if (runGeneration != myGeneration) return@synchronized
                        runJob = null
                        // The attachment is closed by the runner before it returns, so the claim has
                        // outlived the capture it was covering and may now be reconsidered.
                        runHoldsCapture = false
                        if (pendingReconfigure) {
                            pendingReconfigure = false
                            disarmLocked()
                            if (armed) armEngineLocked(settings())
                        }
                        val lease = wakeLease
                        // A failure stays visible as `error` until the next run replaces it; the panel is
                        // still listening, which the resumed wake lease proves, but the operator sees why
                        // the last attempt produced nothing.
                        val rest = if (failed) VoiceState.ERROR else VoiceState.IDLE
                        if (lease != null) {
                            lease.resume()
                            state.set(rest)
                        } else {
                            releaseForegroundLocked()
                            state.set(if (armed) rest else VoiceState.OFF)
                        }
                    }
                }
            }
        }
        return RunAdmission.STARTED
    }

    /** Returns false when the exchange ended in a reportable error. */
    private suspend fun converse(mic: MicrophoneSource, activation: WakeWordActivation?, current: VoiceSettings): Boolean {
        var request = AssistRunRequest(
            pipelineId = current.pipelineFor(activation?.modelId),
            wakeWordPhrase = activation?.phrase,
        )
        var turns = 0
        while (true) {
            state.set(VoiceState.LISTENING)
            val outcome = runnerFactory().run(
                request,
                // Closing the attachment is the panel's own signal that it has stopped listening and is
                // waiting on Home Assistant, which is the only phase boundary observable from here.
                attachAudio = { consumer ->
                    val lease = mic.lease(MicPurpose.ASSIST, consumer = consumer)
                    AutoCloseable {
                        lease.close()
                        state.set(VoiceState.PROCESSING)
                    }
                },
                playback = playback,
            )
            val error = outcome.error
            if (error != null) return error.silent
            turns += 1
            if (!outcome.continueConversation || turns >= maxConversationTurns) return true
            request = request.copy(conversationId = outcome.conversationId, wakeWordPhrase = null)
        }
    }

    private fun obtainSource(): MicrophoneSource? = source()?.also { synchronized(lock) { obtainedSource = it } }

    private fun armEngineLocked(current: VoiceSettings) {
        val mic = obtainSource()
        val generation = ++engineGeneration
        val built = if (mic != null && current.wakeWords.isNotEmpty()) {
            engineFactory.create(current.wakeWords) { activation -> onActivation(generation, activation) }
        } else {
            null
        }
        if (mic == null || built == null) {
            // Tap-to-talk only: nothing holds the microphone until a run is requested.
            state.set(VoiceState.IDLE)
            return
        }
        if (!claimForegroundLocked()) {
            built.close()
            state.set(VoiceState.ERROR)
            scheduleRetryLocked()
            return
        }
        engine = built
        wakeLease = mic.lease(MicPurpose.WAKE_WORD, consumer = built)
        state.set(VoiceState.IDLE)
    }

    private fun disarmLocked() {
        // Retiring the listener invalidates its callbacks: a hit already in flight names a generation
        // that no longer matches and is refused rather than starting a run for a closed engine.
        engineGeneration += 1
        wakeLease?.close()
        wakeLease = null
        engine?.close()
        engine = null
        if (runJob?.isActive != true && !runHoldsCapture) releaseForegroundLocked()
    }

    private fun claimForegroundLocked(): Boolean {
        if (foregroundClaimed) return true
        foregroundClaimed = foregroundMicrophone(true)
        return foregroundClaimed
    }

    private fun releaseForegroundLocked() {
        if (!foregroundClaimed) return
        foregroundMicrophone(false)
        foregroundClaimed = false
    }

    private fun scheduleRetryLocked() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(foregroundRetryMs)
            synchronized(lock) { retryJob = null }
            retryStart()
        }
    }

    companion object {
        const val DEFAULT_FOREGROUND_RETRY_MS = 5 * 60_000L
        const val DEFAULT_CLOSE_TIMEOUT_MS = 2_000L
        const val DEFAULT_MAX_CONVERSATION_TURNS = 5
    }
}
