package io.github.maxlyth.hapaneld.control

import android.content.Context
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
 *     volume (0 on some panels), so ON raises it; OFF clears the flag (a clean, volume-independent off).
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

    private var pool: SoundPool? = null
    private var clickId = 0
    private var watcher: View? = null

    fun isEnabled(): Boolean =
        Settings.System.getInt(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1

    fun set(on: Boolean) = runCatching {
        Settings.System.putInt(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, if (on) 1 else 0)
        if (on) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_SYSTEM)
            am.setStreamVolume(AudioManager.STREAM_SYSTEM, (max * 0.45f).toInt().coerceAtLeast(1), 0)
            am.loadSoundEffects()
            enableOverlay()
        } else {
            disableOverlay()
        }
        Log.i(TAG, "touch sound -> ${if (on) "on" else "off"}")
    }.onFailure { Log.w(TAG, "touch sound set failed: ${it.message}") }

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
    }
}
