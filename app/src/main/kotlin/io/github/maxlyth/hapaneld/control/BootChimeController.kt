package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import io.github.maxlyth.hapaneld.Config

/**
 * Silences the firmware startup chime so scheduled panel reboots are quiet.
 *
 * The chime plays through the ringer/notification stream. On these panels the DnD subsystem blocks
 * AudioManager.setStreamVolume(STREAM_RING) for the app (confirmed fleet data: shell `media volume`
 * also fails for ring/notification). The only working path is a direct Settings.System write, which
 * bypasses the DnD check and persists across reboots. ha-paneld already holds WRITE_SETTINGS.
 *
 * Key used: `volume_ring_speaker` — confirmed on PX30/Android 8.1 + TPA10/Android 11 from the
 * fleet's androidtv.adb_command scripts. Also write the AOSP standard `volume_ring` as a
 * belt-and-suspenders cover for Android 14 (WF1589T) where behaviour is unconfirmed.
 *
 * Note: ring/notification share the same ringer-mode group on PX30, so `volume_ring_speaker` = 0
 * silences both. The HA Companion startup chime also uses this stream — the fleet already keeps
 * ring at 0 across all volume profiles for exactly this reason ("Companion-chime baseline").
 *
 * Apply is called at service start when the setting is on (re-establishes 0 in case a firmware
 * update or factory reset raised it), and on MQTT command.
 */
class BootChimeController(
    context: Context,
    private val config: Config,
) {
    private val cr = context.contentResolver
    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun isEnabled(): Boolean = config.silenceBootChime

    fun set(on: Boolean) {
        config.setSilenceBootChime(on)
        if (on) silence() else restore()
    }

    fun applyPersisted() {
        if (config.silenceBootChime) silence()
    }

    private fun silence() = runCatching {
        Settings.System.putInt(cr, RING_SPEAKER_KEY, 0)
        Settings.System.putInt(cr, RING_STANDARD_KEY, 0)
        Log.i(TAG, "boot chime silenced")
    }.onFailure { Log.w(TAG, "silence failed: ${it.message}") }

    private fun restore() = runCatching {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
        val level = (max * 2) / 3
        Settings.System.putInt(cr, RING_SPEAKER_KEY, level)
        Settings.System.putInt(cr, RING_STANDARD_KEY, level)
        Log.i(TAG, "boot chime restored → $level/$max")
    }.onFailure { Log.w(TAG, "restore failed: ${it.message}") }

    companion object {
        private const val TAG = "ha-paneld/bootchime"
        private const val RING_SPEAKER_KEY = "volume_ring_speaker"
        private const val RING_STANDARD_KEY = "volume_ring"
    }
}
