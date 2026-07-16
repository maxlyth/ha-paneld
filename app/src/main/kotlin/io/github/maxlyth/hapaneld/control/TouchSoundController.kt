package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.io.File

/**
 * UI touch-sound control, made consistent across the fleet from HA.
 *
 * Two parts, because neither alone is enough on these panels:
 *  1. The system `SOUND_EFFECTS_ENABLED` flag + an audible `STREAM_SYSTEM` volume — covers native-UI and
 *     IME key clicks where the OS / keyboard honour it. The fleet inconsistency was the system-stream
 *     volume (0 on some panels), so ON raises it while active; OFF restores the exact prior flag + volume.
 *  2. ha-paneld's OWN click. Dashboard taps live in the WebView, which never plays Android touch sounds,
 *     and the IME has its own keypress-sound pref we don't control — so neither covers a wall-panel
 *     dashboard. We add a 1 px `FLAG_WATCH_OUTSIDE_TOUCH` overlay that receives `ACTION_OUTSIDE` for
 *     every tap anywhere on screen WITHOUT consuming it (the tap still reaches the dashboard), and play
 *     a short tick via SoundPool. This is the only firmware/IME-independent way to make the panel click.
 *
 * App-direct: `SOUND_EFFECTS_ENABLED` needs `WRITE_SETTINGS`; the overlay needs `SYSTEM_ALERT_WINDOW`
 * (already held for the navbar). No root, no daemon.
 */
class TouchSoundController(context: Context) {
    private val ctx = context.applicationContext
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val statePolicy = TouchSoundStatePolicy(
        AndroidTouchSoundStateStore(ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)),
        AndroidTouchSoundHardware(ctx, am),
    )

    private var pool: SoundPool? = null
    private var clickId = 0
    private var watcher: View? = null

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

    private fun ensurePool() {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
        // Prefer the standard key-press click; fall back to the generic tick. (Present on these panels.)
        val sample = listOf(
            "/system/media/audio/ui/KeypressStandard.ogg",
            "/system/media/audio/ui/Effect_Tick.ogg",
        ).firstOrNull { File(it).canRead() }
        clickId = if (sample != null) sp.load(sample, 1) else 0
        if (clickId == 0) Log.w(TAG, "no UI click sample found; overlay click will be silent")
        pool = sp
    }

    @Suppress("ClickableViewAccessibility")
    private fun enableOverlay() = main.post {
        if (watcher != null) return@post
        ensurePool()
        val v = View(ctx)
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                Log.d(TAG, "tap -> click")
                pool?.takeIf { clickId != 0 }?.play(clickId, 1f, 1f, 1, 0, 1f)
            }
            false // never consume — the tap still reaches the dashboard
        }
        val lp = WindowManager.LayoutParams(
            1, 1, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(v, lp); watcher = v }
            .onFailure { Log.w(TAG, "touch-click overlay addView failed: ${it.message}") }
    }

    private fun disableOverlay() = main.post {
        watcher?.let { runCatching { wm.removeView(it) } }
        watcher = null
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    companion object {
        private const val TAG = "ha-paneld/touchsound"
        private const val STATE_PREFS = "ha-paneld-controller-state"
    }
}

internal data class TouchSoundState(
    val effectsSetting: Int?,
    val systemStream: Int,
)

internal interface TouchSoundStateStore {
    fun active(): Boolean?
    fun prior(): TouchSoundState?
    fun saveEnabled(prior: TouchSoundState): Boolean
    fun saveDisabledAndClearPrior(): Boolean
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
        if ((!preferences.contains(KEY_EFFECTS) && !preferences.getBoolean(KEY_EFFECTS_NULL, false)) ||
            !preferences.contains(KEY_STREAM)
        ) return null
        return TouchSoundState(
            effectsSetting = if (preferences.getBoolean(KEY_EFFECTS_NULL, false)) null
            else preferences.getInt(KEY_EFFECTS, 1),
            systemStream = preferences.getInt(KEY_STREAM, 0),
        )
    }

    override fun saveEnabled(prior: TouchSoundState): Boolean {
        val editor = preferences.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putBoolean(KEY_PRIOR_PRESENT, true)
            .putInt(KEY_STREAM, prior.systemStream)
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
    private val audio: AudioManager,
) : TouchSoundHardware {
    private val cr = context.contentResolver

    override fun capture(): TouchSoundState? = runCatching {
        TouchSoundState(
            Settings.System.getString(cr, Settings.System.SOUND_EFFECTS_ENABLED)?.toIntOrNull(),
            audio.getStreamVolume(AudioManager.STREAM_SYSTEM),
        )
    }.getOrNull()

    override fun enable(): Boolean = runCatching {
        if (!Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 1)) return@runCatching false
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_SYSTEM)
        audio.setStreamVolume(AudioManager.STREAM_SYSTEM, (max * 0.45f).toInt().coerceAtLeast(1), 0)
        true
    }.getOrDefault(false)

    override fun restore(state: TouchSoundState): Boolean = runCatching {
        val setting = if (state.effectsSetting == null) {
            cr.delete(Settings.System.CONTENT_URI, "name=?", arrayOf(Settings.System.SOUND_EFFECTS_ENABLED)) >= 0
        } else {
            Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, state.effectsSetting)
        }
        audio.setStreamVolume(AudioManager.STREAM_SYSTEM, state.systemStream, 0)
        setting
    }.getOrDefault(false)

    override fun disableConservatively(): Boolean = runCatching {
        Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 0)
    }.getOrDefault(false)
}
