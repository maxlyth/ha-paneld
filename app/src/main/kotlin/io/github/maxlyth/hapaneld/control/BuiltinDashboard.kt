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
}
