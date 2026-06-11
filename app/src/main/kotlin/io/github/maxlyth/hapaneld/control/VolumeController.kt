package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * TTS/announce playback volume — the STREAM_MUSIC level, which is the stream [AudioPlayer] plays
 * on (USAGE_MEDIA). Exposed in HA as a 0–100% number so the panel's announce loudness is settable
 * and persists, independent of whatever the panel's media volume happened to be.
 */
class VolumeController(context: Context) {
    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val stream = AudioManager.STREAM_MUSIC

    fun getPercent(): Int {
        val max = am.getStreamMaxVolume(stream)
        if (max <= 0) return 0
        return (am.getStreamVolume(stream) * 100) / max
    }

    /** @param showUi when true, flash the system volume slider for visual feedback (navbar taps;
     *  HA-driven changes leave it false so they don't pop the dialog on every adjustment). */
    fun setPercent(pct: Int, showUi: Boolean = false) {
        val max = am.getStreamMaxVolume(stream)
        val v = (pct.coerceIn(0, 100) * max) / 100
        try {
            am.setStreamVolume(stream, v, if (showUi) AudioManager.FLAG_SHOW_UI else 0)
            Log.d(TAG, "volume -> $pct% (raw $v/$max)")
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot set volume (needs MODIFY_AUDIO_SETTINGS / not DND-restricted)", e)
        }
    }

    companion object {
        private const val TAG = "ha-paneld/volume"
    }
}
