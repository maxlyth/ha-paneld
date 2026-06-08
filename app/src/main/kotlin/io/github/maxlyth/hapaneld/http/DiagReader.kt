package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.hardware.NativeLed
import io.github.maxlyth.hapaneld.util.HelperClient
import java.io.File

/**
 * Self-diagnostics for the info page. Two outputs from the same probes:
 *  - [capabilities] — a per-capability status (ok / degraded / none) with an actionable note, so the
 *    UI can tell the user what works on THEIR hardware/firmware and how to fix a shortfall.
 *  - [dump] — a copy-paste text report (build, SELinux, su, sysfs/dev nodes, packages, capabilities)
 *    for pasting into a bug report, so a maintainer can diagnose a panel they don't own.
 *
 * Everything here is read as the app uid — world-readable /proc + /sys, `appops`/Settings checks,
 * and a graceful `su` probe. No assumption of root.
 */
object DiagReader {

    /** status: "ok" | "degraded" | "none" */
    data class Cap(val name: String, val status: String, val note: String)

    fun capabilities(ctx: Context): List<Cap> {
        val pkg = ctx.packageName
        val su = Su.available()
        val daemon = HelperClient.available()
        val rkLed = NativeLed.available()
        val canWrite = Settings.System.canWrite(ctx)
        val a11y = a11yEnabled(ctx)
        val rootish = su || daemon
        return listOf(
            Cap("Root (su)", if (su) "ok" else "none",
                if (su) "available" else
                    "no su on this firmware — sysfs-LED, reboot/reload and true screen-off are unavailable; everything else still works"),
            Cap("Brightness", if (canWrite) "ok" else "none",
                if (canWrite) "WRITE_SETTINGS granted" else
                    "grant it (no root needed): adb shell appops set $pkg WRITE_SETTINGS allow"),
            Cap("Screen on/off", if (daemon || su) "ok" else "degraded",
                when {
                    daemon -> "true backlight-off via the helper daemon"
                    su -> "true backlight-off via su bl_power"
                    else -> "DIM ONLY — the backlight stays powered; needs su or the helper daemon for a real off"
                }),
            Cap("RGB LED", if (rkLed || daemon) "ok" else "none",
                when {
                    rkLed -> "rk3576 /dev/ledjni (app-direct, no root)"
                    daemon -> "sysfs LED via the helper daemon"
                    else -> "no app-accessible LED node; sysfs LEDs need the root helper daemon (install needs su once)"
                }),
            Cap("Hardware buttons", if (a11y) "ok" else "none",
                if (a11y) "accessibility key capture enabled" else
                    "enable (no root): adb shell settings put secure enabled_accessibility_services $pkg/.input.PanelAccessibilityService && adb shell settings put secure accessibility_enabled 1"),
            Cap("Reboot / reload / launcher", if (rootish) "ok" else "none",
                if (rootish) "available" else "needs su or the helper daemon"),
        )
    }

    /**
     * Terse, version-stamped copy-paste report for GitHub issues. The `[panel]` block reuses the EXACT
     * facts shown on the info page ([facts], passed by the caller) so it auto-tracks every field we add —
     * no separate maintenance — and **network addresses are omitted** ([OMIT]) so it's safe to paste in a
     * public thread. Every other section is one line. The version+build header is the version control: a
     * pasted report is always attributable to the build that produced it.
     */
    fun dump(ctx: Context, facts: Map<String, String> = emptyMap()): String = buildString {
        appendLine("ha-paneld diagnostics — ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        if (facts.isNotEmpty()) {
            appendLine()
            appendLine("[panel]")
            for ((k, v) in facts) if (k !in OMIT) appendLine("$k=$v")
        }
        appendLine()
        appendLine("[build] fingerprint=${Build.FINGERPRINT}")
        appendLine("board=${Build.BOARD} product=${Build.PRODUCT} hardware=${Build.HARDWARE} abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("[env] selinux=${readFile("/sys/fs/selinux/enforce")?.trim() ?: "?"} su=${Su.available()} write_settings=${Settings.System.canWrite(ctx)} a11y=${a11yEnabled(ctx)} daemon=${HelperClient.available()} ledjni=${NativeLed.available()}")
        appendLine("[sysfs] leds=${listDir("/sys/class/leds")} backlight=${listDir("/sys/class/backlight")} devfreq=${listDir("/sys/class/devfreq")}")
        appendLine("[labels] ${exec("ls -Zd /sys/class/leds/*/ /sys/class/backlight/*/ /dev/ledjni 2>&1").replace("\n", " ")}")
        appendLine("[packages] " + listOf("io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal")
            .joinToString(" ") { "${it.substringAfterLast('.')}=${pkgVer(ctx, it)}" })
        appendLine("[capabilities] " + capabilities(ctx).joinToString(" | ") { "${it.name}=${it.status}" })
    }

    // Omitted from the report (it's for public GitHub issues): network addresses; non-diagnostic instance
    // config (panel_id / Friendly name / HTTP port / mDNS — no hardware/capability signal); and Device ID
    // (an ANDROID_ID identifier whose only value is correlating reports — not worth a public identifier).
    private val OMIT = setOf(
        "Local IP", "Local IPv6", "MQTT", "panel_id", "Friendly name", "HTTP port", "mDNS", "Device ID",
    )

    private fun a11yEnabled(ctx: Context): Boolean =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .contains(ctx.packageName)

    private fun readFile(p: String): String? = runCatching { File(p).readText() }.getOrNull()

    private fun listDir(p: String): List<String> =
        runCatching { File(p).listFiles()?.map { it.name }?.sorted() ?: emptyList() }.getOrNull() ?: emptyList()

    private fun pkgVer(ctx: Context, id: String): String =
        runCatching { ctx.packageManager.getPackageInfo(id, 0).versionName ?: "?" }.getOrElse { "not installed" }

    private fun exec(cmd: String): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        (p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()).trim()
    }.getOrElse { "(exec failed)" }
}
