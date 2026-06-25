package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
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

    /** One row in the config page's tame list: a package, its label + current state, and whether it's
     *  currently on the blocklist (ticked). */
    data class Candidate(
        val pkg: String,
        val label: String,
        val installed: Boolean,
        val disabled: Boolean,
        val blocked: Boolean,
    )

    /**
     * The tame candidate list for the config UI. [enumerate]=false (a profiled panel) shows the panel's
     * curated [profileCandidates]; [enumerate]=true (the Generic profile) discovers candidates live with a
     * has-launcher / holds-overlay / non-platform-signed heuristic. Currently-[blocked] packages are always
     * included (so an arbitrary one the user added still shows, with its tick), and untouchables (critical
     * system packages, HA, ourselves) are never offered. Installed packages sort first, then by label.
     */
    fun candidates(profileCandidates: List<String>, blocked: List<String>, enumerate: Boolean): List<Candidate> {
        val blockedSet = blocked.toSet()
        val pkgs = LinkedHashSet<String>()
        pkgs += if (enumerate) enumerate() else profileCandidates
        pkgs += blocked
        return pkgs.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isUntouchable(it) }
            .distinct()
            .map { toCandidate(it, blockedSet) }
            .sortedWith(compareByDescending<Candidate> { it.installed }.thenBy { it.label.lowercase() })
            .toList()
    }

    /**
     * Enumerated "likely to want to control" packages for the picker pop-up — runs the heuristic on ANY
     * panel (profiled or not), so a user who doesn't know package names can pick from a list instead of
     * typing one. [exclude] drops packages already shown in the card (its curated/blocked rows), and the
     * untouchables are never enumerated. Installed first, then by label.
     */
    fun suggestions(exclude: Set<String>): List<Candidate> =
        enumerate().asSequence()
            .filter { it !in exclude }
            .distinct()
            .map { toCandidate(it, exclude) }
            .sortedWith(compareByDescending<Candidate> { it.installed }.thenBy { it.label.lowercase() })
            .toList()

    private fun toCandidate(pkg: String, blocked: Set<String>): Candidate {
        val pm = context.packageManager
        val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        val label = ai?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            ?.takeIf { it != pkg } ?: pkg
        val disabled = ai != null && runCatching { pm.getApplicationEnabledSetting(pkg) }.getOrNull()
            ?.let { it == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    it == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER } ?: false
        return Candidate(pkg, label, installed = ai != null, disabled = disabled, blocked = pkg in blocked)
    }

    // Discovery: a package is a candidate if it (a) has a launcher activity or holds the overlay
    // permission — an app "with a face" or one that can draw over the dashboard — AND (b) is not in the
    // core-AOSP namespace. The namespace gate is the load-bearing filter: vendor bloat lives in vendor
    // namespaces (com.eWeLink*, com.rockchip*, com.smatek*, org.fdroid, …), never com.android.* — whereas
    // many core platform packages (telecom, the settings/telephony providers, …) DO hold the overlay
    // permission, and taming one of those would brick the panel. So com.android.* / com.google.android.*
    // are excluded outright; a headless vendor service won't appear either, but the free-text box covers
    // both rare cases. The current home launcher and IME are excluded so the picker can't suggest
    // disabling the very things the user navigates with.
    private fun enumerate(): List<String> {
        val pm = context.packageManager
        val launchers = runCatching {
            // MATCH_DISABLED_COMPONENTS so an already-disabled vendor app's launcher still resolves —
            // otherwise the picker can't surface it for re-enabling (its activity is hidden by default).
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.MATCH_DISABLED_COMPONENTS)
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        val home = runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        }.getOrNull()
        val ime = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        }.getOrNull()
        val skip = setOfNotNull(home, ime)
        return runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            .map { it.packageName }
            .filter { pkg ->
                !isUntouchable(pkg) && !isCoreAosp(pkg) && pkg !in skip &&
                    (pkg in launchers || hasOverlay(pkg))
            }
    }

    // Core AOSP / GMS namespaces — excluded from discovery (see [enumerate]). Vendor bloat is never here.
    private fun isCoreAosp(pkg: String): Boolean =
        pkg.startsWith("com.android.") || pkg.startsWith("com.google.android.")

    private fun hasOverlay(pkg: String): Boolean = runCatching {
        context.packageManager.checkPermission("android.permission.SYSTEM_ALERT_WINDOW", pkg) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun isUntouchable(pkg: String): Boolean =
        isCritical(pkg) || pkg == context.packageName || pkg in HA_PACKAGES

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

        // The HA Companion dashboard apps — never offered as tame candidates (this is an HA project; the
        // dashboard is the whole point). Excluded from enumeration/suggestions, not the brick-guard.
        private val HA_PACKAGES = setOf(
            "io.homeassistant.companion.android",
            "io.homeassistant.companion.android.minimal",
        )

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
