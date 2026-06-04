package io.github.maxlyth.hapaneld.util

import java.net.Inet4Address
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
