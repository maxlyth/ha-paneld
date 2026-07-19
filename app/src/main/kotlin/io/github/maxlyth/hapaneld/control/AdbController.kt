package io.github.maxlyth.hapaneld.control

import android.webkit.WebView
import io.github.maxlyth.hapaneld.Config
import java.io.InputStream
import java.util.concurrent.TimeUnit

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

    /** Whether network adb is live or enabled right now, or null when the relevant properties cannot
     *  all be proven inactive. The unprivileged reads detect Developer-options/external adb even on
     *  devices without root; root cross-checks reads that are empty, unavailable, or malformed for the
     *  app UID. */
    fun activeState(allowRootCrossCheck: Boolean = true): Boolean? = networkAdbActiveState(
        directRead = ::readSystemPropertyDirect,
        rootRead = if (allowRootCrossCheck) ::readSystemPropertyRoot else null,
    )

    /** True when network adb is known to be active or enabled over the LAN now. */
    fun isActive(allowRootCrossCheck: Boolean = true): Boolean = activeState(allowRootCrossCheck) == true

    /**
     * Enable/disable ha-paneld-persistent network adb and apply it now (restarting adbd). ON records the
     * intent + brings adb up. OFF clears the intent, and only tears adb down if ha-paneld was the one
     * persisting it — an adb port another mechanism opened is left running.
     */
    @Synchronized fun set(on: Boolean): Boolean {
        if (on) {
            if (!config.setNetworkAdbEnabled(true)) return false
            return apply()
        }
        // This switch is also the explicit admission control for the built-in renderer's DevTools
        // socket. Closing the global WebView debugging surface before the potentially slow root teardown
        // prevents a previously-created WebView from remaining debuggable until its next rebuild.
        WebView.setWebContentsDebuggingEnabled(false)
        return disableOwnedNetworkAdb(
            owned = config.networkAdbEnabled,
            teardown = { Su.run(networkAdbDisableCommand()) },
            clearOwnership = { config.setNetworkAdbEnabled(false) },
        )
    }

    /**
     * Boot/reconnect re-assert: if ha-paneld is persisting network adb but it isn't live (a firmware
     * that stripped the prop at boot, or adbd died), bring it back. Idempotent no-op when already active,
     * intent is off, or there's no root.
     */
    @Synchronized fun reassert() {
        if (!config.networkAdbEnabled || !available() || isActive()) return
        apply()
    }

    private fun apply(): Boolean =
        Su.run(networkAdbEnableCommand())

    /** UI status — ha-paneld-persisted vs merely externally-active vs off. */
    fun statusText(): String {
        val active = activeState()
        return when {
            config.networkAdbEnabled -> "persistent (ha-paneld)"
            active == true -> "active (external — not ha-paneld)"
            active == null -> "unknown"
            else -> "off"
        }
    }

    companion object {
        internal const val PORT = "5555"
    }
}

/**
 * Prefer app-readable Android properties so an unrooted panel can still detect externally enabled adb.
 * Android may return an empty value when SELinux hides a property from the app UID, so empty, failed,
 * and malformed direct reads are cross-checked as root. A known-positive signal wins; false is returned
 * only when every classic-TCP and wireless-debugging signal is authoritatively inactive. Returning null
 * lets security-sensitive callers fail closed when that cannot be established.
 */
internal fun networkAdbActiveState(
    directRead: (String) -> String?,
    rootRead: ((String) -> String?)?,
): Boolean? {
    val directStates = NETWORK_ADB_PROPERTIES.associateWith { property ->
        property.parse(directRead(property.name), emptyIsInactive = false)
    }
    if (directStates.values.any { it == true }) return true

    var unknown = false
    for (property in NETWORK_ADB_PROPERTIES) {
        val state = directStates.getValue(property)
            ?: rootRead?.let { property.parse(it(property.name), emptyIsInactive = true) }
        when (state) {
            true -> return true
            false -> Unit
            null -> unknown = true
        }
    }
    return if (unknown) null else false
}

private fun readSystemPropertyDirect(name: String): String? {
    val fixedName = NETWORK_ADB_PROPERTIES.firstOrNull { it.name == name }?.name ?: return null
    return runCatching {
        val process = ProcessBuilder(SYSTEM_GETPROP, fixedName)
            .redirectErrorStream(true)
            .start()
        try {
            if (!process.waitFor(PROPERTY_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroy()
                if (!process.waitFor(PROPERTY_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
                return@runCatching null
            }
            if (process.exitValue() != 0) return@runCatching null
            readBounded(process.inputStream, MAX_PROPERTY_OUTPUT_BYTES)?.trim()
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }.getOrNull()
}

private fun readSystemPropertyRoot(name: String): String? {
    val command = networkAdbRootReadCommand(name) ?: return null
    return Su.runOutputIsolatedBounded(
        command,
        maxBytes = MAX_PROPERTY_OUTPUT_BYTES.toLong(),
        timeoutMs = PROPERTY_READ_TIMEOUT_MS,
    )?.trim()
}

private fun readBounded(input: InputStream, maxBytes: Int): String? {
    val bytes = ByteArray(maxBytes + 1)
    var size = 0
    while (size < bytes.size) {
        val read = input.read(bytes, size, bytes.size - size)
        if (read < 0) break
        if (read == 0) continue
        size += read
    }
    if (size > maxBytes) return null
    return String(bytes, 0, size, Charsets.UTF_8)
}

private const val SYSTEM_GETPROP = "/system/bin/getprop"
private const val PROPERTY_READ_TIMEOUT_MS = 1_000L
private const val PROPERTY_DESTROY_GRACE_MS = 100L
private const val MAX_PROPERTY_OUTPUT_BYTES = 128

internal const val SERVICE_ADB_TCP_PORT_PROPERTY = "service.adb.tcp.port"
internal const val PERSIST_ADB_TCP_PORT_PROPERTY = "persist.adb.tcp.port"
internal const val SERVICE_ADB_LISTEN_ADDRS_PROPERTY = "service.adb.listen_addrs"
internal const val SERVICE_ADB_TLS_PORT_PROPERTY = "service.adb.tls.port"
internal const val PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY = "persist.adb.tls_server.enable"

private enum class NetworkAdbPropertyKind { PORT, ENABLED, LISTEN_ADDRESSES }

private data class NetworkAdbProperty(
    val name: String,
    val kind: NetworkAdbPropertyKind,
) {
    fun parse(raw: String?, emptyIsInactive: Boolean): Boolean? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty()) return if (emptyIsInactive) false else null
        return when (kind) {
            NetworkAdbPropertyKind.PORT -> value.toIntOrNull()?.let { port ->
                when {
                    port in 1..MAX_NETWORK_PORT -> true
                    port <= 0 -> false
                    else -> null
                }
            }
            NetworkAdbPropertyKind.ENABLED -> when (value) {
                "1" -> true
                "0" -> false
                else -> null
            }
            // AOSP gives this property precedence over every port property and passes each comma-
            // separated socket spec directly to adbd. Treat any non-empty value as active: attempting
            // to duplicate every present and future socket-spec grammar here could misclassify an
            // unfamiliar but valid endpoint as off. A malformed value therefore also fails closed.
            NetworkAdbPropertyKind.LISTEN_ADDRESSES -> true
        }
    }
}

private val NETWORK_ADB_PROPERTIES = listOf(
    NetworkAdbProperty(SERVICE_ADB_LISTEN_ADDRS_PROPERTY, NetworkAdbPropertyKind.LISTEN_ADDRESSES),
    NetworkAdbProperty(SERVICE_ADB_TCP_PORT_PROPERTY, NetworkAdbPropertyKind.PORT),
    NetworkAdbProperty(PERSIST_ADB_TCP_PORT_PROPERTY, NetworkAdbPropertyKind.PORT),
    NetworkAdbProperty(SERVICE_ADB_TLS_PORT_PROPERTY, NetworkAdbPropertyKind.PORT),
    NetworkAdbProperty(PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY, NetworkAdbPropertyKind.ENABLED),
)

/** Only fixed, enumerated property names can enter the privileged shell command. */
internal fun networkAdbRootReadCommand(name: String): String? =
    NETWORK_ADB_PROPERTIES.firstOrNull { it.name == name }
        ?.let { "$SYSTEM_GETPROP ${it.name}" }

private const val MAX_NETWORK_PORT = 65_535

internal fun networkAdbEnableCommand(): String = adbTransitionCommand(
    "setprop persist.adb.tcp.port ${AdbController.PORT}",
    "setprop service.adb.tcp.port ${AdbController.PORT}",
    "setprop ctl.restart adbd",
)

internal fun networkAdbDisableCommand(): String = adbTransitionCommand(
    "setprop persist.adb.tcp.port \"\"",
    "setprop service.adb.tcp.port \"\"",
    "setprop ctl.restart adbd",
)

/**
 * RootShell reports the exit status of the complete shell expression. A semicolon-separated sequence
 * would therefore report only the final command and could mask an earlier failed property write.
 */
private fun adbTransitionCommand(vararg commands: String): String = commands.joinToString(" && ")

/** An OFF failure must retain ownership intent so the next command/reconnect retries the teardown. */
internal fun disableOwnedNetworkAdb(
    owned: Boolean,
    teardown: () -> Boolean,
    clearOwnership: () -> Unit,
): Boolean {
    if (!owned) return true // externally-active adb — don't kill it
    if (!teardown()) return false
    clearOwnership()
    return true
}
