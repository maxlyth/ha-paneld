package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.platform.RootShell
import org.json.JSONObject

/** One self-consistent, request-scoped view of the Zigbee gateway. */
internal data class ZigbeeObservation(
    val probeSucceeded: Boolean,
    val present: Boolean,
    val managed: Boolean,
    val running: Boolean,
    val driver: String?,
    val role: String?,
) {
    val status: String
        get() {
            if (!probeSucceeded) return "gateway · status unavailable"
            if (!present) return "none"
            val roleSuffix = role?.let { " · $it" }.orEmpty()
            return "${driver ?: "gateway"} · ${if (running) "running" else "stopped"}$roleSuffix"
        }
}

/**
 * Zigbee gateway control for panels that ship a Sonoff-style gateway (currently the NSPanel Pro — a
 * Silicon Labs EFR32 EZSP NCP on `/dev/ttyS5`). The gateway dir comes from [DeviceProfile.zigbeeGatewayDir]
 * (`/vendor/bin/siliconlabs_host` on NSPanel Pro); a profile with no dir makes this controller inert.
 *
 * Two gateway LAYOUTS exist in the wild and we handle both (verified 2026-06-08 on an 86P and a 120P):
 *   - **NSPanelTools-managed** — NSPPT side-loaded a Sonoff package: `run_guard_process.sh` launchers +
 *     a `package_version` marker (e.g. `sonoff-v3.5.4:sonoff-3.5.0`). The footprint PERSISTS after NSPPT
 *     is uninstalled. Start/stop via `run_guard_process.sh [stop]`.
 *   - **vendor-native** — stock firmware ships only `guard_process.sh` (a while-true supervisor), no
 *     `run_` launchers, no `package_version`. Boot-started by a vendor hook (reparented to init). It has
 *     **no stop argument**, so disable = kill the guard (so it can't respawn) then `zgateway`.
 *     (Note: on 120P/3.7.1 the vendor guard has a CPU-spin defect — disabling it is a real win.)
 *
 * zgateway is controlled over a LOCAL anonymous mosquitto broker on `127.0.0.1:1883`:
 *   - role status:  `zigbee/system/network-role/information`  →  `{"role":"Repeater"|"Coordinator"}`
 *   - role switch:  `zigbee/system/network-role/switch`        ←  `{"role":"Repeater"}`
 * Everything here needs root via [Su].
 */
class ZigbeeController(profile: DeviceProfile, private val root: RootShell = Su) {

    private val dir: String? = profile.zigbeeGatewayDir

    /**
     * Read all cheap gateway metadata through one bounded root command. Fixed framing distinguishes a
     * valid absence from partial, oversized, reordered, or otherwise malformed output, which remains
     * unknown. Role is a separate bounded broker read, attempted only when this frame proves it running.
     */
    internal fun observe(includeRole: Boolean, directSuReady: Boolean = true): ZigbeeObservation {
        val gatewayDir = dir ?: return ABSENT_OBSERVATION
        if (!directSuReady) return UNKNOWN_OBSERVATION
        val metadata = parseMetadata(
            root.runOutputIsolatedBounded(
                metadataCommand(gatewayDir),
                maxBytes = METADATA_MAX_BYTES,
                timeoutMs = METADATA_TIMEOUT_MS,
            ),
        ) ?: return UNKNOWN_OBSERVATION
        return if (includeRole && metadata.present && metadata.running) {
            metadata.copy(role = readRole(gatewayDir))
        } else {
            metadata
        }
    }

    /** True when this panel has a drivable Zigbee gateway in EITHER layout, or one already running. */
    fun present(): Boolean {
        val dir = dir ?: return false
        // Keep the low-overhead control-path checks independent of the management observation. In
        // particular, MQTT reconciliation must not wait for the diagnostic lane just to read a PID.
        return managed() || fileExists("$dir/guard_process.sh") || fileExists("$dir/zgateway") || running()
    }

    /** NSPanelTools-managed install (has the run_ launchers) vs vendor-native (guard_process.sh only). */
    private fun managed(): Boolean = dir != null && fileExists("$dir/run_guard_process.sh")

    private fun fileExists(path: String): Boolean =
        root.runOutput("ls $path 2>/dev/null")?.trim()?.isNotEmpty() == true

    /** True when the zgateway host process is running (the radio is in use). */
    fun running(): Boolean = root.runOutput("pidof zgateway")?.trim()?.isNotEmpty() == true

    /** Current network role from the local broker, or null if unreadable (gateway/broker down). */
    fun role(): String? = dir?.let(::readRole)

    private fun readRole(gatewayDir: String): String? {
        val out = root.runOutputIsolatedBounded(
            "export LD_LIBRARY_PATH=$gatewayDir; $gatewayDir/mosquitto_sub -h 127.0.0.1 -p 1883 -i hapaneld_zr " +
                "-t zigbee/system/network-role/information -C 1 -W 3",
            maxBytes = ROLE_MAX_BYTES,
            timeoutMs = ROLE_TIMEOUT_MS,
        )?.trim() ?: return null
        return runCatching {
            (JSONObject(out).opt("role") as? String)
                ?.takeIf { role -> role.isNotEmpty() && role.length <= ROLE_MAX_CHARS && role.none(Char::isISOControl) }
        }.getOrNull()
    }

    /** A guard supervisor is already running (matched by exact cmdline, excluding our own shells). */
    private fun guardRunning(): Boolean =
        root.runOutput("ps -A -o ARGS= 2>/dev/null | grep guard_process.sh | grep -v ' -c ' | grep -v grep")
            ?.trim()?.isNotEmpty() == true

    /**
     * Start the gateway. **Idempotent** — if the radio or a guard is ALREADY running (e.g. the vendor
     * boot-started it), do nothing: starting a second guard makes both fight over the gateway's fixed MQTT
     * client-id (`rkguardsh_zigbee`), thrashing the connection into a CPU spin (root cause of the 120P hog).
     * NSPPT-managed: `run_guard_process.sh`. Vendor-native: launch `guard_process.sh` DETACHED (it's a
     * while-true watchdog — must not block the su call). Then best-effort nudge the role to Repeater.
     */
    fun enable(): Boolean {
        val dir = dir ?: return false
        val alreadyRunning = running()
        if (!alreadyRunning && guardRunning()) return true // startup already in flight — never duplicate it
        val ok = alreadyRunning || if (managed()) root.run("sh $dir/run_guard_process.sh")
        else root.run("nohup sh $dir/guard_process.sh >/dev/null 2>&1 &")
        runCatching {
            val r = readRole(dir)
            if (!r.equals(ROLE_REPEATER, ignoreCase = true)) setRole(ROLE_REPEATER)
        }
        return ok
    }

    /**
     * Stop the gateway, freeing the radio (and, on the buggy vendor guard, the CPU). NSPPT-managed:
     * `run_guard_process.sh stop`. Vendor-native has no stop arg → kill the guard first (so it can't
     * respawn zgateway) then zgateway itself.
     */
    fun disable(): Boolean {
        val dir = dir ?: return false
        // NSPPT-managed: the clean stop script. Vendor-native (no stop arg): kill the guard — it's the
        // supervisor + CPU hog + respawner, and it's killable (shell domain). Match it by full cmdline via
        // ps, EXCLUDING our own su/sh shells (`-c`) and grep, so we don't kill the shell running this (the
        // bug pkill -f had: its cmdline contains "guard_process.sh"). Then best-effort SIGKILL the radio —
        // but on stock firmware zgateway runs in the init domain and the vendor `su` returns EPERM, so this
        // is a no-op there and the radio persists until reboot (a firmware limit surfaced in status).
        return if (managed()) root.run("sh $dir/run_guard_process.sh stop")
        else root.run(
            "for p in \$(ps -A -o PID=,ARGS= 2>/dev/null | grep guard_process.sh | grep -v ' -c ' | " +
                "grep -v grep | awk '{print \$1}'); do kill -9 \$p 2>/dev/null; done; " +
                "killall -9 zgateway 2>/dev/null; true",
        )
    }

    /**
     * Drive the gateway to [desiredOn], whoever started it. Vendor firmware boot-starts the gateway
     * independently, so persisting an explicit "off" means actively stopping it; "on" starts it if down.
     * Caller gates this on the user having EXPLICITLY configured the switch — we must never disable a
     * vendor-started gateway a user relies on just because our default is off.
     */
    fun reconcile(desiredOn: Boolean): Boolean {
        if (dir == null || !present()) return false
        val run = running()
        return when {
            desiredOn -> enable()
            !desiredOn && run -> disable()
            else -> true
        }
    }

    /** Publish a role switch to the local broker. Allowlist the role so an arbitrary string can never
     *  be interpolated into the shell command. */
    private fun setRole(role: String) {
        val dir = dir ?: return
        val r = when (role.lowercase()) {
            "coordinator" -> "Coordinator"
            "repeater" -> "Repeater"
            else -> return
        }
        root.run(
            "export LD_LIBRARY_PATH=$dir; $dir/mosquitto_pub -h 127.0.0.1 -p 1883 -i hapaneld_zp " +
                "-t zigbee/system/network-role/switch -m '{\"role\":\"$r\"}'",
        )
    }

    companion object {
        private val ABSENT_OBSERVATION = ZigbeeObservation(
            probeSucceeded = true,
            present = false,
            managed = false,
            running = false,
            driver = null,
            role = null,
        )
        private val UNKNOWN_OBSERVATION = ABSENT_OBSERVATION.copy(probeSucceeded = false)
        private const val METADATA_HEADER = "HAPANELD_ZIGBEE_V1"
        private const val METADATA_FOOTER = "HAPANELD_ZIGBEE_END"
        private const val METADATA_MAX_BYTES = 1_024L
        private const val METADATA_TIMEOUT_MS = 3_500L
        private const val PACKAGE_VERSION_MAX_BYTES = 120
        private const val ROLE_REPEATER = "Repeater"
        private const val ROLE_MAX_CHARS = 64
        private const val ROLE_MAX_BYTES = 4_096L
        private const val ROLE_TIMEOUT_MS = 3_500L

        private fun metadataCommand(dir: String): String =
            "printf '$METADATA_HEADER\\n'; " +
                "if [ -e '$dir/run_guard_process.sh' ]; then printf 'managed=1\\n'; " +
                "else printf 'managed=0\\n'; fi; " +
                "if [ -e '$dir/guard_process.sh' ]; then printf 'guard=1\\n'; " +
                "else printf 'guard=0\\n'; fi; " +
                "if [ -e '$dir/zgateway' ]; then printf 'binary=1\\n'; " +
                "else printf 'binary=0\\n'; fi; " +
                "if pidof zgateway >/dev/null 2>&1; then printf 'running=1\\n'; " +
                "else printf 'running=0\\n'; fi; " +
                "package_version=\$(head -c ${PACKAGE_VERSION_MAX_BYTES + 1} '$dir/package_version' " +
                "2>/dev/null); printf 'package=%s\\n' \"\$package_version\"; " +
                "printf '$METADATA_FOOTER\\n'"

        private fun parseMetadata(raw: String?): ZigbeeObservation? {
            raw ?: return null
            val framed = raw.removeSuffix("\n")
            val lines = framed.split('\n').map { it.removeSuffix("\r") }
            if (lines.size != 7 || lines.first() != METADATA_HEADER || lines.last() != METADATA_FOOTER) {
                return null
            }
            fun flag(index: Int, name: String): Boolean? = when (lines[index]) {
                "$name=0" -> false
                "$name=1" -> true
                else -> null
            }
            val managed = flag(1, "managed") ?: return null
            val guard = flag(2, "guard") ?: return null
            val binary = flag(3, "binary") ?: return null
            val running = flag(4, "running") ?: return null
            val packageLine = lines[5]
            if (!packageLine.startsWith("package=")) return null
            val packageVersion = packageLine.removePrefix("package=")
            if (packageVersion.toByteArray(Charsets.UTF_8).size > PACKAGE_VERSION_MAX_BYTES ||
                packageVersion.any(Char::isISOControl)
            ) return null
            val present = managed || guard || binary || running
            val rawDriver = packageVersion.trim().takeIf(String::isNotEmpty)
            val driver = if (present) rawDriver?.let(::driverLabel) ?: "vendor-native" else null
            return ZigbeeObservation(
                probeSucceeded = true,
                present = present,
                managed = managed,
                running = running,
                driver = driver,
                role = null,
            )
        }

        private fun driverLabel(version: String): String {
            val tail = version.substringAfter(':', version)
            val type = tail.substringBefore('-', tail)
            val release = tail.substringAfter('-', "")
            return if (release.isEmpty()) type else "$type $release"
        }
    }
}
