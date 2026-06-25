package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.util.HelperClient

/**
 * **Taming intrusive vendor packages.** A firmware update can leave a vendor app that relaunches on
 * boot and even draws a floating widget over the dashboard (seen on the NSPanel Pro 120P after a Sonoff
 * update: `com.eWeLinkControlPanel`). This controller, driven by the opt-in
 * [io.github.maxlyth.hapaneld.Config.tameVendorPackages] blocklist, neutralises such a package by:
 *
 *  1. **force-stop** — kill it now (`am force-stop`),
 *  2. **disable-user** — stop it relaunching on boot (`pm disable-user --user 0`, reversible), and
 *  3. **overlay deny** — strip its floating-window permission (`appops set … SYSTEM_ALERT_WINDOW deny`).
 *
 * Everything is **privileged** (root), so it routes through the helper daemon (`STOP`/`DISABLE`/`OVERLAY`)
 * — which covers sandbox-walled panels — and falls back to `su` where the app can reach it. Everything is
 * **reversible** ([untame] re-enables + restores the overlay permission), and **critical system packages
 * are never touched**: a brick-guard here AND in the daemon refuses the system UI, Settings, telephony,
 * the framework, and ha-paneld itself. Only installed packages are acted on. The feature is **off by
 * default** — the blocklist is empty unless the user deliberately populates it.
 */
class TameController(private val context: Context) {

    /** Tame every blocklisted package that's installed and safe. Returns the packages actually tamed. */
    fun applyBlocklist(packages: List<String>): List<String> =
        packages.filter { tame(it) }

    /** force-stop + disable boot-relaunch + deny the overlay permission for [pkg]. */
    fun tame(pkg: String): Boolean {
        if (!actOn(pkg)) return false
        val stopped = privileged("STOP $pkg", "am force-stop $pkg")
        val disabled = privileged("DISABLE $pkg", "pm disable-user --user 0 $pkg")
        val overlay = privileged("OVERLAY $pkg deny", "appops set $pkg SYSTEM_ALERT_WINDOW deny")
        val ok = stopped && disabled && overlay
        Log.i(TAG, "tame $pkg: stop=$stopped disable=$disabled overlay=$overlay")
        return ok
    }

    /** Reverse [tame]: re-enable the package and restore its overlay permission (for a future UI / undo). */
    fun untame(pkg: String): Boolean {
        if (!isInstalled(pkg)) return false
        val enabled = privileged("ENABLE $pkg", "pm enable $pkg")
        val overlay = privileged("OVERLAY $pkg allow", "appops set $pkg SYSTEM_ALERT_WINDOW allow")
        Log.i(TAG, "untame $pkg: enable=$enabled overlay=$overlay")
        return enabled && overlay
    }

    /** Run a privileged op via the daemon (trusting only an explicit OK), falling back to su. */
    private fun privileged(daemonCmd: String, suCmd: String): Boolean {
        if (HelperClient.available() && HelperClient.send(daemonCmd) == "OK") return true
        return Su.run(suCmd)
    }

    /** Guard: act only on an installed, non-critical package. Mirrors the daemon's own backstop. */
    private fun actOn(pkg: String): Boolean {
        if (pkg.isBlank() || isCritical(pkg)) {
            if (isCritical(pkg)) Log.w(TAG, "refusing to tame critical package $pkg")
            return false
        }
        if (!isInstalled(pkg)) { Log.i(TAG, "skip $pkg: not installed"); return false }
        return true
    }

    private fun isInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    companion object {
        private const val TAG = "ha-paneld/tame"

        // Never stop/disable these, even if a user lists one — tearing them down bricks the panel. The
        // daemon enforces the same set as a privileged backstop; this is the app-side first line.
        private val CRITICAL = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "io.github.maxlyth.hapaneld",
        )

        fun isCritical(pkg: String): Boolean = pkg in CRITICAL
    }
}
