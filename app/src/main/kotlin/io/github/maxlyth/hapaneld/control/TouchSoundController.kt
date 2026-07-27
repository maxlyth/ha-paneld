package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.maxlyth.hapaneld.persistence.AppState
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * UI touch-sound control, made consistent across the fleet from HA.
 *
 * Two parts, because neither alone is enough on these panels:
 *  1. The system `SOUND_EFFECTS_ENABLED` flag covers native UI where the OS honours it. We deliberately
 *     do not own `STREAM_SYSTEM`: Android 14 may alias it to ring, where boot-chime suppression must keep
 *     it muted. Touch sound and boot-chime policy must never fight over the same stream.
 *  2. ha-paneld's OWN click. Dashboard taps live in the WebView, which never plays Android touch sounds,
 *     and the IME has its own keypress-sound pref we don't control — so neither covers a wall-panel
 *     dashboard. We subscribe to [PanelTouchObserver]'s shared 1 px `FLAG_WATCH_OUTSIDE_TOUCH` overlay,
 *     which receives `ACTION_OUTSIDE` without consuming it, and play an app-owned generated click on the
 *     media stream. This avoids firmware sample paths and remains independent of ring/system muting
 *     while respecting the user's media volume.
 *
 * App-direct: `SOUND_EFFECTS_ENABLED` needs `WRITE_SETTINGS`; the overlay needs `SYSTEM_ALERT_WINDOW`
 * (already held for the navbar). No root, no daemon.
 */
class TouchSoundController(context: Context, clickGain: Float) {
    private val ctx = context.applicationContext
    private val clickGain = clickGain.coerceIn(0.05f, 1f)
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val touchObserver = PanelTouchObserver.shared(ctx)
    private val statePolicy = TouchSoundStatePolicy(
        AndroidTouchSoundStateStore(AppState.preferences(ctx, "controller-state", STATE_PREFS)),
        AndroidTouchSoundHardware(ctx),
    )

    private var pool: SoundPool? = null
    private var clickId = 0
    @Volatile private var clickReady = false
    private var touchSubscription: PanelTouchObserver.Subscription? = null

    fun isEnabled(): Boolean = statePolicy.isEnabled(
        Settings.System.getInt(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1,
    )

    @Synchronized
    fun set(on: Boolean): Boolean {
        return try {
            if (on) {
                if (!statePolicy.enable()) {
                    Log.w(TAG, "touch sound enable refused: prior state could not be captured durably")
                    return false
                }
                am.loadSoundEffects()
                enableOverlay()
            } else {
                disableOverlay()
                if (!statePolicy.disable()) {
                    Log.w(TAG, "touch sound exact restore failed; retained for retry")
                    return false
                }
            }
            Log.i(TAG, "touch sound -> ${if (on) "on" else "off"}")
            true
        } catch (error: Exception) {
            Log.w(TAG, "touch sound set failed: ${error.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun ensurePool() {
        if (pool != null) return
        val sp = SoundPool(2, AudioManager.STREAM_MUSIC, 0)
        val nativeSample = listOf(
            "/system/media/audio/ui/KeypressStandard.ogg",
            "/product/media/audio/ui/KeypressStandard.ogg",
            "/system/media/audio/ui/Effect_Tick.ogg",
            "/product/media/audio/ui/Effect_Tick.ogg",
        ).map(::File).firstOrNull(File::canRead)
        val sample = nativeSample ?: File(ctx.cacheDir, CLICK_FILE).also { owned ->
            val bytes = touchClickWav()
            if (!owned.isFile || owned.length() != bytes.size.toLong()) {
                runCatching { owned.writeBytes(bytes) }
                    .onFailure { Log.w(TAG, "could not create owned touch sample: ${it.message}") }
            }
        }
        sp.setOnLoadCompleteListener { _, sampleId, status ->
            clickId = sampleId
            clickReady = status == 0
            if (clickReady) Log.i(TAG, "touch sample ready: ${sample.name} gain=$clickGain")
            else Log.w(TAG, "touch sample load failed: ${sample.name} status=$status")
        }
        clickId = if (sample.canRead()) sp.load(sample.absolutePath, 1) else 0
        if (clickId == 0) Log.w(TAG, "owned touch sample could not be queued; overlay click will be silent")
        pool = sp
    }

    private fun enableOverlay() = main.post {
        if (touchSubscription != null) return@post
        ensurePool()
        touchSubscription = touchObserver.subscribe {
            Log.d(TAG, "tap -> click")
            pool?.takeIf { clickId != 0 && clickReady }
                ?.play(clickId, clickGain, clickGain, 1, 0, 1f)
        }
        if (touchSubscription == null) Log.w(TAG, "touch-click overlay unavailable")
    }

    private fun disableOverlay() = main.post {
        touchSubscription?.close()
        touchSubscription = null
    }

    companion object {
        private const val TAG = "ha-paneld/touchsound"
        private const val STATE_PREFS = "ha-paneld-controller-state"
        private const val CLICK_FILE = "ha-paneld-touch-click-v1.wav"
    }
}

/** Small deterministic PCM click owned by ha-paneld; no vendor audio-file dependency. */
internal fun touchClickWav(sampleRate: Int = 16_000, durationMs: Int = 58): ByteArray {
    val frames = (sampleRate * durationMs / 1_000).coerceAtLeast(1)
    val dataBytes = frames * 2
    val out = ByteArray(44 + dataBytes)
    fun ascii(offset: Int, value: String) = value.forEachIndexed { index, char -> out[offset + index] = char.code.toByte() }
    fun le16(offset: Int, value: Int) {
        out[offset] = value.toByte(); out[offset + 1] = (value ushr 8).toByte()
    }
    fun le32(offset: Int, value: Int) {
        out[offset] = value.toByte(); out[offset + 1] = (value ushr 8).toByte()
        out[offset + 2] = (value ushr 16).toByte(); out[offset + 3] = (value ushr 24).toByte()
    }
    ascii(0, "RIFF"); le32(4, out.size - 8); ascii(8, "WAVE"); ascii(12, "fmt ")
    le32(16, 16); le16(20, 1); le16(22, 1); le32(24, sampleRate)
    le32(28, sampleRate * 2); le16(32, 2); le16(34, 16); ascii(36, "data"); le32(40, dataBytes)
    repeat(frames) { index ->
        val elapsed = index.toDouble() / sampleRate
        val remaining = (frames - index).toDouble() / frames
        val attack = (index / 10.0).coerceAtMost(1.0)
        val envelope = remaining * remaining * attack
        val body = sin(2.0 * PI * 215.0 * elapsed) + 0.28 * sin(2.0 * PI * 108.0 * elapsed)
        val sample = (body * envelope * 8_200.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        le16(44 + index * 2, sample)
    }
    return out
}

internal data class TouchSoundState(
    val effectsSetting: Int?,
)

internal interface TouchSoundStateStore {
    fun active(): Boolean?
    fun prior(): TouchSoundState?
    fun saveEnabled(prior: TouchSoundState): Boolean
    fun saveDisabledAndClearPrior(): Boolean
    fun retireLegacyStreamState(): Boolean
}

internal interface TouchSoundHardware {
    fun capture(): TouchSoundState?
    fun enable(): Boolean
    fun restore(state: TouchSoundState): Boolean
    fun disableConservatively(): Boolean
}

/** State machine separated from Android UI/audio objects so ordering and legacy behavior stay testable. */
internal class TouchSoundStatePolicy(
    private val store: TouchSoundStateStore,
    private val hardware: TouchSoundHardware,
) {
    init {
        // Older builds captured STREAM_SYSTEM even though restoring it could undo boot-chime muting.
        // Retire that orphaned preference once; touch sound now owns only SOUND_EFFECTS_ENABLED.
        store.retireLegacyStreamState()
    }

    fun isEnabled(platformFallback: Boolean): Boolean = store.active() ?: platformFallback

    fun enable(): Boolean {
        if (store.active() != true || store.prior() == null) {
            val prior = hardware.capture() ?: return false
            if (!store.saveEnabled(prior)) return false
        }
        return hardware.enable()
    }

    fun disable(): Boolean {
        val prior = store.prior()
        val restored = if (prior != null) hardware.restore(prior) else hardware.disableConservatively()
        if (!restored) return false
        return store.saveDisabledAndClearPrior()
    }
}

private class AndroidTouchSoundStateStore(
    private val preferences: SharedPreferences,
) : TouchSoundStateStore {
    override fun active(): Boolean? =
        if (preferences.contains(KEY_ACTIVE)) preferences.getBoolean(KEY_ACTIVE, false) else null

    override fun prior(): TouchSoundState? {
        if (!preferences.getBoolean(KEY_PRIOR_PRESENT, false)) return null
        if (!preferences.contains(KEY_EFFECTS) && !preferences.getBoolean(KEY_EFFECTS_NULL, false)) return null
        return TouchSoundState(
            effectsSetting = if (preferences.getBoolean(KEY_EFFECTS_NULL, false)) null
            else preferences.getInt(KEY_EFFECTS, 1),
        )
    }

    override fun saveEnabled(prior: TouchSoundState): Boolean {
        val editor = preferences.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putBoolean(KEY_PRIOR_PRESENT, true)
        if (prior.effectsSetting == null) {
            editor.remove(KEY_EFFECTS).putBoolean(KEY_EFFECTS_NULL, true)
        } else {
            editor.putInt(KEY_EFFECTS, prior.effectsSetting).remove(KEY_EFFECTS_NULL)
        }
        return editor.commit()
    }

    override fun saveDisabledAndClearPrior(): Boolean = preferences.edit()
        .putBoolean(KEY_ACTIVE, false)
        .remove(KEY_PRIOR_PRESENT)
        .remove(KEY_EFFECTS).remove(KEY_EFFECTS_NULL).remove(KEY_STREAM)
        .commit()

    override fun retireLegacyStreamState(): Boolean =
        !preferences.contains(KEY_STREAM) || preferences.edit().remove(KEY_STREAM).commit()

    companion object {
        private const val KEY_ACTIVE = "touch_sound.active"
        private const val KEY_PRIOR_PRESENT = "touch_sound.prior.present"
        private const val KEY_EFFECTS = "touch_sound.prior.effects"
        private const val KEY_EFFECTS_NULL = "touch_sound.prior.effects_null"
        private const val KEY_STREAM = "touch_sound.prior.system_stream"
    }
}

private class AndroidTouchSoundHardware(
    private val context: Context,
) : TouchSoundHardware {
    private val cr = context.contentResolver

    override fun capture(): TouchSoundState? = runCatching {
        TouchSoundState(
            Settings.System.getString(cr, Settings.System.SOUND_EFFECTS_ENABLED)?.toIntOrNull(),
        )
    }.getOrNull()

    override fun enable(): Boolean = runCatching {
        Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 1)
    }.getOrDefault(false)

    override fun restore(state: TouchSoundState): Boolean = runCatching {
        val setting = if (state.effectsSetting == null) {
            cr.delete(Settings.System.CONTENT_URI, "name=?", arrayOf(Settings.System.SOUND_EFFECTS_ENABLED)) >= 0
        } else {
            Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, state.effectsSetting)
        }
        setting
    }.getOrDefault(false)

    override fun disableConservatively(): Boolean = runCatching {
        Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 0)
    }.getOrDefault(false)
}
