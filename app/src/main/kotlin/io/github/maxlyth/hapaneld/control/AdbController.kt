package io.github.maxlyth.hapaneld.control

/**
 * Persistent network-adb control. Network adb (`adb tcpip 5555`) normally resets on reboot; setting
 * `persist.adb.tcp.port` makes it survive — handy for headless panels you only reach over the LAN.
 *
 * ⚠️ Security: this leaves a standing adb port open on the LAN. It is therefore opt-in (off unless you
 * enable it) and root-gated ([Su]). Toggling restarts adbd, which briefly drops an existing adb-over-tcp
 * connection (it re-establishes).
 */
class AdbController {

    /** Root present — required to set the props and restart adbd. */
    fun available(): Boolean = Su.available()

    /** True when network adb is set to persist across reboot (the OS `persist.` prop brings adbd up on
     *  the port at boot — ha-paneld does NOT re-apply it on boot, so this is genuine OS persistence). */
    fun isPersisted(): Boolean =
        Su.runOutput("getprop persist.adb.tcp.port 2>/dev/null")?.trim() == PORT

    /** True when network adb is live right now (runtime `service.` port) — reachable over the LAN now,
     *  but this alone does NOT survive a reboot; only [isPersisted] does. */
    fun isActive(): Boolean =
        Su.runOutput("getprop service.adb.tcp.port 2>/dev/null")?.trim() == PORT

    /** Enable/disable persistent network adb (and apply it now by restarting adbd). */
    fun set(on: Boolean): Boolean = if (on) {
        Su.run("setprop persist.adb.tcp.port $PORT; setprop service.adb.tcp.port $PORT; setprop ctl.restart adbd")
    } else {
        Su.run("setprop persist.adb.tcp.port \"\"; setprop service.adb.tcp.port \"\"; setprop ctl.restart adbd")
    }

    companion object {
        private const val PORT = "5555"
    }
}
