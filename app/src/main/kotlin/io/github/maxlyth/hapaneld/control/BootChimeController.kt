package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.persistence.AppState
import io.github.maxlyth.hapaneld.platform.RootShell

/**
 * Silences the firmware startup chime while preserving the exact prior ring/notification state.
 *
 * Persistent Settings values are the reliable pre-boot control on the supported panels. Android 14
 * also keeps live stream values, so both are snapshotted before the first silence and restored on
 * disable. A legacy installation without a snapshot is left at its current volume when disabled:
 * inventing a "reasonable" louder volume is less safe than preserving silence.
 */
class BootChimeController internal constructor(
    private val configured: () -> Boolean,
    private val setConfigured: (Boolean) -> Unit,
    private val stateStore: BootChimeStateStore,
    private val hardware: BootChimeHardware,
) {
    constructor(
        context: Context,
        config: Config,
        root: RootShell = Su,
    ) : this(
        configured = { config.silenceBootChime },
        setConfigured = config::setSilenceBootChime,
        stateStore = AndroidBootChimeStateStore(
            AppState.preferences(context, "controller-state", STATE_PREFS),
        ),
        hardware = AndroidBootChimeHardware(context.applicationContext, root),
    )

    fun isEnabled(): Boolean = configured()

    @Synchronized
    fun set(on: Boolean): Boolean {
        if (on) {
            if (!ensureSnapshot()) return false
            setConfigured(true)
            return if (hardware.silence()) {
                Log.i(TAG, "boot chime silenced")
                true
            } else {
                Log.w(TAG, "silence failed through app and root settings paths")
                false
            }
        }

        val prior = stateStore.load()
        if (prior == null) {
            // Old versions did not record the prior volume. Do not resurrect sound with a guessed level.
            setConfigured(false)
            Log.i(TAG, "boot chime control disabled; no legacy volume snapshot to restore")
            return true
        }
        if (!hardware.restore(prior)) {
            Log.w(TAG, "exact boot-chime restore failed; retaining snapshot for retry")
            return false
        }
        if (!stateStore.clear()) {
            Log.w(TAG, "boot-chime state restored but snapshot cleanup failed; retaining enabled state for retry")
            return false
        }
        setConfigured(false)
        Log.i(TAG, "boot chime state restored exactly")
        return true
    }

    fun applyPersisted() {
        if (configured()) {
            // Service.onCreate is the main thread; reads/root writes can take seconds on a hostile OEM.
            Thread(::applyPersistedOffMain, "ha-paneld-bootchime").start()
        }
    }

    @Synchronized
    private fun applyPersistedOffMain() {
        // The setting may have been turned off after the worker was submitted. Serializing with [set]
        // guarantees an in-flight silence happens before its exact restore, never after it.
        if (!configured() || !ensureSnapshot()) return
        if (hardware.silence()) Log.i(TAG, "boot chime silenced")
        else Log.w(TAG, "silence failed through app and root settings paths")
    }

    private fun ensureSnapshot(): Boolean {
        if (stateStore.load() != null) return true
        val captured = hardware.capture()
        if (captured == null) {
            Log.w(TAG, "could not capture prior boot-chime state; refusing to silence")
            return false
        }
        if (!stateStore.save(captured)) {
            Log.w(TAG, "could not persist prior boot-chime state; refusing to silence")
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "ha-paneld/bootchime"
        private const val STATE_PREFS = "ha-paneld-controller-state"
    }
}

internal data class BootChimeState(
    val ringSpeakerSetting: Int?,
    val ringSetting: Int?,
    val notificationSetting: Int?,
    val ringStream: Int,
    val notificationStream: Int,
)

internal interface BootChimeStateStore {
    fun load(): BootChimeState?
    fun save(state: BootChimeState): Boolean
    fun clear(): Boolean
}

internal interface BootChimeHardware {
    fun capture(): BootChimeState?
    fun silence(): Boolean
    fun restore(state: BootChimeState): Boolean
}

private class AndroidBootChimeStateStore(
    private val preferences: SharedPreferences,
) : BootChimeStateStore {
    override fun load(): BootChimeState? {
        if (!preferences.getBoolean(KEY_PRESENT, false)) return null
        if (!preferences.hasNullableInt(KEY_RING_SPEAKER) ||
            !preferences.hasNullableInt(KEY_RING) ||
            !preferences.hasNullableInt(KEY_NOTIFICATION) ||
            !preferences.contains(KEY_RING_STREAM) ||
            !preferences.contains(KEY_NOTIFICATION_STREAM)
        ) return null
        return BootChimeState(
            preferences.nullableInt(KEY_RING_SPEAKER),
            preferences.nullableInt(KEY_RING),
            preferences.nullableInt(KEY_NOTIFICATION),
            preferences.getInt(KEY_RING_STREAM, 0),
            preferences.getInt(KEY_NOTIFICATION_STREAM, 0),
        )
    }

    override fun save(state: BootChimeState): Boolean = preferences.edit()
        .putNullableInt(KEY_RING_SPEAKER, state.ringSpeakerSetting)
        .putNullableInt(KEY_RING, state.ringSetting)
        .putNullableInt(KEY_NOTIFICATION, state.notificationSetting)
        .putInt(KEY_RING_STREAM, state.ringStream)
        .putInt(KEY_NOTIFICATION_STREAM, state.notificationStream)
        .putBoolean(KEY_PRESENT, true)
        .commit()

    override fun clear(): Boolean = preferences.edit()
        .remove(KEY_PRESENT)
        .remove(KEY_RING_SPEAKER).remove("$KEY_RING_SPEAKER.null")
        .remove(KEY_RING).remove("$KEY_RING.null")
        .remove(KEY_NOTIFICATION).remove("$KEY_NOTIFICATION.null")
        .remove(KEY_RING_STREAM).remove(KEY_NOTIFICATION_STREAM)
        .commit()

    private fun SharedPreferences.nullableInt(key: String): Int? =
        if (getBoolean("$key.null", false)) null else getInt(key, 0)

    private fun SharedPreferences.hasNullableInt(key: String): Boolean =
        contains(key) || getBoolean("$key.null", false)

    private fun SharedPreferences.Editor.putNullableInt(key: String, value: Int?): SharedPreferences.Editor =
        if (value == null) remove(key).putBoolean("$key.null", true)
        else putInt(key, value).remove("$key.null")

    companion object {
        private const val KEY_PRESENT = "boot_chime.prior.present"
        private const val KEY_RING_SPEAKER = "boot_chime.prior.ring_speaker"
        private const val KEY_RING = "boot_chime.prior.ring"
        private const val KEY_NOTIFICATION = "boot_chime.prior.notification"
        private const val KEY_RING_STREAM = "boot_chime.prior.ring_stream"
        private const val KEY_NOTIFICATION_STREAM = "boot_chime.prior.notification_stream"
    }
}

private class AndroidBootChimeHardware(
    context: Context,
    private val root: RootShell,
) : BootChimeHardware {
    private val cr = context.contentResolver
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun capture(): BootChimeState? = runCatching {
        BootChimeState(
            Settings.System.getString(cr, RING_SPEAKER_KEY)?.toIntOrNull(),
            Settings.System.getString(cr, RING_STANDARD_KEY)?.toIntOrNull(),
            Settings.System.getString(cr, NOTIFICATION_KEY)?.toIntOrNull(),
            am.getStreamVolume(AudioManager.STREAM_RING),
            am.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
        )
    }.onFailure { Log.w(TAG, "boot-chime state capture failed: ${it.message}") }.getOrNull()

    override fun silence(): Boolean = writeLevel(0)

    override fun restore(state: BootChimeState): Boolean {
        val direct = runCatching {
            var ok = true
            ok = writeSetting(RING_SPEAKER_KEY, state.ringSpeakerSetting) && ok
            ok = writeSetting(RING_STANDARD_KEY, state.ringSetting) && ok
            ok = writeSetting(NOTIFICATION_KEY, state.notificationSetting) && ok
            am.setStreamVolume(AudioManager.STREAM_RING, state.ringStream, 0)
            am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, state.notificationStream, 0)
            ok
        }.onFailure { Log.w(TAG, "app exact restore failed; trying root: ${it.message}") }
            .getOrDefault(false)
        if (direct) return true
        return root.run(restoreShellCommand(state))
    }

    private fun writeLevel(level: Int): Boolean {
        val direct = runCatching {
            var ok = true
            ok = Settings.System.putInt(cr, RING_SPEAKER_KEY, level) && ok
            ok = Settings.System.putInt(cr, RING_STANDARD_KEY, level) && ok
            ok = Settings.System.putInt(cr, NOTIFICATION_KEY, level) && ok
            ok
        }.onFailure { Log.w(TAG, "app settings write failed; trying root: ${it.message}") }
            .getOrDefault(false)
        if (direct) return true
        return root.run(silenceShellCommand(level))
    }

    private fun writeSetting(key: String, value: Int?): Boolean =
        if (value == null) cr.delete(Settings.System.CONTENT_URI, "name=?", arrayOf(key)) >= 0
        else Settings.System.putInt(cr, key, value)

    companion object {
        private const val TAG = "ha-paneld/bootchime"
    }
}

internal fun restoreShellCommand(state: BootChimeState): String =
    bootChimeTransitionCommand(
        settingShellCommand(RING_SPEAKER_KEY, state.ringSpeakerSetting),
        settingShellCommand(RING_STANDARD_KEY, state.ringSetting),
        settingShellCommand(NOTIFICATION_KEY, state.notificationSetting),
        "cmd media_session volume --stream $RING_STREAM --set ${state.ringStream}",
        "cmd media_session volume --stream $NOTIFICATION_STREAM --set ${state.notificationStream}",
    )

internal fun silenceShellCommand(level: Int): String =
    bootChimeTransitionCommand(
        "settings put system $RING_SPEAKER_KEY $level",
        "settings put system $RING_STANDARD_KEY $level",
        "settings put system $NOTIFICATION_KEY $level",
        "cmd media_session volume --stream $RING_STREAM --set $level",
        "cmd media_session volume --stream $NOTIFICATION_STREAM --set $level",
    )

private fun settingShellCommand(key: String, value: Int?): String =
    if (value == null) "settings delete system $key" else "settings put system $key $value"

/**
 * RootShell returns the status of the complete expression. Fail-fast chaining prevents a successful
 * final stream write from disguising an earlier failed settings write and causing snapshot cleanup.
 */
private fun bootChimeTransitionCommand(vararg commands: String): String = commands.joinToString(" && ")

private const val RING_SPEAKER_KEY = "volume_ring_speaker"
private const val RING_STANDARD_KEY = "volume_ring"
private const val NOTIFICATION_KEY = "volume_notification"
private const val RING_STREAM = 2
private const val NOTIFICATION_STREAM = 5
