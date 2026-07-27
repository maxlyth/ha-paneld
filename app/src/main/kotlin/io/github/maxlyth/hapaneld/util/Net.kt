package io.github.maxlyth.hapaneld.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * First non-loopback IPv4 address of an up interface (works for both Wi-Fi and Ethernet panels),
 * or null if none. Used for the `configuration_url` ("Visit" link) and the info page.
 */
fun localIpv4(): String? {
    runCatching {
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress
            }
        }
    }
    return null
}

/**
 * First global-scope IPv6 address (skips loopback and link-local `fe80::`), with any zone id stripped,
 * or null if none. Covers both ULA (`fd00::/8`) and global-unicast addresses. Informational for the
 * info page — IPv4 stays the primary address for `configuration_url` and MQTT discovery.
 */
fun localIpv6(): String? {
    runCatching {
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet6Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    return addr.hostAddress?.substringBefore('%') // drop the %iface zone id
                }
            }
        }
    }
    return null
}

/**
 * Parse a request source string (a raw peer IP, or an info-page address value) into an [InetAddress],
 * or null if it does not parse. Strips any `%zone` id and a leading `/` (as `remoteAddress` /
 * `InetAddress.toString()` can carry) before resolving. Because these are already numeric IP literals
 * in practice, `getByName` does not perform a blocking DNS lookup. Parsing with [InetAddress] also unmaps
 * an IPv4-mapped IPv6 form (`::ffff:a.b.c.d`) to its IPv4 address, so a dual-stack bind cannot smuggle a
 * public IPv4 past the [isLanLocal] check.
 */
private fun parseAddress(host: String): InetAddress? =
    runCatching { InetAddress.getByName(host.substringBefore('%').removePrefix("/")) }.getOrNull()

/**
 * True if [addr] is a LAN-local address: loopback, link-local, RFC1918 site-local, wildcard/any-local, or
 * IPv6 ULA (`fc00::/7`). These are exactly the five predicates a globally-routable source must fail.
 */
private fun isLanLocal(addr: InetAddress): Boolean =
    addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isAnyLocalAddress ||
        (addr is Inet6Address && (addr.address[0].toInt() and 0xfe) == 0xfc) // fc00::/7 ULA

/**
 * True if [host] parses to a loopback address. Unparseable values return false. Used by the hardened-mode
 * gate that trusts only software already on the panel (loopback), where a broader LAN-local match would be
 * too permissive.
 */
fun isLoopbackPeer(host: String): Boolean = parseAddress(host)?.isLoopbackAddress ?: false

/**
 * True if [host] (the request's source IP) is a LAN-local address: loopback, RFC1918, link-local, IPv6 ULA,
 * or the wildcard address. Global/public sources — and unparseable values — return false → 403. See
 * [parseAddress] for the IPv4-mapped-IPv6 unmapping and zone/`/` stripping this relies on.
 */
fun isLocalSource(host: String): Boolean = parseAddress(host)?.let(::isLanLocal) ?: false

/**
 * True only for a parseable, globally-routable address (not loopback / RFC1918 / ULA / link-local /
 * any-local). Unparseable values (e.g. "—") return false → not routable. The exact negation of
 * [isLocalSource] for parseable input; both return false for unparseable input.
 */
fun isRoutable(host: String): Boolean = parseAddress(host)?.let { !isLanLocal(it) } ?: false
