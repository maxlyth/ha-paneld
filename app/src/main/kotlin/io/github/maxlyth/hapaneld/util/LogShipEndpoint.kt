package io.github.maxlyth.hapaneld.util

import java.net.URI
import java.util.Locale

/**
 * Pure, unit-testable log-sink addressing — the sibling of [BrokerEndpoint] for the remote
 * log-shipping destination, kept out of `logship/LogShipper` so it can be tested without a socket.
 *
 * One parser serves the value a user types into the Configure form, the value stored in preferences,
 * and the value a config bundle restores, so a scheme typed into the host box, a retired protocol
 * name, and a `host:port` shorthand all resolve identically wherever they are read.
 */
object LogShipEndpoint {
    /** RFC5426: one datagram per message. What a stock syslog collector listens for on port 514. */
    const val SYSLOG_UDP = "syslog-udp"

    /** RFC5424 frames over a stream, newline-delimited per RFC6587 non-transparent framing. */
    const val SYSLOG_TCP = "syslog-tcp"

    /** NDJSON batches POSTed to `http://host:port/`. */
    const val HTTP = "http"

    /** UDP, because the port default is 514 and that is a UDP port on every stock collector. */
    const val DEFAULT_PROTOCOL = SYSLOG_UDP

    val PROTOCOLS = listOf(SYSLOG_UDP, SYSLOG_TCP, HTTP)

    /**
     * Retired spellings, resolved before an enum match so an older config bundle still imports.
     * `syslog` predates the UDP transport and meant TCP, so it must keep meaning TCP — a panel that
     * was deliberately shipping over TCP does not change transport merely because it was upgraded.
     */
    val ALIASES = mapOf(
        "syslog" to SYSLOG_TCP,
        "tcp" to SYSLOG_TCP,
        "udp" to SYSLOG_UDP,
    )

    /** A resolved sink address: bare [host] (IPv6 brackets/zone stripped), [port], and [protocol]. */
    data class Endpoint(val host: String, val port: Int, val protocol: String)

    /** Canonical protocol for any stored or user-supplied value; unrecognised input takes the default. */
    fun protocol(raw: String): String = known(raw) ?: DEFAULT_PROTOCOL

    /**
     * The host as it must appear inside a URL. [resolve] strips IPv6 brackets because that is what
     * `InetAddress` and `Socket` want, but a URL needs them back or `http://::1:514/` is ambiguous
     * nonsense — the parser cannot tell the address's colons from the port separator.
     */
    fun urlHost(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    /** Short transport word for status text: `udp://host:514` reads better than `syslog-udp://…`. */
    fun scheme(protocol: String): String = when (protocol) {
        SYSLOG_UDP -> "udp"
        HTTP -> "http"
        else -> "tcp"
    }

    /**
     * Resolve what the user actually typed into the free-text host box against the separately stored
     * port and protocol. People paste a scheme or a `host:port` in there, and taking that literally is
     * precisely how `udp://collector` became a hostname lookup whose failure message was the hostname
     * itself — a warning that named the destination and no fault.
     *
     * A recognised scheme wins over the stored protocol, because typing one is a deliberate statement
     * about transport. An unrecognised scheme is ignored rather than allowed to reset the protocol to
     * the default. A value [URI] will not parse — an underscore in a hostname, or a meaningful path —
     * is passed through exactly as typed rather than silently rewritten, so it fails at resolution
     * quoting what the user actually entered.
     */
    fun resolve(host: String, port: Int, protocol: String): Endpoint {
        val raw = host.trim()
        val typedScheme = if ("://" in raw) raw.substringBefore("://") else ""
        val authority = (if (typedScheme.isEmpty()) raw else raw.substringAfter("://")).trim()
        val resolvedProtocol = known(typedScheme) ?: protocol(protocol)
        if (authority.isEmpty()) return Endpoint("", port, resolvedProtocol)

        val verbatim = Endpoint(authority.removeSuffix("/"), port, resolvedProtocol)
        val uri = runCatching { URI("logsink://$authority") }.getOrNull() ?: return verbatim
        val addressOnly = uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath in setOf(null, "", "/")
        val parsedHost = uri.host?.takeIf { addressOnly } ?: return verbatim

        val bare = if (parsedHost.length >= 2 && parsedHost.startsWith("[") && parsedHost.endsWith("]")) {
            parsedHost.substring(1, parsedHost.length - 1)
        } else {
            parsedHost
        }
        val resolvedPort = if (uri.port in 1..65535) uri.port else port
        return Endpoint(bare.substringBefore('%'), resolvedPort, resolvedProtocol)
    }

    /** The canonical form of a recognised protocol or scheme word, or null if it names none. */
    private fun known(raw: String): String? {
        val v = raw.trim().lowercase(Locale.ROOT)
        if (v.isEmpty()) return null
        return ALIASES[v] ?: PROTOCOLS.firstOrNull { it == v }
    }
}
