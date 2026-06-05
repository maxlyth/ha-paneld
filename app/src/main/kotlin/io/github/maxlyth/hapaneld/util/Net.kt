package io.github.maxlyth.hapaneld.util

import java.net.Inet4Address
import java.net.Inet6Address
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
