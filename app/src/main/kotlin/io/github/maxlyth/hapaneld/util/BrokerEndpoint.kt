package io.github.maxlyth.hapaneld.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Pure, unit-testable broker-address handling — the IPv6-first-class core of the MQTT connect path,
 * kept out of [io.github.maxlyth.hapaneld.MqttBridge] so it can be tested without HiveMQ or a device.
 *
 * Two concerns:
 *  - [parse]: turn a broker spec (`host`, `host:port`, `tcp://host:port`, `ssl://[v6]:port`, …) into a
 *    clean (host, port) — critically **stripping the `[...]` brackets** that `java.net.URI.getHost()`
 *    returns for an IPv6 literal (HiveMQ's `serverHost()` wants the bare address).
 *  - [select]: happy-eyeballs-style family choice over resolved addresses, so ha-paneld can prefer one
 *    family and fall back to the other when a link (e.g. a flaky IPv6 path) won't hold.
 */
object BrokerEndpoint {
    const val DEFAULT_PORT = 1883

    /** Parse a broker spec into (host, port), or null if unusable. Host has any IPv6 `[...]` brackets and
     *  `%zone` suffix stripped; port defaults to [DEFAULT_PORT] when absent. A bare `host`/`[v6]`/`v4`
     *  (no scheme) is accepted by prefixing a synthetic `tcp://`. */
    fun parse(raw: String): Pair<String, Int>? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val uri = runCatching { URI(if (s.contains("://")) s else "tcp://$s") }.getOrNull() ?: return null
        var host = uri.host ?: return null
        if (host.length >= 2 && host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        host = host.substringBefore('%')          // drop any IPv6 scope/zone id
        if (host.isEmpty()) return null
        val port = if (uri.port in 1..65535) uri.port else DEFAULT_PORT
        return host to port
    }

    /** Happy-eyeballs family selection: return the first address of the preferred family, else the first
     *  of the other family, else null. Deterministic (first-of-family) so reconnect behaviour is stable. */
    fun select(candidates: List<InetAddress>, preferIpv4: Boolean): InetAddress? {
        val v4 = candidates.filterIsInstance<Inet4Address>()
        val v6 = candidates.filterIsInstance<Inet6Address>()
        val (first, second) = if (preferIpv4) v4 to v6 else v6 to v4
        return first.firstOrNull() ?: second.firstOrNull()
    }

    /** Format an address for HiveMQ's `serverHost()` — bare form, no brackets (HiveMQ adds them). Strips
     *  the leading `/` and any `%zone` from [InetAddress.toString]/`hostAddress`. */
    fun hostString(addr: InetAddress): String = addr.hostAddress.substringBefore('%')
}
