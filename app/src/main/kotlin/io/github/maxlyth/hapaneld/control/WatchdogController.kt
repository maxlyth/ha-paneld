package io.github.maxlyth.hapaneld.control

import android.os.SystemClock
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.PanelStatus
import io.github.maxlyth.hapaneld.util.periodic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicLong

/**
 * App watchdog — keeps the dashboard alive on a wall panel so it self-heals without intervention.
 *
 * When enabled it polls the dashboard app every [INTERVAL_MS] via [SystemController.dashboardState]
 * (the daemon's `APPSTATE` verb, else `su`) and recovers two failure modes:
 *
 * - **process died** — the dashboard crashed or was killed: relaunch it. Debounced over [DEAD_STREAK]
 *   consecutive dead checks so a momentary restart isn't mistaken for a crash. Relaunches are rate-limited
 *   by a [CrashLoopTracker]: if the app crashes on launch (e.g. an incompatible Companion update) and is
 *   relaunched too many times too fast, it backs off and raises [PanelStatus.dashboardCrashLooping] rather
 *   than storming the screen — cleared automatically when the dashboard comes back foreground.
 * - **backgrounded too long** — the dashboard is alive but hasn't been foreground for [BG_TIMEOUT_MS]
 *   (someone opened another app / Settings and walked away): bring it back.
 *
 * It never acts while the dashboard is foreground, and deliberate navigation keeps the process alive,
 * so routine admin use isn't interrupted until the background timeout. Opt-in ([Config.watchdogEnabled]);
 * a no-op on panels with neither root nor the daemon (state reads [AppState.UNKNOWN] and nothing fires).
 */
class WatchdogController(
    private val system: SystemController,
    private val config: Config,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var job: Job? = null
    private val runGeneration = AtomicLong()

    /** Idempotent: (re)start the poll loop when [enabled], else stop it. Called at boot and on toggle. */
    @Synchronized
    fun apply(enabled: Boolean) {
        val generation = synchronized(this) {
            runGeneration.incrementAndGet().also { PanelStatus.dashboardCrashLooping = false }
        }
        job?.cancel()
        job = null
        if (!enabled) { Log.i(TAG, "watchdog off"); return }
        Log.i(TAG, "watchdog on (poll ${INTERVAL_MS / 1000}s, bg-return ${BG_TIMEOUT_MS / 1000}s)")
        val policy = DashboardRecoveryPolicy(DEAD_STREAK, BG_TIMEOUT_MS)
        job = scope.periodic(INTERVAL_MS, initialDelayMs = INTERVAL_MS, tag = TAG, name = "watchdog") {
            val pkg = config.dashboardPackage
            val state = runCatching { system.dashboardState(pkg) }.getOrDefault(AppState.UNKNOWN)
            if (generation != runGeneration.get() || pkg != config.dashboardPackage) return@periodic
            val decision = policy.evaluate(pkg, state, SystemClock.elapsedRealtime())
            if (generation != runGeneration.get() || pkg != config.dashboardPackage) return@periodic
            val becameCrashLooping = publishCrashStatus(generation, decision.crashLooping) ?: return@periodic
            if (becameCrashLooping) {
                Log.e(TAG, "dashboard crash-looping -> backing off relaunches; see health warning")
            }
            if (generation != runGeneration.get() || pkg != config.dashboardPackage) return@periodic
            when (decision.action) {
                DashboardRecoveryPolicy.Action.NONE -> Unit
                DashboardRecoveryPolicy.Action.RELAUNCH_DEAD -> {
                    Log.w(TAG, "dashboard process dead -> relaunching")
                    system.launchHome(pkg)
                }
                DashboardRecoveryPolicy.Action.RETURN_FROM_BACKGROUND -> {
                    Log.i(TAG, "dashboard backgrounded > ${BG_TIMEOUT_MS / 1000}s -> returning to it")
                    system.launchHome(pkg)
                }
            }
        }
    }

    /** Stop the loop for good (service teardown). */
    @Synchronized
    fun stop() {
        synchronized(this) {
            runGeneration.incrementAndGet()
            PanelStatus.dashboardCrashLooping = false
        }
        job?.cancel()
        job = null
    }

    /** Publish only for the live run. Null means this run was retired; true means the warning rose. */
    private fun publishCrashStatus(generation: Long, crashLooping: Boolean): Boolean? = synchronized(this) {
        if (generation != runGeneration.get()) return@synchronized null
        val becameCrashLooping = !PanelStatus.dashboardCrashLooping && crashLooping
        PanelStatus.dashboardCrashLooping = crashLooping
        becameCrashLooping
    }

    companion object {
        private const val TAG = "ha-paneld/watchdog"
        private const val INTERVAL_MS = 30_000L     // poll cadence
        private const val DEAD_STREAK = 2           // consecutive dead checks before a relaunch
        private const val BG_TIMEOUT_MS = 300_000L  // 5 min backgrounded -> return to dashboard
    }
}
