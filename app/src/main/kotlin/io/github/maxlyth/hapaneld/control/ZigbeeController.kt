package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.platform.RootShell
import org.json.JSONObject

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
class ZigbeeController(profile: DeviceProfile = DeviceProfile.detect(), private val root: RootShell = Su) {

    private val dir: String? = profile.zigbeeGatewayDir

    /** NSPanelTools-managed install (has the run_ launchers) vs vendor-native (guard_process.sh only). */
    private fun managed(): Boolean = dir != null && fileExists("$dir/run_guard_process.sh")

    /** True when this panel has a drivable Zigbee gateway in EITHER layout, or one already running. */
    fun present(): Boolean {
        val dir = dir ?: return false
        // The `zgateway` binary is the layout-agnostic presence marker. NSPPT installs add
        // run_guard_process.sh (managed); old vendor firmware has guard_process.sh; 4.x firmware ships
        // /vendor/bin/siliconlabs_host with run.sh + the zgateway binary (none of the guard markers) —
        // so check the binary itself, else 4.x panels report "none" despite having a working gateway.
        return managed() || fileExists("$dir/guard_process.sh") || fileExists("$dir/zgateway") || running()
    }

    /**
     * Driver label: `"sonoff 3.5.0"` from the NSPPT `package_version` marker, or `"vendor-native"` when
     * there's a gateway but no marker (stock firmware); null when there is no gateway at all.
     */
    fun driver(): String? {
        val dir = dir ?: return null
        val raw = root.runOutput("cat $dir/package_version 2>/dev/null")?.trim()
        if (!raw.isNullOrEmpty()) {
            val tail = raw.substringAfter(':', raw)
            val type = tail.substringBefore('-', tail)
            val ver = tail.substringAfter('-', "")
            return if (ver.isEmpty()) type else "$type $ver"
        }
        return if (present()) "vendor-native" else null
    }

    private fun fileExists(path: String): Boolean =
        root.runOutput("ls $path 2>/dev/null")?.trim()?.isNotEmpty() == true

    /** True when the zgateway host process is running (the radio is in use). */
    fun running(): Boolean = root.runOutput("pidof zgateway")?.trim()?.isNotEmpty() == true

    /** Current network role from the local broker, or null if unreadable (gateway/broker down). */
    fun role(): String? {
        val dir = dir ?: return null
        val out = root.runOutput(
            "export LD_LIBRARY_PATH=$dir; $dir/mosquitto_sub -h 127.0.0.1 -p 1883 -i hapaneld_zr " +
                "-t zigbee/system/network-role/information -C 1 -W 3",
        )?.trim() ?: return null
        return runCatching { JSONObject(out).optString("role").ifEmpty { null } }.getOrNull()
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
        if (running() || guardRunning()) return true // already up — never spawn a duplicate guard
        val ok = if (managed()) root.run("sh $dir/run_guard_process.sh")
        else root.run("nohup sh $dir/guard_process.sh >/dev/null 2>&1 &")
        runCatching {
            val r = role()
            if (r != null && !r.equals(ROLE_REPEATER, ignoreCase = true)) setRole(ROLE_REPEATER)
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
        // is a no-op there and the radio persists until reboot (a firmware limit, surfaced in status()).
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
            desiredOn && !run -> enable()
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

    /** One-line status for the info page: "sonoff 3.5.0 · running · Repeater" / "vendor-native · running"
     *  / "none". */
    fun status(): String {
        if (!present()) return "none"
        val d = driver() ?: "gateway"
        val run = if (running()) "running" else "stopped"
        val r = role()?.let { " · $it" } ?: ""
        return "$d · $run$r"
    }

    companion object {
        private const val ROLE_REPEATER = "Repeater"
    }
}
