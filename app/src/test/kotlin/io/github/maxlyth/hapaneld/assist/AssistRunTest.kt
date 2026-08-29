package io.github.maxlyth.hapaneld.assist

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pipeline run contract, driven through the real parser so the wire shapes are part of the
 * evidence: every case feeds the exact frames Home Assistant sends.
 */
class AssistRunTest {

    @Test fun `the run request carries the pipeline, conversation and wake word`() {
        val run = AssistRun(
            AssistRunRequest(
                pipelineId = "pipe-1",
                conversationId = "conv-7",
                wakeWordPhrase = "okay nabu",
            ),
        )

        val json = JSONObject((run.start().single() as AssistCommand.SendText).json)

        assertEquals(AssistPipelineJson.RUN_TYPE, json.getString("type"))
        assertEquals(1, json.getInt("id"))
        assertEquals("stt", json.getString("start_stage"))
        assertEquals("tts", json.getString("end_stage"))
        assertEquals(16_000, json.getJSONObject("input").getInt("sample_rate"))
        assertEquals("okay nabu", json.getJSONObject("input").getString("wake_word_phrase"))
        assertEquals("pipe-1", json.getString("pipeline"))
        assertEquals("conv-7", json.getString("conversation_id"))
        // Nothing the caller left unset is invented: an empty device id would name the wrong device.
        assertFalse(json.has("device_id"))
        assertFalse(json.has("timeout"))
        assertEquals(AssistState.Requested, run.state)
    }

    @Test fun `a run request carries the caller's deadline`() {
        val json = JSONObject(
            (AssistRun(AssistRunRequest(timeoutSeconds = 12)).start().single() as AssistCommand.SendText).json,
        )

        // optInt, not getInt: a missing key must fail this assertion, not throw past it.
        assertEquals(12, json.optInt("timeout", -1))
    }

    @Test fun `run-start reports the deadline Home Assistant set for the run`() {
        val run = AssistRun(AssistRunRequest())
        run.start()
        assertNull(run.serverTimeoutSeconds)

        run.on(runStart(200))

        // The driver bounds the run by this when the caller named no deadline of its own.
        assertEquals(300, run.serverTimeoutSeconds)
    }

    @Test fun `a driver abort keeps everything the run had already learned`() {
        val run = AssistRun(AssistRunRequest())
        run.start()
        run.on(runStart(200))
        run.on(event("stt-end", """{"stt_output":{"text":"turn on the lamp"}}"""))
        run.on(
            event(
                "intent-end",
                """{"intent_output":{"response":{"speech":{"plain":{"speech":"Done"}}},""" +
                    """"conversation_id":"conv-4"}}""",
            ),
        )
        run.on(event("tts-end", """{"tts_output":{"url":"/api/tts_proxy/reply.mp3"}}"""))

        val commands = run.on(AssistInput.Aborted("playback_failed", "IOException"))
        val outcome = (commands.last() as AssistCommand.Finish).outcome

        // The failure is the last stage only; what the panel heard and answered still happened.
        assertEquals(AssistError("playback_failed", "IOException"), outcome.error)
        assertEquals("turn on the lamp", outcome.sttText)
        assertEquals("Done", outcome.responseText)
        assertEquals("conv-4", outcome.conversationId)
        assertEquals("/api/tts_proxy/reply.mp3", outcome.ttsUrl)
        assertTrue(run.terminal)
    }

    @Test fun `a full run reports transcript, response, conversation and reply url`() {
        val run = AssistRun(AssistRunRequest())
        run.start()

        assertEquals(listOf(AssistCommand.StreamAudio(200)), run.on(runStart(200)))
        assertEquals(AssistState.Streaming(200), run.state)

        assertEquals(
            listOf(AssistCommand.EndAudio(200), AssistCommand.CloseAudio),
            run.on(event("stt-vad-end", """{"timestamp":1.5}""")),
        )
        assertEquals(
            emptyList<AssistCommand>(),
            run.on(event("stt-end", """{"stt_output":{"text":"turn on the lamp"}}""")),
        )
        assertEquals(AssistState.Ended("turn on the lamp"), run.state)

        run.on(
            event(
                "intent-end",
                """{"intent_output":{"response":{"speech":{"plain":{"speech":"Turned on the lamp"}}},""" +
                    """"conversation_id":"conv-9","continue_conversation":true}}""",
            ),
        )
        assertEquals(
            listOf(AssistCommand.PlayTts("/api/tts_proxy/reply.mp3")),
            run.on(event("tts-end", """{"tts_output":{"token":"t","url":"/api/tts_proxy/reply.mp3"}}""")),
        )
        assertEquals(AssistState.Responding("/api/tts_proxy/reply.mp3"), run.state)

        assertEquals(emptyList<AssistCommand>(), run.on(event("run-end")))
        assertTrue(run.awaitingPlayback)
        assertFalse(run.terminal)

        val outcome = (run.on(AssistInput.PlaybackFinished).single() as AssistCommand.Finish).outcome
        assertEquals(AssistState.Done, run.state)
        assertEquals("turn on the lamp", outcome.sttText)
        assertEquals("Turned on the lamp", outcome.responseText)
        assertEquals("conv-9", outcome.conversationId)
        assertTrue(outcome.continueConversation)
        assertEquals("/api/tts_proxy/reply.mp3", outcome.ttsUrl)
        assertNull(outcome.error)
        assertFalse(outcome.failed)
    }

    @Test fun `the utterance is terminated exactly once however it ends`() {
        val run = AssistRun(AssistRunRequest())
        run.start()
        run.on(runStart(200))

        assertEquals(
            listOf(AssistCommand.EndAudio(200), AssistCommand.CloseAudio),
            run.on(AssistInput.ConsumerStop),
        )
        // Three more paths reach the same terminator; a second one would end an utterance Home
        // Assistant has already closed, and a second microphone release would be a double free.
        assertEquals(emptyList<AssistCommand>(), run.on(AssistInput.ConsumerStop))
        assertEquals(emptyList<AssistCommand>(), run.on(event("stt-vad-end")))
        assertEquals(emptyList<AssistCommand>(), run.on(event("stt-end", """{"stt_output":{"text":"hi"}}""")))
    }

    @Test fun `speech-to-text end terminates an utterance no detector closed`() {
        val run = AssistRun(AssistRunRequest())
        run.start()
        run.on(runStart(7))

        // A pipeline without voice-activity detection never sends stt-vad-end.
        assertEquals(
            listOf(AssistCommand.EndAudio(7), AssistCommand.CloseAudio),
            run.on(event("stt-end", """{"stt_output":{"text":"hello"}}""")),
        )
    }

    @Test fun `a stop before the handler id still delivers the buffered utterance`() {
        val run = AssistRun(AssistRunRequest())
        run.start()

        // Nothing can be terminated before Home Assistant names the audio handler, and the audio
        // captured meanwhile must survive: dropping it would clip the whole request.
        assertEquals(emptyList<AssistCommand>(), run.on(AssistInput.ConsumerStop))

        assertEquals(
            listOf(AssistCommand.StreamAudio(3), AssistCommand.EndAudio(3), AssistCommand.CloseAudio),
            run.on(runStart(3)),
        )
    }

    @Test fun `a streaming reply plays as soon as synthesis starts`() {
        val run = AssistRun(AssistRunRequest())
        run.start()
        run.on(
            runStart(
                12,
                ""","tts_output":{"token":"t","url":"/api/tts_proxy/stream.mp3","stream_response":true}""",
            ),
        )

        assertEquals(
            listOf(AssistCommand.PlayTts("/api/tts_proxy/stream.mp3")),
            run.on(event("tts-start")),
        )
        // The reply is already playing; the same url arriving again must not restart it.
        assertEquals(
            emptyList<AssistCommand>(),
            run.on(event("tts-end", """{"tts_output":{"url":"/api/tts_proxy/stream.mp3"}}""")),
        )
    }

    @Test fun `a reply that is not a stream waits for the finished file`() {
        val run = AssistRun(AssistRunRequest())
        run.start()
        run.on(runStart(12, ""","tts_output":{"token":"t","url":"/api/tts_proxy/early.mp3"}"""))

        // Without stream_response the url names a file that does not exist until tts-end, so
        // playing it when synthesis starts would play nothing at all.
        assertEquals(emptyList<AssistCommand>(), run.on(event("tts-start")))
        assertTrue(run.on(event("tts-end", """{"tts_output":{"url":"/api/tts_proxy/early.mp3"}}""")).isNotEmpty())
    }

    @Test fun `a reply url announced at run-start wins over the one repeated at tts-end`() {
        val run = AssistRun(AssistRunRequest())
        run.start()
        run.on(runStart(12, ""","tts_output":{"token":"t","url":"/api/tts_proxy/early.mp3"}"""))

        val commands = run.on(event("tts-end", """{"tts_output":{"url":"/api/tts_proxy/late.mp3"}}"""))

        assertEquals(listOf(AssistCommand.PlayTts("/api/tts_proxy/early.mp3")), commands)
    }

    @Test fun `a run with no speech stage releases the microphone at once`() {
        val run = AssistRun(AssistRunRequest(startStage = "intent"))
        val json = JSONObject((run.start().single() as AssistCommand.SendText).json)
        assertFalse(json.getJSONObject("input").has("sample_rate"))

        // No handler id means no audio will ever be read, so holding the capture open would light
        // the platform microphone indicator for a run that never listens.
        assertEquals(listOf(AssistCommand.CloseAudio), run.on(event("run-start", """{"runner_data":{}}""")))
        assertEquals(AssistState.Requested, run.state)
    }

    @Test fun `a duplicate wake word ends the run silently`() {
        val run = AssistRun(AssistRunRequest())
        run.start()
        run.on(runStart(200))

        val commands = run.on(
            event("error", """{"code":"duplicate_wake_up_detected","message":"Duplicate wake-up detected"}"""),
        )

        // Two satellites hearing one phrase is an ordinary room, not a fault to report.
        val outcome = (commands.last() as AssistCommand.Finish).outcome
        assertEquals("duplicate_wake_up_detected", outcome.error?.code)
        assertTrue(outcome.error?.silent == true)
        assertTrue(run.terminal)
        assertEquals(AssistState.Failed("duplicate_wake_up_detected", "Duplicate wake-up detected"), run.state)
        // Terminal is terminal: a late event cannot revive a finished run.
        assertEquals(emptyList<AssistCommand>(), run.on(event("run-end")))
    }

    @Test fun `a failed run is never told where the utterance ended`() {
        val run = AssistRun(AssistRunRequest())
        run.start()
        run.on(runStart(200))

        val commands = run.on(event("error", """{"code":"stt_provider_missing","message":"No provider"}"""))

        // The microphone is released, but a pipeline that has already given up is not sent a
        // terminator: the utterance it describes is one nothing will ever read.
        assertEquals(AssistCommand.CloseAudio, commands.first())
        assertFalse(commands.any { it is AssistCommand.EndAudio })
    }

    @Test fun `an ordinary pipeline error is not silent`() {
        val run = AssistRun(AssistRunRequest())
        run.start()
        run.on(runStart(200))

        val commands = run.on(event("error", """{"code":"stt-provider-missing","message":"No speech provider"}"""))
        val outcome = (commands.last() as AssistCommand.Finish).outcome

        assertEquals("stt-provider-missing", outcome.error?.code)
        assertFalse(outcome.error?.silent == true)
    }

    @Test fun `an unsuccessful result frame ends the run`() {
        val run = AssistRun(AssistRunRequest())
        run.start()
        val message = AssistPipelineJson.parseMessage(
            """{"id":1,"type":"result","success":false,"error":{"code":"not_found","message":"Unknown pipeline"}}""",
        ) as AssistMessage.Result

        assertFalse(message.success)
        val commands = run.on(AssistInput.Result(message.success, message.code, message.message))
        val outcome = (commands.last() as AssistCommand.Finish).outcome

        assertEquals(AssistError("not_found", "Unknown pipeline"), outcome.error)
        assertTrue(run.terminal)
    }

    @Test fun `a successful result frame is not the end of the run`() {
        val run = AssistRun(AssistRunRequest())
        run.start()
        run.on(runStart(200))

        assertEquals(emptyList<AssistCommand>(), run.on(AssistInput.Result(true, null, null)))
        assertFalse(run.terminal)
    }

    @Test fun `a run without a reply finishes at run-end`() {
        val run = AssistRun(AssistRunRequest())
        run.start()
        run.on(runStart(200))
        run.on(event("stt-end", """{"stt_output":{"text":"lights out"}}"""))

        val commands = run.on(event("run-end"))

        // One command, and it is the finish: an utterance already terminated is not terminated again.
        assertEquals(1, commands.size)
        val outcome = (commands.first() as AssistCommand.Finish).outcome
        assertEquals(AssistState.Done, run.state)
        assertEquals("lights out", outcome.sttText)
        assertNull(outcome.ttsUrl)
        assertFalse(run.awaitingPlayback)
    }

    @Test fun `intent end reads the conversation identity from either place`() {
        val beside = AssistPipelineJson.parseEvent(
            JSONObject(
                """{"type":"intent-end","data":{"conversation_id":"outer","continue_conversation":true,""" +
                    """"intent_output":{"response":{"speech":{"plain":{"speech":"ok"}}}}}}""",
            ),
        ) as AssistEvent.IntentEnd

        assertEquals("outer", beside.conversationId)
        assertTrue(beside.continueConversation)
        assertEquals("ok", beside.responseText)

        val inside = AssistPipelineJson.parseEvent(
            JSONObject("""{"type":"intent-end","data":{"intent_output":{"conversation_id":"inner"}}}"""),
        ) as AssistEvent.IntentEnd

        assertEquals("inner", inside.conversationId)
        assertFalse(inside.continueConversation)
        assertNull(inside.responseText)
    }

    @Test fun `an unmodelled event leaves the run untouched`() {
        val run = AssistRun(AssistRunRequest())
        run.start()
        run.on(runStart(200))

        assertEquals(emptyList<AssistCommand>(), run.on(event("wake_word-start")))
        assertEquals(AssistState.Streaming(200), run.state)
        assertEquals(AssistEvent.Other("wake_word-start"), (event("wake_word-start") as AssistInput.Event).event)
    }

    @Test fun `reply urls resolve against the session base url`() {
        assertEquals(
            "https://ha.example/api/tts_proxy/x.mp3",
            AssistPipelineJson.resolveMediaUrl("https://ha.example/", "/api/tts_proxy/x.mp3"),
        )
        assertEquals(
            "https://ha.example/api/tts_proxy/x.mp3",
            AssistPipelineJson.resolveMediaUrl("https://ha.example", "api/tts_proxy/x.mp3"),
        )
        // An absolute url is already addressed and must not be prefixed with the panel's endpoint.
        assertEquals(
            "https://cloud.example/x.mp3",
            AssistPipelineJson.resolveMediaUrl("https://ha.example", "https://cloud.example/x.mp3"),
        )
    }

    @Test fun `the pipeline catalog keeps the preferred pipeline`() {
        val message = AssistPipelineJson.parseMessage(
            """{"id":4,"type":"result","success":true,"result":{"pipelines":[""" +
                """{"id":"01","name":"Home Assistant","language":"en"},{"id":"02","name":"Ollama"}],""" +
                """"preferred_pipeline":"02"}}""",
        ) as AssistMessage.Result

        val catalog = AssistPipelineJson.parseCatalog(requireNotNull(message.result))

        assertEquals(listOf(AssistPipeline("01", "Home Assistant"), AssistPipeline("02", "Ollama")), catalog.pipelines)
        assertEquals("02", catalog.preferredId)
        assertEquals("""{"id":4,"type":"assist_pipeline/pipeline/list"}""", AssistPipelineJson.listMessage(4))
    }

    private fun runStart(handlerId: Int, extra: String = ""): AssistInput.Event =
        event("run-start", """{"runner_data":{"stt_binary_handler_id":$handlerId,"timeout":300}$extra}""")

    private fun event(name: String, data: String = "{}"): AssistInput.Event {
        val raw = """{"id":1,"type":"event","event":{"type":"$name","data":$data,"timestamp":1.0}}"""
        return AssistInput.Event((AssistPipelineJson.parseMessage(raw) as AssistMessage.Event).event)
    }
}
