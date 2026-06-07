package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.device.DeviceProfile
import org.json.JSONObject

/**
 * Zigbee gateway control for panels that ship a Sonoff-style gateway (currently the NSPanel Pro — a
 * Silicon Labs EFR32 EZSP NCP on `/dev/ttyS5`). The gateway's directory is **not hardcoded here**: it
 * comes from the active [DeviceProfile] ([DeviceProfile.zigbeeGatewayDir]); a profile with no gateway
 * dir (every non-NSPanel-Pro panel) makes this controller inert.
 *
 * The radio is driven by the **manufacturer's** `zgateway` host binary in that dir — the package is
 * eWeLink/Sonoff's own, versioned per firmware (e.g. `sonoff-v3.5.4`) and **side-loaded by NSPanelTools
 * onto firmware that didn't ship it**, not seaky's code. It's kept alive by `guard_process.sh`
 * (a 5-second supervisor loop). NOTE: on an NSPanelTools-provisioned panel a persistent hook
 * boot-starts `guard_process.sh` — it survives the APK removal and reparents to init, but isn't a
 * standard `/system`|`/vendor/etc/init` service (verified 2026-06-08). [enable] also starts it on
 * demand via `run_guard_process.sh`; MqttBridge's boot-restore starts it on connect only when nothing
 * else has. zgateway is controlled over a LOCAL mosquitto broker on
 * `127.0.0.1:1883`, which is anonymous (the `password_file` line is commented out in `mosquitto.conf`),
 * so no credentials are needed:
 *
 *   - role status:  `zigbee/system/network-role/information`  →  `{"role":"Repeater"|"Coordinator"}`
 *   - role switch:  `zigbee/system/network-role/switch`        ←  `{"role":"Repeater"}`
 *
 * "Repeater" is router mode (extends an existing mesh — the supported sweet spot); "Coordinator" forms
 * its own network. The role persists in the NCP's NVM across restarts. Everything here needs root via
 * [Su] (the bundled mosquitto client needs `LD_LIBRARY_PATH`; the lifecycle scripts touch `/vendor`).
 */
class ZigbeeController(profile: DeviceProfile = DeviceProfile.detect()) {

    /** Gateway dir from the device profile, or null on panels without a managed Zigbee gateway. */
    private val dir: String? = profile.zigbeeGatewayDir

    /**
     * True when this panel has a Zigbee gateway we can actually drive. Gated on the **guard script we
     * invoke** existing — not just the `package_version` marker, since a configured panel may have lost
     * only the marker file. This also correctly EXCLUDES panels left with an empty gateway dir and an
     * orphaned `zgateway` process: there, ON could not restart the gateway and it wouldn't survive a
     * reboot.
     */
    fun present(): Boolean {
        val dir = dir ?: return false
        return fileExists("$dir/run_guard_process.sh")
    }

    /**
     * Driver label, e.g. `"sonoff 3.7.1"`, from the package marker `package_version`
     * (format `<type>-v<artifact>:<type>-<zstack>`). Falls back to a generic label when the gateway is
     * drivable ([present]) but the marker is missing; null when there is no gateway at all.
     */
    fun driver(): String? {
        val dir = dir ?: return null
        val raw = Su.runOutput("cat $dir/package_version 2>/dev/null")?.trim()
        if (!raw.isNullOrEmpty()) {
            val tail = raw.substringAfter(':', raw) // "sonoff-3.7.1"
            val type = tail.substringBefore('-', tail)
            val ver = tail.substringAfter('-', "")
            return if (ver.isEmpty()) type else "$type $ver"
        }
        return if (present()) "gateway present (version unknown)" else null
    }

    private fun fileExists(path: String): Boolean =
        Su.runOutput("ls $path 2>/dev/null")?.trim()?.isNotEmpty() == true

    /** True when the zgateway host process is running (the radio is in use). */
    fun running(): Boolean = Su.runOutput("pidof zgateway")?.trim()?.isNotEmpty() == true

    /** Current network role from the local broker, or null if unreadable (gateway/broker down). */
    fun role(): String? {
        val dir = dir ?: return null
        val out = Su.runOutput(
            "export LD_LIBRARY_PATH=$dir; $dir/mosquitto_sub -h 127.0.0.1 -p 1883 -i hapaneld_zr " +
                "-t zigbee/system/network-role/information -C 1 -W 3",
        )?.trim() ?: return null
        return runCatching { JSONObject(out).optString("role").ifEmpty { null } }.getOrNull()
    }

    /**
     * Enable the router: start the guard supervisor (which brings up mosquitto + zgateway), then
     * best-effort nudge the role to Repeater — but only if the broker is already up AND the role
     * differs, to avoid a needless network leave/rejoin. The role persists in NVM, so a panel that was
     * previously a router comes back as a router on start regardless.
     */
    fun enable(): Boolean {
        val dir = dir ?: return false
        val ok = Su.run("sh $dir/run_guard_process.sh")
        runCatching {
            val r = role()
            if (r != null && !r.equals(ROLE_REPEATER, ignoreCase = true)) setRole(ROLE_REPEATER)
        }
        return ok
    }

    /** Disable the router: stop the guard supervisor and the whole zstack, freeing the radio. */
    fun disable(): Boolean {
        val dir = dir ?: return false
        return Su.run("sh $dir/run_guard_process.sh stop")
    }

    /** Publish a role switch to the local broker. Allowlist the role so an arbitrary string can never
     *  be interpolated into the shell command (defensive — callers only pass constants today). */
    private fun setRole(role: String) {
        val dir = dir ?: return
        val r = when (role.lowercase()) {
            "coordinator" -> "Coordinator"
            "repeater" -> "Repeater"
            else -> return
        }
        Su.run(
            "export LD_LIBRARY_PATH=$dir; $dir/mosquitto_pub -h 127.0.0.1 -p 1883 -i hapaneld_zp " +
                "-t zigbee/system/network-role/switch -m '{\"role\":\"$r\"}'",
        )
    }

    /** One-line status for the info page: "sonoff 3.7.1 · running · Repeater", or "none". */
    fun status(): String {
        val d = driver() ?: return "none"
        val run = if (running()) "running" else "stopped"
        val r = role()?.let { " · $it" } ?: ""
        return "$d · $run$r"
    }

    companion object {
        private const val ROLE_REPEATER = "Repeater"
    }
}
