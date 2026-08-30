package io.github.maxlyth.hapaneld.assist

import io.github.maxlyth.hapaneld.assist.wakeword.LoadedWakeWordModel
import io.github.maxlyth.hapaneld.assist.wakeword.MicroWakeWordModelConfig
import io.github.maxlyth.hapaneld.assist.wakeword.WakeWordScorer
import io.github.maxlyth.hapaneld.audio.MicrophoneSource
import io.github.maxlyth.hapaneld.audio.PcmFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The factory decides whether this panel can listen for a wake word at all. Getting that wrong in
 * either direction is costly: a null where a listener could run silently downgrades the panel to
 * press-to-speak, and a listener built over no models would hold the microphone to score nothing.
 */
class MicroWakeWordEngineFactoryTest {

    private class FakeScorer : WakeWordScorer {
        var closed = false
        var scored = 0
        var resets = 0
        override fun score(samples: ShortArray, count: Int): Int {
            scored++
            return WakeWordScorer.NO_INFERENCE
        }
        override fun reset() { resets++ }
        override fun close() { closed = true }
    }

    private fun model(id: String, scorer: WakeWordScorer) = LoadedWakeWordModel(
        MicroWakeWordModelConfig(
            id = id,
            wakeWord = id.replace('_', ' '),
            author = "test",
            website = null,
            modelFile = "$id.tflite",
            trainedLanguages = listOf("en"),
            version = 2,
            probabilityCutoff = 0.5f,
            featureStepSizeMs = 10,
            slidingWindowSize = 4,
            tensorArenaSize = 0,
        ),
        scorer,
    )

    private fun frame() = PcmFrame(
        ShortArray(MicrophoneSource.SAMPLES_PER_FRAME),
        MicrophoneSource.SAMPLE_RATE_HZ,
        1L,
    )

    @Test
    fun `a panel whose engine cannot load gets no listener`() {
        val factory = MicroWakeWordEngineFactory { _, _ -> null }
        assertNull("a null load must leave the panel press-to-speak", factory.create(listOf("okay_nabu")) {})
    }

    @Test
    fun `a panel with no matching model gets no listener rather than an idle one`() {
        val factory = MicroWakeWordEngineFactory { _, _ -> emptyList() }
        assertNull("an empty load must not build a listener over nothing", factory.create(listOf("okay_nabu")) {})
    }

    @Test
    fun `the requested ids and the active bound both reach the loader`() {
        var seenIds: List<String>? = null
        var seenMax = -1
        val factory = MicroWakeWordEngineFactory { ids, max -> seenIds = ids; seenMax = max; null }
        factory.create(listOf("okay_nabu", "hey_jarvis")) {}
        assertEquals(listOf("okay_nabu", "hey_jarvis"), seenIds)
        assertEquals(VoiceSettings.MAX_ACTIVE_WAKE_WORDS, seenMax)
    }

    @Test
    fun `a loaded model yields a listener that consumes audio and closes its models`() {
        val scorer = FakeScorer()
        val factory = MicroWakeWordEngineFactory { _, _ -> listOf(model("okay_nabu", scorer)) }
        val engine = factory.create(listOf("okay_nabu")) {}
        assertNotNull(engine)
        engine!!.onFrame(frame())
        assertTrue("the listener must actually feed its model", scorer.scored > 0)
        engine.onDropped(3)
        assertTrue("a gap in the audio must reset the model's stream context", scorer.resets > 0)
        engine.close()
        assertTrue("closing the listener must release its models", scorer.closed)
    }
}
