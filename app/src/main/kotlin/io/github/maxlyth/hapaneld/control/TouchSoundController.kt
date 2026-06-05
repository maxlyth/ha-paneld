package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.util.Log

/**
 * UI touch-sound control, made consistent across the fleet from HA.
 *
 * The fleet inconsistency isn't the `SOUND_EFFECTS_ENABLED` flag (it's on everywhere) — it's the
 * **system-stream volume**, which is 0 on some panels (silent) and non-zero on others (clicks). So:
 *  - **OFF** clears `SOUND_EFFECTS_ENABLED` — a clean, volume-independent disable.
 *  - **ON** sets the flag *and* raises `STREAM_SYSTEM` to a fixed audible level, then preloads effects.
 *
 * App-direct: `SOUND_EFFECTS_ENABLED` needs `WRITE_SETTINGS` (already held for brightness); the stream
 * volume is a plain `AudioManager` call (already used for TTS volume). No root, no daemon.
 */
class TouchSoundController(context: Context) {
    private val ctx = context.applicationContext
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun isEnabled(): Boolean =
        Settings.System.getInt(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1

    fun set(on: Boolean) = runCatching {
        Settings.System.putInt(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, if (on) 1 else 0)
        if (on) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_SYSTEM)
            am.setStreamVolume(AudioManager.STREAM_SYSTEM, (max * 0.45f).toInt().coerceAtLeast(1), 0)
            am.loadSoundEffects()
        }
        Log.i(TAG, "touch sound -> ${if (on) "on" else "off"}")
    }.onFailure { Log.w(TAG, "touch sound set failed: ${it.message}") }

    companion object {
        private const val TAG = "ha-paneld/touchsound"
    }
}
