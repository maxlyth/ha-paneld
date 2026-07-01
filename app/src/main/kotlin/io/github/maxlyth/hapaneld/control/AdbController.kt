package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.Config

/**
 * Persistent network-adb control. Network adb (`adb tcpip 5555`) normally resets on reboot; setting
 * `persist.adb.tcp.port` is *supposed* to make it survive — but some panel firmwares strip that prop at
 * boot, so relying on the OS alone is unreliable. Instead ha-paneld records its own **intent** (the
 * switch) in [Config] and **re-asserts** adb-tcp at boot/reconnect ([reassert]) while the switch is on.
 *
 * Two states are distinguished for the UI: adb persisted BY ha-paneld vs adb merely **active** because
 * something else turned it on. On OFF, ha-paneld only tears adb down if it was the one that enabled it —
 * it never disables an adb port another mechanism started.
 *
 * ⚠️ Security: this leaves a standing adb port open on the LAN — opt-in and root-gated ([Su]). Toggling
 * restarts adbd, briefly dropping an existing adb-over-tcp connection (it re-establishes).
 */
class AdbController(private val config: Config) {

    /** Root present — required to set the props and restart adbd. */
    fun available(): Boolean = Su.available()

    /** ha-paneld's persist intent = the switch state. ha-paneld re-asserts adb-tcp at boot/reconnect
     *  while this is true, so it survives firmwares that drop `persist.adb.tcp.port`. */
    fun isPersisted(): Boolean = config.networkAdbEnabled

    /** True when network adb is live right now (runtime `service.` port) — reachable over the LAN now.
     *  On its own this does NOT survive a reboot. */
    fun isActive(): Boolean =
        Su.runOutput("getprop service.adb.tcp.port 2>/dev/null")?.trim() == PORT

    /**
     * Enable/disable ha-paneld-persistent network adb and apply it now (restarting adbd). ON records the
     * intent + brings adb up. OFF clears the intent, and only tears adb down if ha-paneld was the one
     * persisting it — an adb port another mechanism opened is left running.
     */
    fun set(on: Boolean): Boolean {
        if (on) {
            config.setNetworkAdbEnabled(true)
            return apply()
        }
        val weOwnedIt = config.networkAdbEnabled
        config.setNetworkAdbEnabled(false)
        return if (weOwnedIt)
            Su.run("setprop persist.adb.tcp.port \"\"; setprop service.adb.tcp.port \"\"; setprop ctl.restart adbd")
        else true // externally-active adb — don't kill it
    }

    /**
     * Boot/reconnect re-assert: if ha-paneld is persisting network adb but it isn't live (a firmware
     * that stripped the prop at boot, or adbd died), bring it back. Idempotent no-op when already active,
     * intent is off, or there's no root.
     */
    fun reassert() {
        if (!config.networkAdbEnabled || !available() || isActive()) return
        apply()
    }

    private fun apply(): Boolean =
        Su.run("setprop persist.adb.tcp.port $PORT; setprop service.adb.tcp.port $PORT; setprop ctl.restart adbd")

    /** UI status — ha-paneld-persisted vs merely externally-active vs off. */
    fun statusText(): String = when {
        config.networkAdbEnabled -> "persistent (ha-paneld)"
        isActive() -> "active (external — not ha-paneld)"
        else -> "off"
    }

    companion object {
        private const val PORT = "5555"
    }
}
