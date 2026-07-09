package io.github.maxlyth.hapaneld.control

/**
 * Process-local foreground state of the built-in [io.github.maxlyth.hapaneld.DashboardActivity].
 *
 * The built-in renderer runs inside ha-paneld's own process, so — unlike a foreign dashboard app —
 * its liveness/foreground can be read directly from an activity lifecycle flag instead of probed with
 * root `pidof`/`dumpsys`. [SystemController.dashboardState] reads this for the `builtin` dashboard so
 * the watchdog + kiosk return-loop work with zero root and zero IPC. Pure Kotlin (no Android imports)
 * so the controller stays unit-testable.
 */
object BuiltinDashboard {
    /** True while DashboardActivity is resumed (foreground). Set from its onResume/onPause. */
    @Volatile var foreground = false

    /** The dashboard path the renderer should show — set by an MQTT `navigate` command, then the
     *  renderer is (re)launched and reads this on load. Null = fall back to the configured home
     *  dashboard. Lets `navigate` drive the built-in WebView the way a deep link drives the Companion. */
    @Volatile var navPath: String? = null

    // Screen-state fan-out to the live renderer. A 24/7 dashboard WebView keeps churning CPU (websocket
    // state, animations, JS timers) behind a dark screen, so when the panel screen goes off/on we tell
    // the renderer to pause/resume the WebView. The activity registers a listener (and must marshal to
    // the UI thread itself); [ScreenController] pokes it. Pure Kotlin so the controller stays testable.
    @Volatile private var screenListener: ((Boolean) -> Unit)? = null

    /** Renderer registers its pause/resume handler here. */
    fun setScreenListener(l: ((Boolean) -> Unit)?) { screenListener = l }

    /** Clear the listener only if it's still [l] — so a destroyed old activity instance can't wipe a
     *  newer instance's registration when their lifecycles overlap. */
    fun clearScreenListener(l: (Boolean) -> Unit) { if (screenListener === l) screenListener = null }

    /** [ScreenController] calls this from sleep()/wake(); no-op when no renderer is listening. */
    fun onScreenAwake(awake: Boolean) { screenListener?.invoke(awake) }

    /** True while the renderer is latched on "HA definitively rejected our credential" (revoked token /
     *  repeated auth-invalid). Read by the `:8888` health warnings so the failure is visible off-panel;
     *  cleared when a reload/navigate arrives or the frontend connects. */
    @Volatile var authLatched = false
}
