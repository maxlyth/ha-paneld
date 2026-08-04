package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.URL

/**
 * The redirect a server chooses is the one nobody approved, so this is the rule that decides whether
 * the panel may be sent there. Addresses are supplied directly, so none of this touches the network.
 */
class OutboundDestinationTest {
    private fun at(vararg literals: String): List<InetAddress> =
        literals.map { InetAddress.getByName(it) }

    @Test fun publicAddressesAreRoutableAndEveryPrivateFormIsNot() {
        assertTrue(OutboundDestination.publiclyRoutable(at("93.184.216.34")))
        assertTrue(OutboundDestination.publiclyRoutable(at("2606:2800:220:1:248:1893:25c8:1946")))

        assertFalse("loopback", OutboundDestination.publiclyRoutable(at("127.0.0.1")))
        assertFalse("IPv6 loopback", OutboundDestination.publiclyRoutable(at("::1")))
        assertFalse("RFC1918 /8", OutboundDestination.publiclyRoutable(at("10.1.2.3")))
        assertFalse("RFC1918 /12", OutboundDestination.publiclyRoutable(at("172.16.5.4")))
        assertFalse("RFC1918 /16", OutboundDestination.publiclyRoutable(at("192.168.1.10")))
        assertFalse("link-local, which is where cloud metadata lives", OutboundDestination.publiclyRoutable(at("169.254.169.254")))
        assertFalse("IPv6 link-local", OutboundDestination.publiclyRoutable(at("fe80::1")))
        assertFalse("IPv6 unique-local", OutboundDestination.publiclyRoutable(at("fd00::1")))
        assertFalse("wildcard", OutboundDestination.publiclyRoutable(at("0.0.0.0")))
        assertFalse("multicast", OutboundDestination.publiclyRoutable(at("239.1.1.1")))

        assertFalse("a name that resolves to nothing is not admitted", OutboundDestination.publiclyRoutable(emptyList()))
        // A name answering with both must not be usable to reach the private one.
        assertFalse(
            "a split answer must be refused wholesale",
            OutboundDestination.publiclyRoutable(at("93.184.216.34", "127.0.0.1")),
        )
    }

    @Test fun redirectsAreAdmittedOnlyToPublicHostsOrBackToTheApprovedOne() {
        val resolve: (String) -> List<InetAddress> = { host ->
            when (host) {
                "cdn.example" -> at("93.184.216.34")
                "internal.example" -> at("10.1.2.5")
                "mirror.lan" -> at("192.168.1.50")
                else -> emptyList()
            }
        }

        // The ordinary case this feature depends on: APK sites bounce through public CDN hops.
        assertTrue(OutboundDestination.admitsRedirect("apk.example", URL("https://cdn.example/a.apk"), resolve))

        // The finding: an approved public link must not be able to steer the panel inside the network.
        assertFalse(OutboundDestination.admitsRedirect("apk.example", URL("https://internal.example/a.apk"), resolve))

        // A LAN-hosted mirror redirecting within itself still works, because the operator approved it.
        assertTrue(OutboundDestination.admitsRedirect("mirror.lan", URL("https://mirror.lan/files/a.apk"), resolve))
        assertTrue(
            "host comparison is case-insensitive",
            OutboundDestination.admitsRedirect("Mirror.LAN", URL("https://mirror.lan/files/a.apk"), resolve),
        )
        // ...but it must not become a licence to reach a DIFFERENT internal host.
        assertFalse(OutboundDestination.admitsRedirect("mirror.lan", URL("https://internal.example/a.apk"), resolve))

        assertFalse("an unresolvable hop", OutboundDestination.admitsRedirect("apk.example", URL("https://nowhere.example/a.apk"), resolve))
        assertFalse(
            "a resolver failure must refuse, not admit",
            OutboundDestination.admitsRedirect("apk.example", URL("https://cdn.example/a.apk")) { error("resolver down") },
        )
    }
}
