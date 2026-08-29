package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSettingsSpecTest {
    private val voiceEnabled = requireNotNull(SettingsRegistry.spec("voice_enabled"))
    private val wakeWords = requireNotNull(SettingsRegistry.spec("voice_wake_words"))
    private val pipelines = requireNotNull(SettingsRegistry.spec("voice_pipelines"))
    private val audioSource = requireNotNull(SettingsRegistry.spec("voice_audio_source"))
    private val sensitivity = requireNotNull(SettingsRegistry.spec("voice_sensitivity"))
    private val voiceState = requireNotNull(SettingsRegistry.spec("voice_state"))

    private val everyMicrophoneGatedVoiceSpec =
        listOf(voiceEnabled, wakeWords, pipelines, audioSource, sensitivity, voiceState)

    @Test fun `every voice setting requires the microphone capability and lives in the Voice group`() {
        everyMicrophoneGatedVoiceSpec.forEach { spec ->
            assertEquals(spec.key, "Voice", spec.group)
            assertFalse("${spec.key} must be unavailable with no microphone", spec.availableWhen(Capabilities()))
            assertTrue(
                "${spec.key} must be available with a microphone",
                spec.availableWhen(Capabilities(hasMicrophone = true)),
            )
        }
    }

    @Test fun `voice_enabled is an advanced live-apply switch, off and unexposed by default`() {
        assertEquals(SettingType.BOOL, voiceEnabled.type)
        assertEquals("false", voiceEnabled.default)
        assertEquals(Tier.ADVANCED, voiceEnabled.tier)
        assertTrue(voiceEnabled.liveApply)
        assertFalse(voiceEnabled.haExposedByDefault)
        assertNotNull(voiceEnabled.ha)
        assertEquals("switch", voiceEnabled.ha!!.component)
        assertEquals("voice_assistant", voiceEnabled.ha!!.objectSuffix)
        assertFalse(voiceEnabled.ha!!.readOnly)
    }

    @Test fun `voice_wake_words defaults to okay_nabu and validates known ids up to two entries`() {
        assertEquals(SettingType.STRING, wakeWords.type)
        assertEquals("""["okay_nabu"]""", wakeWords.default)
        assertEquals(
            "the default itself must validate",
            wakeWords.default,
            (SettingValue.validate(wakeWords, wakeWords.default) as Validation.Ok).normalized,
        )

        val ok = SettingValue.validate(wakeWords, """["hey_jarvis","alexa"]""") as Validation.Ok
        assertEquals("""["hey_jarvis","alexa"]""", ok.normalized)

        val emptyOk = SettingValue.validate(wakeWords, "[]") as Validation.Ok
        assertEquals("[]", emptyOk.normalized)
    }

    @Test fun `voice_wake_words rejects malformed JSON`() {
        assertTrue(SettingValue.validate(wakeWords, "not json") is Validation.Bad)
        assertTrue(SettingValue.validate(wakeWords, "{\"okay_nabu\":true}") is Validation.Bad)
        assertTrue(SettingValue.validate(wakeWords, "[\"okay_nabu\"") is Validation.Bad)
    }

    @Test fun `voice_wake_words rejects an unknown model id`() {
        val bad = SettingValue.validate(wakeWords, """["okay_nabu","computer"]""") as Validation.Bad
        assertTrue(bad.reason.contains("computer"))
    }

    @Test fun `voice_wake_words rejects more than two entries`() {
        val bad = SettingValue.validate(
            wakeWords,
            """["okay_nabu","hey_jarvis","alexa"]""",
        ) as Validation.Bad
        assertTrue(bad.reason.contains("at most"))
    }

    @Test fun `voice_wake_words rejects a duplicate entry`() {
        assertTrue(SettingValue.validate(wakeWords, """["okay_nabu","okay_nabu"]""") is Validation.Bad)
    }

    @Test fun `voice_wake_words rejects a non-string entry`() {
        assertTrue(SettingValue.validate(wakeWords, "[1]") is Validation.Bad)
    }

    @Test fun `voice_pipelines defaults to an empty object and validates a known-key string map`() {
        assertEquals(SettingType.STRING, pipelines.type)
        assertEquals("{}", pipelines.default)
        assertEquals(
            "{}",
            (SettingValue.validate(pipelines, pipelines.default) as Validation.Ok).normalized,
        )

        val ok = SettingValue.validate(
            pipelines,
            """{"hey_jarvis":"assist_pipeline_1","okay_nabu":""}""",
        ) as Validation.Ok
        // Keys are re-serialized sorted, so the round trip is stable regardless of request order.
        assertEquals("""{"hey_jarvis":"assist_pipeline_1","okay_nabu":""}""", ok.normalized)
    }

    @Test fun `voice_pipelines rejects malformed JSON`() {
        assertTrue(SettingValue.validate(pipelines, "not json") is Validation.Bad)
        assertTrue(SettingValue.validate(pipelines, "[\"okay_nabu\"]") is Validation.Bad)
        assertTrue(SettingValue.validate(pipelines, "{\"okay_nabu\":\"x\"") is Validation.Bad)
    }

    @Test fun `voice_pipelines rejects an unknown wake word key`() {
        val bad = SettingValue.validate(pipelines, """{"computer":"assist_pipeline_1"}""") as Validation.Bad
        assertTrue(bad.reason.contains("computer"))
    }

    @Test fun `voice_pipelines rejects a non-string value`() {
        assertTrue(SettingValue.validate(pipelines, """{"okay_nabu":1}""") is Validation.Bad)
        assertTrue(SettingValue.validate(pipelines, """{"okay_nabu":null}""") is Validation.Bad)
        assertTrue(SettingValue.validate(pipelines, """{"okay_nabu":["x"]}""") is Validation.Bad)
    }

    @Test fun `voice_audio_source is an enum defaulting to voice_recognition`() {
        assertEquals(SettingType.ENUM, audioSource.type)
        assertEquals("voice_recognition", audioSource.default)
        assertEquals(listOf("voice_recognition", "mic", "voice_communication"), audioSource.options)
    }

    @Test fun `voice_sensitivity is an enum defaulting to normal and documents its meaning`() {
        assertEquals(SettingType.ENUM, sensitivity.type)
        assertEquals("normal", sensitivity.default)
        assertEquals(listOf("low", "normal", "high"), sensitivity.options)
        assertTrue(sensitivity.help.contains("offset"))
    }

    @Test fun `voice_state is a read-only unexposed-by-default sensor`() {
        assertTrue(voiceState.readOnly)
        assertFalse(voiceState.haExposedByDefault)
        assertNotNull(voiceState.ha)
        assertEquals("sensor", voiceState.ha!!.component)
        assertEquals("voice_state", voiceState.ha!!.objectSuffix)
        assertFalse(voiceState in SettingsRegistry.settable())
    }
}
