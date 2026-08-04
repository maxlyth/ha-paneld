package io.github.maxlyth.hapaneld.util

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

/**
 * Decides whether the panel may follow a redirect it was never asked to follow.
 *
 * An operator naming a URL is an intent the panel can confirm and act on. A redirect target is named
 * by the *server*, and nobody approved it — so following one blindly turns an approved "fetch this
 * APK" into "connect to wherever this server points you", including addresses only the panel can
 * reach. That is the whole SSRF exposure of an APK-from-URL feature.
 *
 * The rule is deliberately narrow, because APK distribution genuinely depends on redirect chains
 * (APKMirror, APKPure and GitHub releases all bounce through one or more CDN hops, and refusing
 * redirects would leave the feature working for almost nobody):
 *
 *  - a hop back to the **same host the operator already approved** is allowed, so a LAN-hosted mirror
 *    that redirects within itself keeps working;
 *  - any other hop must resolve **entirely** to publicly routable addresses.
 *
 * A hop that resolves to nothing, or to a mix including a private address, is refused — a name that
 * answers with both a public and a loopback address must not be usable to reach the loopback one.
 *
 * **Known residual:** the check resolves the name and the connection resolves it again, so a DNS
 * answer that changes in between is not caught. Closing that needs connecting to the checked literal
 * address while still presenting the original name for TLS, which `HttpURLConnection` cannot express
 * without a hand-written HTTPS client. The exposure that remains is bounded: the fetched bytes are
 * only ever inspected and shown, and installing them requires its own separate approval.
 */
internal object OutboundDestination {

    /** True when every resolved address is routable on the public internet. */
    fun publiclyRoutable(addresses: List<InetAddress>): Boolean =
        addresses.isNotEmpty() && addresses.all { address ->
            !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress &&
                !address.isAnyLocalAddress &&
                !address.isMulticastAddress &&
                !isUniqueLocalIpv6(address)
        }

    /** Whether the panel may follow [target] while fetching something approved for [approvedHost]. */
    fun admitsRedirect(
        approvedHost: String,
        target: URL,
        resolve: (String) -> List<InetAddress>,
    ): Boolean {
        val host = target.host.orEmpty()
        if (host.isBlank()) return false
        if (host.equals(approvedHost, ignoreCase = true)) return true
        val resolved = runCatching { resolve(host) }.getOrElse { return false }
        return publiclyRoutable(resolved)
    }

    /** `fc00::/7`. Java has no predicate for IPv6 unique-local, and `isSiteLocalAddress` misses it. */
    private fun isUniqueLocalIpv6(address: InetAddress): Boolean =
        address is Inet6Address && (address.address.firstOrNull()?.toInt()?.and(0xFE) == 0xFC)
}
