package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.hardware.NativeLed
import io.github.maxlyth.hapaneld.input.ButtonCaptureHealth
import io.github.maxlyth.hapaneld.input.EvdevButtonClient
import io.github.maxlyth.hapaneld.metrics.PanelMetrics
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.util.UpdateChecker
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

    fun capabilities(ctx: Context, profile: DeviceProfile = DeviceProfile.detect()): List<Cap> {
        val pkg = ctx.packageName
        val su = Su.available()
        val daemon = HelperClient.available()
        val rkLed = NativeLed.available()
        // Which LED node the daemon can actually reach — so "RGB LED" reflects a reachable node, not
        // just "a daemon is running". An old daemon predates LEDPROBE and replies "ERR" → fall back to
        // the daemon-up signal (prior behaviour); "none" = daemon present but no LED node.
        val ledProbe = HelperClient.send("LEDPROBE")
        val daemonLed = when (ledProbe) {
            "ledjni", "sysfs" -> true
            "none" -> false
            else -> daemon
        }
        val canWrite = Settings.System.canWrite(ctx)
        val a11y = a11yEnabled(ctx)
        val evdev = EvdevButtonClient.snapshot()
        val buttonHealth = ButtonCaptureHealth.evaluate(a11y, profile.evdevButtons.size, evdev, pkg)
        val rootish = su || daemon
        // Surface the helper whenever this profile needs it for privileged control or profile-specific
        // hardware such as daemon-only LEDs and evdev buttons, even if the app can also execute su.
        val usesDaemon = profile.usesDaemon
        return listOfNotNull(
            Cap("Root (su)", if (su) "ok" else "none",
                if (su) "available" else
                    "no su on this firmware — sysfs-LED, reboot/reload and true screen-off are unavailable; everything else still works"),
            if (usesDaemon) Cap("Helper daemon", if (daemon) "ok" else "none",
                if (daemon) daemonRequirement(profile, running = true)
                else daemonRequirement(profile, running = false))
            else null,
            Cap("Brightness", if (canWrite) "ok" else "none",
                if (canWrite) "WRITE_SETTINGS granted" else
                    "grant it (no root needed): adb shell appops set $pkg WRITE_SETTINGS allow"),
            Cap("Screen on/off", if (daemon || su) "ok" else "degraded",
                when {
                    daemon -> "true backlight-off via the helper daemon"
                    su -> "true backlight-off via su bl_power"
                    else -> "DIM ONLY — the backlight stays powered; needs su or the helper daemon for a real off"
                }),
            Cap("RGB LED", if (rkLed || daemonLed) "ok" else "none",
                when {
                    rkLed -> "Rockchip /dev/ledjni (app-direct, no root)"
                    ledProbe == "ledjni" -> "Rockchip /dev/ledjni ioctl via the helper daemon (root)"
                    ledProbe == "sysfs" || daemonLed -> "sysfs LED via the helper daemon"
                    else -> "no reachable LED node; needs the root helper daemon (install needs su once)"
                }),
            Cap("Hardware buttons", buttonHealth.status, buttonHealth.note),
            Cap("Reboot / reload / launcher", if (rootish) "ok" else "none",
                if (rootish) "available" else "needs su or the helper daemon"),
        )
    }

    /** Hardware-button truth combines the two independent sources. Accessibility sees Android-delivered
     * keys; profile-declared evdev keys require the helper daemon and can exist even when accessibility is
     * off. A missing daemon degrades an otherwise-working accessibility path instead of hiding the loss. */
    internal fun hardwareButtonsCapability(
        accessibility: Boolean,
        daemon: Boolean,
        evdevButtonCount: Int,
        packageName: String = "io.github.maxlyth.hapaneld",
    ): Cap = when {
        accessibility && evdevButtonCount > 0 && daemon -> Cap(
            "Hardware buttons", "ok",
            "accessibility key capture plus $evdevButtonCount daemon-instrumented physical button(s)",
        )
        accessibility && evdevButtonCount > 0 -> Cap(
            "Hardware buttons", "degraded",
            "accessibility key capture works; $evdevButtonCount profiled physical button(s) need the helper daemon",
        )
        accessibility -> Cap("Hardware buttons", "ok", "accessibility key capture enabled")
        evdevButtonCount > 0 && daemon -> Cap(
            "Hardware buttons", "ok", "$evdevButtonCount profiled physical button(s) via the helper daemon",
        )
        evdevButtonCount > 0 -> Cap(
            "Hardware buttons", "none", "$evdevButtonCount profiled physical button(s) need the helper daemon",
        )
        else -> Cap(
            "Hardware buttons", "none",
            "enable (no root): adb shell settings put secure enabled_accessibility_services $packageName/.input.PanelAccessibilityService && adb shell settings put secure accessibility_enabled 1",
        )
    }

    private fun daemonRequirement(profile: DeviceProfile, running: Boolean): String {
        val state = if (running) "running" else "NEEDED but not running"
        return when {
            !profile.appCanSu -> "$state — the privileged control path on this sandbox-walled panel; without it, root-only controls remain unavailable"
            profile.evdevButtons.isNotEmpty() -> "$state — required for ${profile.evdevButtons.size} profiled physical button(s), even though ordinary privileged actions can use su"
            profile.hasButtonBacklight -> "$state — required for the profiled button backlight"
            else -> "$state — required for this profile's daemon-backed hardware"
        }
    }

    /**
     * Terse, version-stamped copy-paste report for GitHub issues. The `[panel]` block reuses the EXACT
     * facts shown on the info page ([facts], passed by the caller) so it auto-tracks every field we add —
     * no separate maintenance — and **network addresses are omitted** ([OMIT]) so it's safe to paste in a
     * public thread. Every other section is one line. The version+build header is the version control: a
     * pasted report is always attributable to the build that produced it.
     */
    fun dump(
        ctx: Context,
        facts: Map<String, String> = emptyMap(),
        profile: DeviceProfile = DeviceProfile.detect(),
    ): String = buildString {
        appendLine("ha-paneld diagnostics — ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        // Capture metadata — a normalise-me line for the regression harness: when this dump was taken +
        // how long the panel has been up (uptime is often more telling than wall-clock on a panel).
        appendLine("[captured] ${java.time.OffsetDateTime.now()} uptime=${fmtUptime(android.os.SystemClock.elapsedRealtime())}")
        if (facts.isNotEmpty()) {
            appendLine()
            appendLine("[panel]")
            for ((k, v) in facts) if (k !in OMIT) appendLine("$k=$v")
        }
        appendLine()
        appendLine("[build] fingerprint=${Build.FINGERPRINT}")
        appendLine("board=${Build.BOARD} product=${Build.PRODUCT} hardware=${Build.HARDWARE} abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        val evdev = EvdevButtonClient.snapshot()
        appendLine("[env] selinux=${PanelMetrics.shared.selinuxEnforce() ?: "?"} su=${Su.available()} write_settings=${Settings.System.canWrite(ctx)} a11y=${a11yEnabled(ctx)} daemon=${HelperClient.available()} evdev=${evdev.state.name.lowercase()}/${evdev.mode?.name?.lowercase() ?: "none"} ledjni=${NativeLed.available()}")
        val expectedButtons = DeviceProfile.detect().evdevButtons
        if (expectedButtons.isNotEmpty()) {
            val requested = expectedButtons.joinToString(",") { "${it.node}:${if (it.sw) "SW" else "KEY"}/${it.code}:${if (it.grab) "grab" else "watch"}" }
            appendLine("[evdev] requested=$requested state=${evdev.state.name.lowercase()} mode=${evdev.mode?.name?.lowercase() ?: "none"} error=${evdev.lastError ?: "-"}")
        }
        appendLine("[sysfs] leds=${listDir("/sys/class/leds")} backlight=${listDir("/sys/class/backlight")} devfreq=${listDir("/sys/class/devfreq")}")
        appendLine("[labels] ${exec("ls -Zd /sys/class/leds/*/ /sys/class/backlight/*/ /dev/ledjni 2>&1").replace("\n", " ")}")
        // Bounded, read-only characterization for an unknown/new panel. These are the high-signal
        // names needed to locate vendor climate sensors, relay controllers and input devices without
        // asking a non-developer reporter to run a long sequence of adb commands. Avoid raw uevent,
        // serial and address data; every list is sanitized and capped.
        appendLine("[hardware]")
        appendLine("  inputs=${HardwareCharacterization.inputDevices(readFile("/proc/bus/input/devices"))}")
        appendLine("  i2c=${HardwareCharacterization.namedDevices(File("/sys/bus/i2c/devices"), "name")}")
        appendLine("  iio=${HardwareCharacterization.namedDevices(File("/sys/bus/iio/devices"), "name")}")
        appendLine("  thermal=${HardwareCharacterization.namedDevices(File("/sys/class/thermal"), "type")}")
        appendLine("  relays=${HardwareCharacterization.relayClasses(listOf(File("/sys/class/relay"), File("/sys/class/st_relay"), File("/sys/class/strelay")))}")
        // GPIO export diagnostic — only for panels with sysfs button-LED pins (the S9E, gpio147–150).
        // RelayController.ledCount() exports those pins on demand; if a reporter still sees 0 LEDs the
        // usual cause is a gpiochip-base shift (the kernel numbered the pins differently), which these
        // lines expose: check that led_base falls inside some chip's [base, base+ngpio) range, and which
        // pin dirs / value nodes actually came up. Read-only.
        profile.buttonLedGpioBase?.let { base ->
            val pinPaths = (0 until 4).map { "/sys/class/gpio/gpio${base + it}" }
            val chips = probe("grep -H '' /sys/class/gpio/gpiochip*/base /sys/class/gpio/gpiochip*/ngpio /sys/class/gpio/gpiochip*/label 2>/dev/null")
                .replace("/sys/class/gpio/", "").replace("\n", " ")
            val pinDirs = probe("ls -d ${pinPaths.joinToString(" ")} 2>/dev/null").replace("\n", " ")
            val valueNodes = probe("ls ${pinPaths.joinToString(" ") { "$it/value" }} 2>/dev/null").replace("\n", " ")
            appendLine("[gpio] led_base=$base (button-LED pins must fall inside a chip's [base,base+ngpio))")
            appendLine("  chips: ${chips.ifBlank { "(none readable)" }}")
            appendLine("  pin_dirs: ${pinDirs.ifBlank { "(none exported)" }}")
            appendLine("  value_nodes: ${valueNodes.ifBlank { "(none)" }}")
        }
        appendLine("[packages] " + listOf("io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal")
            .joinToString(" ") { "${it.substringAfterLast('.')}=${pkgVer(ctx, it)}" })
        // Vendor packages this panel's profile knows about, with live state — so a maintainer can see the
        // tame candidates and what's present/disabled on this firmware. Only when the profile defines them.
        val tameCandidates = profile.tameVendorCandidates
        if (tameCandidates.isNotEmpty()) {
            appendLine("[vendor-tame] " + TameController(ctx).profileReport(tameCandidates).joinToString(" | ") { c ->
                val state = if (!c.installed) "absent" else if (c.disabled) "disabled" else "active"
                c.pkg + "=" + state + if (c.tags.isNotEmpty()) "(${c.tags.joinToString(",")})" else ""
            })
        }
        appendLine("[capabilities] " + capabilities(ctx, profile).joinToString(" | ") { "${it.name}=${it.status}" })
        val updates = UpdateChecker.current(ctx)   // revalidated: no stale entry for an uninstalled Companion
        if (updates.isNotEmpty()) {
            appendLine("[updates] " + updates.joinToString(" | ") { "${it.label}: ${it.currentVersion} → ${it.latestVersion}" })
        }
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

    /** Compact device uptime from elapsed-realtime ms, e.g. "3d2h", "5h12m", "47m", "23s". */
    private fun fmtUptime(ms: Long): String {
        val s = ms / 1000; val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
        return when {
            d > 0 -> "${d}d${h}h"
            h > 0 -> "${h}h${m}m"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    private fun readFile(p: String): String? = runCatching { File(p).readText() }.getOrNull()

    private fun listDir(p: String): List<String> =
        runCatching { File(p).listFiles()?.map { it.name }?.sorted() ?: emptyList() }.getOrNull() ?: emptyList()

    private fun pkgVer(ctx: Context, id: String): String =
        runCatching { ctx.packageManager.getPackageInfo(id, 0).versionName ?: "?" }.getOrElse { "not installed" }

    private fun exec(cmd: String): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        (p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()).trim()
    }.getOrElse { "(exec failed)" }

    /** Read-only probe preferring su (the S9E is appCanSu, and /sys/class/gpio may be SELinux-guarded
     *  for the app uid), falling back to an app-uid shell. */
    private fun probe(cmd: String): String =
        Su.runOutput(cmd)?.takeIf { it.isNotBlank() } ?: exec(cmd)
}
