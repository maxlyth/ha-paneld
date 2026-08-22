package io.github.maxlyth.hapaneld

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.maxlyth.hapaneld.security.LocalApprovalBroker
import io.github.maxlyth.hapaneld.security.PendingApproval
import io.github.maxlyth.hapaneld.util.GuardDbMaintenance
import io.github.maxlyth.hapaneld.util.GuardDbMaintenanceClient
import io.github.maxlyth.hapaneld.util.GuardDbProcessAdmission
import java.util.concurrent.Executors

internal fun redirectToGuardDbMaintenanceIfRequired(activity: Activity): Boolean {
    if (!GuardDbProcessAdmission.maintenanceRequired() || activity is GuardDbMaintenanceActivity) return false
    activity.startActivity(
        Intent(activity, GuardDbMaintenanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
    activity.finish()
    return true
}

/**
 * A redirect from [Activity.onCreate] does not end that Activity's lifecycle: Android may still call
 * onStart/onResume, deliver a new intent, or restore focus before finish completes.  Keep the decision
 * sticky for the whole instance so none of those callbacks can construct Config or a database owner.
 *
 * [stop] is deliberately pure apart from the supplied callback, which lets the lifecycle contract be
 * tested without constructing an Android Activity.
 */
internal class GuardDbActivityMaintenanceFence {
    private var redirected = false

    internal fun stop(maintenanceRequired: Boolean, redirect: () -> Unit): Boolean {
        if (redirected) return true
        if (!maintenanceRequired) return false
        redirected = true
        redirect()
        return true
    }

    fun stop(activity: Activity): Boolean = stop(
        maintenanceRequired = GuardDbProcessAdmission.maintenanceRequired() &&
            activity !is GuardDbMaintenanceActivity,
        redirect = { redirectToGuardDbMaintenanceIfRequired(activity) },
    )
}

/** Native approval surface which never constructs Config, WebView, or a SQLite owner. */
class GuardDbMaintenanceActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var approvals: LinearLayout
    private val refresh = object : Runnable {
        override fun run() {
            refreshState()
            main.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        GuardDbMaintenanceService.start(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(TextView(this@GuardDbMaintenanceActivity).apply {
                text = "Database recovery maintenance"
                textSize = 22f
            })
            addView(TextView(this@GuardDbMaintenanceActivity).apply {
                text = "Normal panel services and database writers are paused. Keep this panel powered on; " +
                    "recovery is guaranteed only for this boot. Approve only the exact next step you requested."
                textSize = 15f
                setPadding(0, dp(12), 0, dp(12))
            })
            status = TextView(this@GuardDbMaintenanceActivity).apply { text = "Reading root journal…" }
            addView(status)
            approvals = LinearLayout(this@GuardDbMaintenanceActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(16), 0, 0)
            }
            addView(approvals)
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun refreshState() {
        renderApprovals(LocalApprovalBroker.instance.pending())
        worker.execute {
            val probe = GuardDbMaintenance.client.statusProbe()
            main.post {
                status.text = when (probe) {
                    is GuardDbMaintenanceClient.StatusProbe.Valid ->
                        buildString {
                            append("Root journal: ${probe.status.phase} · generation ${probe.status.generation}")
                            probe.status.outcome?.let { append(" · outcome $it") }
                            if (probe.status.overallDeadlineElapsedMs > 0L) {
                                append("\nHard deadline: ${probe.status.overallDeadlineElapsedMs} elapsed-ms")
                                append(" · forward: ${probe.status.forwardDeadlineElapsedMs}")
                            }
                        }
                    GuardDbMaintenanceClient.StatusProbe.Unreachable -> "Root helper is restarting or unreachable. No mutation is allowed."
                    GuardDbMaintenanceClient.StatusProbe.Malformed -> "Root journal response is malformed. Recovery is held."
                    GuardDbMaintenanceClient.StatusProbe.Unsupported -> "Installed helper cannot run this recovery. Recovery is held."
                }
            }
        }
    }

    private fun renderApprovals(pending: List<PendingApproval>) {
        approvals.removeAllViews()
        approvals.addView(TextView(this).apply {
            text = if (pending.isEmpty()) "No pending physical approval." else "Pending physical approval"
            textSize = 17f
        })
        pending.forEach { approval ->
            approvals.addView(Button(this).apply {
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                text = "${approval.operation.label}\n${approval.summary}\nFrom ${approval.peer}"
                setOnClickListener { confirm(approval) }
            })
        }
    }

    private fun confirm(approval: PendingApproval) {
        AlertDialog.Builder(this)
            .setTitle(approval.operation.label)
            .setMessage("${approval.summary}\n\nExact request from ${approval.peer}. Approval is one-shot and expires.")
            .setPositiveButton("Approve") { _, _ -> LocalApprovalBroker.instance.approve(approval.id) }
            .setNegativeButton("Deny") { _, _ -> LocalApprovalBroker.instance.deny(approval.id) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
