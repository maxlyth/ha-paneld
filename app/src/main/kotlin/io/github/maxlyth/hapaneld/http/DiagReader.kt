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

    fun dump(ctx: Context): String = buildString {
        appendLine("ha-paneld diagnostics — ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        appendLine()
        appendLine("[device]")
        appendLine("manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} model=${Build.MODEL} device=${Build.DEVICE}")
        appendLine("product=${Build.PRODUCT} board=${Build.BOARD} hardware=${Build.HARDWARE}")
        appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} display=${Build.DISPLAY}")
        appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("fingerprint=${Build.FINGERPRINT}")
        appendLine()
        appendLine("[environment]")
        appendLine("selinux.enforce=${readFile("/sys/fs/selinux/enforce")?.trim() ?: "?"}")
        appendLine("su.available=${Su.available()}")
        appendLine("write_settings=${Settings.System.canWrite(ctx)}")
        appendLine("a11y.enabled=${a11yEnabled(ctx)}")
        appendLine("helper_daemon.reachable=${HelperClient.available()}")
        appendLine("dev.ledjni.exists=${File("/dev/ledjni").exists()} openable=${NativeLed.available()}")
        appendLine()
        appendLine("[/sys/class/leds] ${listDir("/sys/class/leds")}")
        appendLine("[/sys/class/backlight] ${listDir("/sys/class/backlight")}")
        appendLine("[/sys/class/devfreq] ${listDir("/sys/class/devfreq")}")
        appendLine()
        appendLine("[ls -Z (SELinux labels)]")
        appendLine(exec("ls -Zd /sys/class/leds/*/ /sys/class/backlight/*/ /dev/ledjni 2>&1"))
        appendLine("[packages]")
        for (id in listOf("io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal")) {
            appendLine("  $id=${pkgVer(ctx, id)}")
        }
        appendLine()
        appendLine("[capabilities]")
        for (c in capabilities(ctx)) appendLine("  ${c.name}: ${c.status} — ${c.note}")
    }

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
