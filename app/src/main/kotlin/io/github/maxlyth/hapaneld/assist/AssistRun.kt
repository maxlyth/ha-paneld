package io.github.maxlyth.hapaneld.assist

/** One Assist pipeline as Home Assistant lists it to a non-admin client. */
internal data class AssistPipeline(val id: String, val name: String)

/** The pipelines this instance offers, plus the one it prefers when the caller names none. */
internal data class AssistPipelineCatalog(
    val pipelines: List<AssistPipeline>,
    val preferredId: String?,
)

/** What one run asks the pipeline to do. */
internal data class AssistRunRequest(
    val pipelineId: String? = null,
    /** Continues an earlier exchange; supplied by the previous run's outcome. */
    val conversationId: String? = null,
    val wakeWordPhrase: String? = null,
    val deviceId: String? = null,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val startStage: String = STAGE_STT,
    val endStage: String = STAGE_TTS,
    /** Server-side run deadline in seconds; Home Assistant defaults to 300 when absent. */
    val timeoutSeconds: Int? = null,
) {
    internal companion object {
        const val DEFAULT_SAMPLE_RATE = 16_000
        const val STAGE_STT = "stt"
        const val STAGE_TTS = "tts"
    }
}

/** Why a run ended badly. Carried in the outcome; never thrown. */
internal data class AssistError(val code: String, val message: String) {
    /**
     * Home Assistant reports the same wake word twice when two satellites hear one phrase. It is an
     * ordinary outcome of a room with more than one microphone, so it never reaches the user.
     */
    val silent: Boolean get() = code == DUPLICATE_WAKE_UP

    internal companion object {
        const val DUPLICATE_WAKE_UP = "duplicate_wake_up_detected"
    }
}

/** Everything one run produced. */
internal data class AssistOutcome(
    val sttText: String? = null,
    val responseText: String? = null,
    val conversationId: String? = null,
    val continueConversation: Boolean = false,
    val ttsUrl: String? = null,
    val error: AssistError? = null,
) {
    val failed: Boolean get() = error != null
}

/** A pipeline event, already parsed off the wire. */
internal sealed interface AssistEvent {
    /**
     * [handlerId] is absent when the run does not start at the speech-to-text stage, in which case
     * no audio is ever wanted. [ttsUrl] appears here only on cores that pre-allocate the reply; it
     * is playable before [TtsEnd] only when [streamResponse] is set.
     */
    data class RunStart(
        val handlerId: Int?,
        val ttsUrl: String? = null,
        val streamResponse: Boolean = false,
        val timeoutSeconds: Int? = null,
    ) : AssistEvent

    data object SttStart : AssistEvent
    data object SttVadStart : AssistEvent
    data object SttVadEnd : AssistEvent
    data class SttEnd(val text: String?) : AssistEvent
    data object IntentStart : AssistEvent
    data object IntentProgress : AssistEvent
    data class IntentEnd(
        val responseText: String?,
        val conversationId: String?,
        val continueConversation: Boolean,
    ) : AssistEvent

    data object TtsStart : AssistEvent
    data class TtsEnd(val url: String?) : AssistEvent
    data object RunEnd : AssistEvent
    data class Failure(val code: String, val message: String) : AssistEvent

    /** A name this build does not model. Kept so an unknown event is inert, never fatal. */
    data class Other(val name: String) : AssistEvent
}

/** Everything that can move a run along. */
internal sealed interface AssistInput {
    data class Event(val event: AssistEvent) : AssistInput

    /** The caller ended the utterance: the button came up, or a local detector heard silence. */
    data object ConsumerStop : AssistInput

    /** The reply finished playing, or playback gave up. */
    data object PlaybackFinished : AssistInput

    /** The terminal result frame for the run command itself. */
    data class Result(val success: Boolean, val code: String?, val message: String?) : AssistInput
}

/** What the driver must do next. The machine performs no I/O of its own. */
internal sealed interface AssistCommand {
    /** Send this exact text frame. */
    data class SendText(val json: String) : AssistCommand

    /** Forward captured audio as binary frames prefixed with [handlerId], oldest first. */
    data class StreamAudio(val handlerId: Int) : AssistCommand

    /**
     * Stop the utterance: flush whatever audio is still queued, then send the one-byte frame that
     * is [handlerId] alone. Emitted at most once per run.
     */
    data class EndAudio(val handlerId: Int) : AssistCommand

    /** Release the microphone attachment. Emitted at most once per run. */
    data object CloseAudio : AssistCommand

    /** Play this media url, still relative to the Home Assistant base url. */
    data class PlayTts(val url: String) : AssistCommand

    /** The run is over; [outcome] is final. */
    data class Finish(val outcome: AssistOutcome) : AssistCommand
}

/** Where a run has got to. */
internal sealed interface AssistState {
    data object Idle : AssistState
    data object Requested : AssistState
    data class Streaming(val handlerId: Int) : AssistState
    data class Ended(val sttText: String?) : AssistState
    data class Responding(val ttsUrl: String) : AssistState
    data object Done : AssistState
    data class Failed(val code: String, val message: String) : AssistState
}

/**
 * The whole shape of one Assist pipeline run, with no transport, no coroutines and no Android.
 *
 * A run is a conversation with fixed order but unreliable membership: any stage may be skipped by a
 * pipeline configuration, the reply url arrives at one of two events, and the final `result` frame
 * may overtake or trail the events it summarises. Keeping that in a pure machine means every one of
 * those paths is reachable in a unit test at no cost, and the driver in [AssistPipelineClient] holds
 * only the parts that genuinely need a socket.
 *
 * Not thread-safe by design: the driver feeds it from one coroutine.
 */
internal class AssistRun(
    private val request: AssistRunRequest,
    private val requestId: Int = DEFAULT_REQUEST_ID,
) {
    var state: AssistState = AssistState.Idle
        private set

    private var outcome = AssistOutcome()
    private var handlerId: Int? = null
    private var stopRequested = false
    private var audioEnded = false
    private var audioClosed = false
    private var played = false
    private var runEnded = false
    private var playbackFinished = false
    private var earlyTtsUrl: String? = null
    private var earlyTtsStreams = false

    /** True once the reply is playing and the run itself is over: nothing more will arrive. */
    val awaitingPlayback: Boolean
        get() = state is AssistState.Responding && runEnded && !playbackFinished

    /** True once [state] can no longer change. */
    val terminal: Boolean
        get() = state is AssistState.Done || state is AssistState.Failed

    /** Sends the run request. Every later command follows from what Home Assistant answers. */
    fun start(): List<AssistCommand> {
        if (state != AssistState.Idle) return emptyList()
        state = AssistState.Requested
        return listOf(AssistCommand.SendText(AssistPipelineJson.runMessage(requestId, request)))
    }

    fun on(input: AssistInput): List<AssistCommand> {
        if (terminal) return emptyList()
        return when (input) {
            is AssistInput.Event -> onEvent(input.event)
            AssistInput.ConsumerStop -> onConsumerStop()
            AssistInput.PlaybackFinished -> onPlaybackFinished()
            is AssistInput.Result ->
                if (input.success) {
                    emptyList()
                } else {
                    fail(input.code ?: RESULT_FAILED, input.message ?: "Home Assistant rejected the run")
                }
        }
    }

    private fun onEvent(event: AssistEvent): List<AssistCommand> = when (event) {
        is AssistEvent.RunStart -> onRunStart(event)
        AssistEvent.SttVadEnd -> endAudio()
        is AssistEvent.SttEnd -> onSttEnd(event)
        is AssistEvent.IntentEnd -> onIntentEnd(event)
        AssistEvent.TtsStart -> onTtsStart()
        is AssistEvent.TtsEnd -> onTtsEnd(event)
        AssistEvent.RunEnd -> onRunEnd()
        is AssistEvent.Failure -> fail(event.code, event.message)
        AssistEvent.SttStart,
        AssistEvent.SttVadStart,
        AssistEvent.IntentStart,
        AssistEvent.IntentProgress,
        is AssistEvent.Other,
        -> emptyList()
    }

    private fun onRunStart(event: AssistEvent.RunStart): List<AssistCommand> {
        earlyTtsUrl = event.ttsUrl
        earlyTtsStreams = event.streamResponse
        if (event.ttsUrl != null) outcome = outcome.copy(ttsUrl = event.ttsUrl)
        val id = event.handlerId
            // No speech stage means no audio is ever wanted: release the microphone immediately
            // rather than holding the privacy indicator on for a run that will never read a frame.
            ?: return closeAudio()
        handlerId = id
        state = AssistState.Streaming(id)
        val commands = mutableListOf<AssistCommand>(AssistCommand.StreamAudio(id))
        // A stop that arrived before the handler id still delivers what was captured: the buffered
        // audio flushes first, and the terminator follows it.
        if (stopRequested) commands += endAudio()
        return commands
    }

    private fun onConsumerStop(): List<AssistCommand> {
        stopRequested = true
        // Before the handler id there is nothing to terminate, and the pre-buffer must survive.
        if (handlerId == null) return emptyList()
        return endAudio()
    }

    private fun onSttEnd(event: AssistEvent.SttEnd): List<AssistCommand> {
        outcome = outcome.copy(sttText = event.text)
        // Home Assistant has the whole utterance; a pipeline without voice-activity detection
        // never sends stt-vad-end, so this is the last chance to stop capturing.
        val commands = endAudio()
        state = AssistState.Ended(event.text)
        return commands
    }

    private fun onIntentEnd(event: AssistEvent.IntentEnd): List<AssistCommand> {
        outcome = outcome.copy(
            responseText = event.responseText ?: outcome.responseText,
            conversationId = event.conversationId ?: outcome.conversationId,
            continueConversation = event.continueConversation,
        )
        return emptyList()
    }

    private fun onTtsStart(): List<AssistCommand> {
        val url = earlyTtsUrl
        // Only a run-start url marked as a stream is playable this early: it names a response being
        // synthesised, so playing it now is the whole point of the core announcing it up front. A
        // plain url from run-start names a file that is not written until tts-end.
        if (url == null || !earlyTtsStreams) return emptyList()
        return play(url)
    }

    private fun onTtsEnd(event: AssistEvent.TtsEnd): List<AssistCommand> {
        val url = earlyTtsUrl ?: event.url ?: return emptyList()
        outcome = outcome.copy(ttsUrl = url)
        return play(url)
    }

    private fun onRunEnd(): List<AssistCommand> {
        runEnded = true
        val commands = mutableListOf<AssistCommand>()
        commands += endAudio()
        if (state is AssistState.Responding && !playbackFinished) return commands
        state = AssistState.Done
        commands += AssistCommand.Finish(outcome)
        return commands
    }

    private fun onPlaybackFinished(): List<AssistCommand> {
        playbackFinished = true
        if (!runEnded || state !is AssistState.Responding) return emptyList()
        state = AssistState.Done
        return listOf(AssistCommand.Finish(outcome))
    }

    private fun play(url: String): List<AssistCommand> {
        if (played) return emptyList()
        played = true
        outcome = outcome.copy(ttsUrl = url)
        state = AssistState.Responding(url)
        return listOf(AssistCommand.PlayTts(url))
    }

    private fun fail(code: String, message: String): List<AssistCommand> {
        outcome = outcome.copy(error = AssistError(code, message.take(MAX_MESSAGE_CHARS)))
        state = AssistState.Failed(code, message.take(MAX_MESSAGE_CHARS))
        // A failed run never gets a terminator: the driver drops the audio rather than telling a
        // pipeline that has already given up where the utterance ended.
        val commands = mutableListOf<AssistCommand>()
        commands += closeAudio()
        commands += AssistCommand.Finish(outcome)
        return commands
    }

    private fun endAudio(): List<AssistCommand> {
        val id = handlerId
        val commands = mutableListOf<AssistCommand>()
        if (id != null && !audioEnded) {
            audioEnded = true
            commands += AssistCommand.EndAudio(id)
        }
        commands += closeAudio()
        return commands
    }

    private fun closeAudio(): List<AssistCommand> {
        if (audioClosed) return emptyList()
        audioClosed = true
        return listOf(AssistCommand.CloseAudio)
    }

    internal companion object {
        const val DEFAULT_REQUEST_ID = 1
        const val RESULT_FAILED = "run_rejected"
        const val MAX_MESSAGE_CHARS = 240
    }
}
