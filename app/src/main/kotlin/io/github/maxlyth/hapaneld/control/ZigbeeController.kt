package io.github.maxlyth.hapaneld.control

import org.json.JSONObject

/**
 * Zigbee gateway control for the Sonoff NSPanel Pro — the only fleet panel with a Zigbee radio
 * (a Silicon Labs EFR32 running EZSP NCP firmware on `/dev/ttyS5`).
 *
 * The radio is driven by Sonoff's `zgateway` host binary in `/vendor/bin/siliconlabs_host/`, kept
 * alive by `guard_process.sh` (a 5-second supervisor loop, boot-started). zgateway is controlled
 * over a LOCAL mosquitto broker on `127.0.0.1:1883`, which is anonymous (the `password_file` line is
 * commented out in `mosquitto.conf`), so no credentials are needed:
 *
 *   - role status:  `zigbee/system/network-role/information`  →  `{"role":"Repeater"|"Coordinator"}`
 *   - role switch:  `zigbee/system/network-role/switch`        ←  `{"role":"Repeater"}`
 *
 * "Repeater" is router mode (extends an existing mesh — the supported sweet spot); "Coordinator"
 * forms its own network. The role persists in the NCP's NVM across restarts.
 *
 * Everything here needs root: the bundled `mosquitto_pub`/`mosquitto_sub` need `LD_LIBRARY_PATH`
 * pointing at their private libs, and the lifecycle scripts touch `/vendor`. NSPanel Pro's toolbox
 * `su` is reachable from the app sandbox (unlike the TPA10), so [Su] covers it; on a panel without
 * the package or without su, every method degrades to "absent" and the capability simply doesn't
 * appear in HA.
 */
class ZigbeeController {

    /**
     * True when this panel has a Zigbee gateway we can actually drive. Gated on the **guard script we
     * invoke** ([GUARD]) existing — not just the `package_version` marker, since a configured panel may
     * have lost only the marker file. This also correctly EXCLUDES panels left with an empty
     * `siliconlabs_host` dir and an orphaned `zgateway` process (as some vendor-app teardowns leave
     * behind): there, ON could not restart the gateway and it wouldn't survive a reboot.
     */
    fun present(): Boolean = fileExists(GUARD)

    /**
     * Driver label, e.g. `"sonoff 3.7.1"`, from the package marker `package_version`
     * (format `<type>-v<artifact>:<type>-<zstack>`). Falls back to a generic label when the gateway is
     * drivable ([present]) but the marker is missing; null when there is no gateway at all.
     */
    fun driver(): String? {
        val raw = Su.runOutput("cat $DIR/package_version 2>/dev/null")?.trim()
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
        val out = Su.runOutput(
            "$ENV $DIR/mosquitto_sub -h 127.0.0.1 -p 1883 -i hapaneld_zr " +
                "-t zigbee/system/network-role/information -C 1 -W 3",
        )?.trim() ?: return null
        return runCatching { JSONObject(out).optString("role").ifEmpty { null } }.getOrNull()
    }

    /**
     * Enable the router: start the guard supervisor (which brings up mosquitto + zgateway), then
     * best-effort nudge the role to Repeater — but only if the broker is already up AND the role
     * differs, to avoid a needless network leave/rejoin. The role persists in NVM, so a panel that
     * was previously a router comes back as a router on start regardless.
     */
    fun enable(): Boolean {
        val ok = Su.run("sh $GUARD")
        runCatching {
            val r = role()
            if (r != null && !r.equals(ROLE_REPEATER, ignoreCase = true)) setRole(ROLE_REPEATER)
        }
        return ok
    }

    /** Disable the router: stop the guard supervisor and the whole zstack, freeing the radio. */
    fun disable(): Boolean = Su.run("sh $GUARD stop")

    /** Publish a role switch to the local broker. */
    private fun setRole(role: String) {
        Su.run(
            "$ENV $DIR/mosquitto_pub -h 127.0.0.1 -p 1883 -i hapaneld_zp " +
                "-t zigbee/system/network-role/switch -m '{\"role\":\"$role\"}'",
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
        private const val DIR = "/vendor/bin/siliconlabs_host"
        private const val GUARD = "$DIR/run_guard_process.sh"

        // The bundled mosquitto client links its own libssl/libcrypto/libmosquitto in DIR.
        private const val ENV = "export LD_LIBRARY_PATH=$DIR;"
        private const val ROLE_REPEATER = "Repeater"
    }
}
