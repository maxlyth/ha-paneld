package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.platform.RootShell

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
 * belt-and-suspenders cover for Android 14 (WF1589T). Android 14 rejects the app-level write despite
 * WRITE_SETTINGS ("You cannot keep your settings in the secure settings"), so rooted panels fall back
 * to the same writes through [RootShell]. They persist for the next firmware boot, where the chime is
 * emitted before ha-paneld itself can start.
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
    private val root: RootShell = Su,
) {
    private val cr = context.contentResolver
    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun isEnabled(): Boolean = config.silenceBootChime

    fun set(on: Boolean) {
        config.setSilenceBootChime(on)
        if (on) silence() else restore()
    }

    fun applyPersisted() {
        if (config.silenceBootChime) {
            // Service.onCreate is the main thread; a su probe/write can take seconds on a hostile OEM.
            Thread({ silence() }, "ha-paneld-bootchime").start()
        }
    }

    private fun silence() {
        if (writeLevel(0)) Log.i(TAG, "boot chime silenced")
        else Log.w(TAG, "silence failed through app and root settings paths")
    }

    private fun restore() {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
        val level = (max * 2) / 3
        if (writeLevel(level)) Log.i(TAG, "boot chime restored → $level/$max")
        else Log.w(TAG, "restore failed through app and root settings paths")
    }

    private fun writeLevel(level: Int): Boolean {
        val direct = runCatching {
            Settings.System.putInt(cr, RING_SPEAKER_KEY, level) &&
                Settings.System.putInt(cr, RING_STANDARD_KEY, level)
        }.onFailure { Log.w(TAG, "app settings write failed; trying root: ${it.message}") }
            .getOrDefault(false)
        if (direct) return true
        return root.run(
            "settings put system $RING_SPEAKER_KEY $level; " +
                "settings put system $RING_STANDARD_KEY $level; " +
                // Android 14 keeps a live per-device volume separate from these persistent keys.
                // Root is required: shell/app callers are blocked by notification-policy access.
                "cmd media_session volume --stream $RING_STREAM --set $level",
        )
    }

    companion object {
        private const val TAG = "ha-paneld/bootchime"
        private const val RING_SPEAKER_KEY = "volume_ring_speaker"
        private const val RING_STANDARD_KEY = "volume_ring"
        private const val RING_STREAM = 2 // AudioManager.STREAM_RING; numeric for the shell command
    }
}
