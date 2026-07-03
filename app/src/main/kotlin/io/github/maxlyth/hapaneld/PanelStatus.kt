package io.github.maxlyth.hapaneld

/**
 * Tiny process-wide status, shared with the launcher [MainActivity] which has no binding to the
 * service. Currently just the live MQTT connection state, used to gate auto-return-to-dashboard.
 */
object PanelStatus {
    @Volatile
    var mqttConnected: Boolean = false

    /** True while the app watchdog has given up relaunching a crash-looping dashboard (drives the
     *  `:8888` health warning + a `/diag` line). Cleared when the dashboard comes back healthy. */
    @Volatile
    var dashboardCrashLooping: Boolean = false
}
