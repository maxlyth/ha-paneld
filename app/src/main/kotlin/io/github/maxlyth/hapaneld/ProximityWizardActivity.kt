package io.github.maxlyth.hapaneld

import android.graphics.Color
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** The physical calibration journey stays on the panel, independently of its dashboard renderer. */
class ProximityWizardActivity : AppCompatActivity() {
    private val maintenanceFence = GuardDbActivityMaintenanceFence()
    private val handler = Handler(Looper.getMainLooper())
    private var sessionId: String? = null
    private var visible = false
    private var stage = ""
    private var lastPresentation = ""
    private lateinit var instruction: TextView
    private lateinit var detail: TextView
    private lateinit var mode: TextView
    private lateinit var progress: TextView
    private lateinit var indicator: ProgressBar
    private lateinit var primary: Button
    private lateinit var cancel: Button
    private lateinit var pictogram: ProximityPictogramView
    private lateinit var cadence: TextView
    private lateinit var cadenceLabel: TextView
    private lateinit var visualRow: LinearLayout

    private val poll = object : Runnable {
        override fun run() {
            if (!visible) return
            refresh()
            if (visible) handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (maintenanceFence.stop(this)) return
        NativeLocale.apply(Config(this).uiLanguage)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sessionId = savedInstanceState?.getString(SESSION)
        buildUi()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val palette = statusPalette(StatusSurface.darkFor(this, Config(this)))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.parseColor(palette.background))
        }
        fun label(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(palette.body))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        instruction = label(34f, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        detail = label(24f)
        mode = label(24f)
        progress = label(24f, true)
        indicator = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        content.addView(instruction)
        pictogram = ProximityPictogramView(this)
        cadence = label(56f, true)
        cadenceLabel = label(24f)
        val cadenceColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(cadence)
            addView(cadenceLabel)
        }
        visualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(pictogram, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f))
            addView(cadenceColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        val visualHeight = (resources.configuration.screenHeightDp * 0.3f).toInt().coerceIn(104, 176)
        content.addView(visualRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(visualHeight)))
        content.addView(detail)
        content.addView(mode)
        content.addView(progress)
        content.addView(indicator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button() = Button(this).apply {
            isAllCaps = false
            textSize = 24f
            minimumHeight = dp(56)
        }
        cancel = button().apply {
            setText(R.string.proximity_wizard_cancel)
            setOnClickListener { cancelAndFinish() }
        }
        primary = button().apply {
            setOnClickListener {
                when (stage) {
                    "intro" -> perform("begin")
                    "failed", "timed_out" -> perform("retry")
                    "review" -> perform("save")
                    else -> finish()
                }
            }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(primary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        if (maintenanceFence.stop(this) || !::instruction.isInitialized) return
        visible = true
        pictogram.setPresenting(true)
        KioskAdminUi.setVisible(this, true)
        handler.post(poll)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A later explicit launch can replace a completed screen, never an in-progress journey.
        if (proximityWizardMayRebind(stage)) {
            sessionId = null
            lastPresentation = ""
            refresh()
        }
    }

    override fun onStop() {
        visible = false
        if (::pictogram.isInitialized) pictogram.setPresenting(false)
        handler.removeCallbacks(poll)
        // Rotation may reconnect to the same session. Leaving the wizard must not keep collecting.
        if (proximityWizardMustCancelOnStop(stage, isChangingConfigurations)) {
            sessionId?.let { ProximityWizardHost.action(it, "cancel") }
        }
        KioskAdminUi.setVisible(this, false)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        KioskAdminUi.setVisible(this, false)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SESSION, sessionId)
        super.onSaveInstanceState(outState)
    }

    private fun refresh() {
        val snapshot = ProximityWizardHost.status()?.let { runCatching { JSONObject(it) }.getOrNull() }
        val currentId = snapshot?.optString("sessionId").orEmpty()
        if (currentId.isBlank() || (sessionId != null && sessionId != currentId)) {
            render(JSONObject().put("stage", "unavailable"))
            return
        }
        sessionId = currentId
        if (snapshot!!.optString("stage") !in TERMINAL) ProximityWizardHost.action(currentId, "visible")
        render(snapshot)
    }

    private fun render(snapshot: JSONObject) {
        stage = snapshot.optString("stage", "unavailable")
        val awaitingReading = stage == "intro" && snapshot.optString("health") != "healthy"
        val cue = ProximityWizardCue.fromWire(snapshot.optString("cue"))
        val usesHand = proximityWizardUsesHand(stage, cue)
        val waveCount = proximityWizardWaveCount(snapshot.optString("wavePattern"))
        val capabilities = proximityWizardCapabilities(
            snapshot.optBoolean("presenceSupported"), snapshot.optBoolean("waveSupported"),
        )
        val collecting = !proximityWizardHasLocalStepAction(stage) && stage != "saving"
        val (title, hint) = if (collecting && cue != ProximityWizardCue.NONE) when (cue) {
            ProximityWizardCue.PREPARE -> R.string.proximity_wizard_prepare to R.string.proximity_wizard_prepare_hint
            ProximityWizardCue.APPROACH -> if (stage == "wave_baseline") {
                R.string.proximity_wizard_normal_position to R.string.proximity_wizard_hands_down
            } else if (usesHand) {
                R.string.proximity_wizard_hand_near to R.string.proximity_wizard_hand_near_hint
            } else R.string.proximity_wizard_approach to R.string.proximity_wizard_approach_hint
            ProximityWizardCue.HOLD -> R.string.proximity_wizard_hold to if (stage == "wave_baseline") {
                R.string.proximity_wizard_hands_down
            } else R.string.proximity_wizard_hold_hint
            ProximityWizardCue.MOVE_AWAY -> if (usesHand) {
                R.string.proximity_wizard_clear to R.string.proximity_wizard_clear_hint
            } else R.string.proximity_wizard_step_away to R.string.proximity_wizard_step_away_hint
            ProximityWizardCue.WAIT_CLEAR -> R.string.proximity_wizard_stay_clear to R.string.proximity_wizard_stay_clear_hint
            ProximityWizardCue.WAVE -> if (waveCount == 2) {
                R.string.proximity_wizard_wave_twice to R.string.proximity_wizard_wave_twice_hint
            } else R.string.proximity_wizard_wave_now to R.string.proximity_wizard_wave_now_hint
            ProximityWizardCue.NONE -> R.string.proximity_wizard_prepare to R.string.proximity_wizard_prepare_hint
        } else when (stage) {
            "intro" -> if (awaitingReading) {
                R.string.proximity_wizard_waiting_reading to R.string.proximity_wizard_waiting_reading_hint
            } else R.string.proximity_wizard_intro to R.string.proximity_wizard_intro_hint
            "clear" -> R.string.proximity_wizard_clear to R.string.proximity_wizard_clear_hint
            "near" -> R.string.proximity_wizard_near to R.string.proximity_wizard_near_hint
            "return_clear" -> R.string.proximity_wizard_return_clear to R.string.proximity_wizard_return_clear_hint
            "waves" -> R.string.proximity_wizard_waves to R.string.proximity_wizard_waves_hint
            "review" -> R.string.proximity_wizard_review to when (capabilities) {
                ProximityWizardCapabilities.BOTH -> R.string.proximity_wizard_review_verified
                ProximityWizardCapabilities.PRESENCE_ONLY -> R.string.proximity_wizard_review_presence_only
                ProximityWizardCapabilities.WAVE_ONLY -> R.string.proximity_wizard_review_wave_only
                ProximityWizardCapabilities.NEITHER -> R.string.proximity_wizard_review_neither
            }
            "saving" -> R.string.proximity_wizard_saving to R.string.proximity_wizard_saving_hint
            "saved" -> R.string.proximity_wizard_saved to when (capabilities) {
                ProximityWizardCapabilities.BOTH -> R.string.proximity_wizard_saved_hint
                ProximityWizardCapabilities.PRESENCE_ONLY -> R.string.proximity_wizard_saved_presence_only
                ProximityWizardCapabilities.WAVE_ONLY -> R.string.proximity_wizard_saved_wave_only
                ProximityWizardCapabilities.NEITHER -> R.string.proximity_wizard_unchanged
            }
            "cancelled" -> R.string.proximity_wizard_cancelled to R.string.proximity_wizard_unchanged
            "timed_out" -> R.string.proximity_wizard_timed_out to R.string.proximity_wizard_unchanged
            "failed" -> R.string.proximity_wizard_failed to R.string.proximity_wizard_unchanged
            else -> R.string.proximity_wizard_unavailable to R.string.proximity_wizard_unavailable_hint
        }
        // Do not re-announce unchanged instructions four times per second to accessibility services.
        val presentation = "$stage|$cue|$waveCount|$capabilities|${snapshot.optBoolean("canSave")}|${snapshot.optString("health")}|${snapshot.optString("message")}|${snapshot.optString("mode")}|${snapshot.optInt("acceptedGestures")}|${snapshot.optInt("requiredGestures", 3)}"
        if (presentation != lastPresentation) {
            lastPresentation = presentation
            instruction.setText(title)
            val failureDetail = snapshot.optString("message").takeIf {
                it.isNotBlank() && stage in setOf("failed", "cancelled", "timed_out")
            }
            detail.text = failureDetail ?: getString(hint)
            detail.textLocale = if (failureDetail != null) java.util.Locale.ENGLISH else resources.configuration.locales[0]
            mode.setText(R.string.proximity_wizard_binary)
            mode.visibility = if (snapshot.optString("mode") == "binary" && !awaitingReading && stage == "intro") View.VISIBLE else View.GONE
            val required = snapshot.optInt("requiredGestures", 3).coerceIn(1, 20)
            val accepted = snapshot.optInt("acceptedGestures", 0).coerceIn(0, required)
            progress.text = getString(R.string.proximity_wizard_progress, accepted, required)
            progress.visibility = if (cue == ProximityWizardCue.WAVE || (stage == "review" && snapshot.optBoolean("waveSupported"))) View.VISIBLE else View.GONE
            detail.visibility = View.VISIBLE
            visualRow.visibility = if (collecting || awaitingReading) View.VISIBLE else View.GONE
            val palette = statusPalette(StatusSurface.darkFor(this, Config(this)))
            pictogram.present(
                if (awaitingReading) ProximityWizardCue.WAVE else cue,
                usesHand || awaitingReading, Color.parseColor(palette.body), Color.parseColor(palette.accent),
                requestedWaveCount = waveCount,
                holdNearPanel = proximityWizardHoldsNearPanel(stage, snapshot.optString("wavePattern")),
            )
            pictogram.setPresenting(visible && visualRow.visibility == View.VISIBLE)
            indicator.visibility = if (stage == "saving") View.VISIBLE else View.GONE
            indicator.isIndeterminate = stage != "waves"
            indicator.max = required
            indicator.progress = accepted
            cancel.visibility = if (stage in TERMINAL) View.GONE else View.VISIBLE
            cancel.isEnabled = stage != "saving"
            primary.isEnabled = stage != "saving" && !awaitingReading &&
                (stage != "review" || (snapshot.optBoolean("canSave") && capabilities != ProximityWizardCapabilities.NEITHER))
            primary.visibility = if (!proximityWizardHasLocalStepAction(stage)) View.GONE else View.VISIBLE
            primary.setText(when (stage) {
                "intro" -> R.string.proximity_wizard_start
                "failed", "timed_out" -> R.string.proximity_wizard_retry
                "review" -> R.string.proximity_wizard_save
                else -> R.string.proximity_wizard_done
            })
            // Failed sessions offer a local exit as well as Retry.
            if (stage == "failed" || stage == "timed_out") cancel.visibility = View.VISIBLE
        }
        val countdown = proximityWizardCountdownSeconds(
            snapshot.optLong("cueRemainingMs"), snapshot.optLong("cueDurationMs"),
        )
        cadence.text = countdown?.toString() ?: getString(R.string.proximity_wizard_waiting_symbol)
        cadenceLabel.setText(when {
            countdown == null -> R.string.proximity_wizard_observing
            cue in setOf(ProximityWizardCue.HOLD, ProximityWizardCue.WAIT_CLEAR) -> R.string.proximity_wizard_capturing
            cue == ProximityWizardCue.WAVE && waveCount == 2 -> R.string.proximity_wizard_wave_twice
            cue == ProximityWizardCue.WAVE -> R.string.proximity_wizard_wave_window
            else -> R.string.proximity_wizard_preparing
        })
    }

    private fun perform(action: String) {
        if (action == "retry") sessionId?.let { ProximityWizardHost.action(it, "visible") }
        val accepted = sessionId?.let { ProximityWizardHost.action(it, action) } == true
        refresh()
        if (!accepted && stage != "unavailable") detail.setText(R.string.proximity_wizard_action_failed)
    }

    private fun cancelAndFinish() {
        if (stage == "saving") return
        sessionId?.let { ProximityWizardHost.action(it, "cancel") }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (stage == "saving") return
        sessionId?.let { ProximityWizardHost.action(it, "cancel") }
        super.onBackPressed()
    }

    companion object {
        private const val SESSION = "proximity_wizard_session"
        private val TERMINAL = setOf("saved", "cancelled", "timed_out", "failed", "unavailable")
    }
}
