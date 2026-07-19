package io.github.maxlyth.hapaneld.control

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchClickSampleTest {
    @Test fun `owned click is a non-silent mono PCM wav`() {
        val wav = touchClickWav()

        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("data", wav.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertTrue(wav.size > 44)
        assertTrue(wav.copyOfRange(44, wav.size).any { it.toInt() != 0 })
    }

    @Test fun `overlay click is independent of ring and system stream policy`() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/control/TouchSoundController.kt").readText()

        assertTrue(source.contains("SoundPool(2, AudioManager.STREAM_MUSIC, 0)"))
        assertTrue(source.contains("clickId != 0 && clickReady"))
        assertTrue(source.contains("/system/media/audio/ui/KeypressStandard.ogg"))
        assertTrue(source.contains("/product/media/audio/ui/KeypressStandard.ogg"))
        assertTrue(source.contains("play(clickId, clickGain, clickGain"))
        assertFalse(source.contains("setStreamVolume(AudioManager.STREAM_SYSTEM"))
        assertFalse(source.contains("getStreamVolume(AudioManager.STREAM_SYSTEM"))
        assertTrue(source.contains("preferences.edit().remove(KEY_STREAM).commit()"))
    }
}
