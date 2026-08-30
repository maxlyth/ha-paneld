package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OriginGuard] — the cross-origin/CSRF gate for the unauthenticated :8888 surface. Pure logic. */
class OriginGuardTest {
    private val host = "192.168.1.50:8888"

    // --- ordinary reads remain outside the CSRF gate ---
    @Test fun getIsAlwaysAllowedEvenCrossOrigin() =
        assertTrue(OriginGuard.allowed("GET", "http://evil.example", null, host))

    @Test fun activeReadAllowsSameOriginBrowserAndHeaderlessLanClients() {
        assertTrue(
            OriginGuard.activeReadAllowed(
                null,
                "http://192.168.1.50:8888/configure",
                host,
                "same-origin",
            ),
        )
        assertTrue(OriginGuard.activeReadAllowed(null, null, host, null))
        assertTrue(OriginGuard.activeReadAllowed(null, null, host, "none"))
    }

    @Test fun activeReadRefusesOpaqueCrossOriginBrowserLoads() {
        assertFalse(OriginGuard.activeReadAllowed(null, "http://evil.example/page", host, "cross-site"))
        assertFalse(OriginGuard.activeReadAllowed(null, null, host, "same-site"))
        assertFalse(OriginGuard.activeReadAllowed("http://evil.example", null, host, null))
        assertFalse(
            OriginGuard.activeReadAllowed(
                null,
                null,
                host,
                null,
                "Mozilla/5.0 Chrome/120.0",
            ),
        )
    }

    @Test fun activeReadFailsClosedForMalformedBrowserMetadata() {
        assertFalse(OriginGuard.activeReadAllowed("garbage", null, host, "same-origin"))
        assertFalse(OriginGuard.activeReadAllowed("http://192.168.1.50:8888", null, null, "same-origin"))
    }

    // --- non-browser API clients (curl, HA rest_command) send no Origin/Referer ---
    @Test fun writeWithNoOriginOrRefererAllowed() =
        assertTrue("curl/automation write must pass", OriginGuard.allowed("POST", null, null, host))

    @Test fun writeWithBlankOriginAllowed() =
        assertTrue(OriginGuard.allowed("POST", "", "", host))

    // --- same-origin browser UI fetches ---
    @Test fun sameOriginWriteAllowed() =
        assertTrue(OriginGuard.allowed("POST", "http://192.168.1.50:8888", null, host))

    @Test fun sameOriginViaMdnsNameAllowed() =
        assertTrue(OriginGuard.allowed("POST", "http://panel.local:8888", null, "panel.local:8888"))

    @Test fun sameOriginRefererFallbackAllowed() =
        assertTrue("Referer used when Origin absent", OriginGuard.allowed("POST", null, "http://192.168.1.50:8888/configure", host))

    // --- the attack: a cross-origin page POSTing to the panel ---
    @Test fun crossOriginWriteRefused() =
        assertFalse("cross-site CSRF write must be refused", OriginGuard.allowed("POST", "http://evil.example", null, host))

    @Test fun crossOriginPutRefused() = assertFalse(OriginGuard.allowed("PUT", "http://evil.example", null, host))

    @Test fun crossOriginDeleteRefused() = assertFalse(OriginGuard.allowed("DELETE", "https://attacker.test:8443", null, host))

    @Test fun crossOriginDifferentPortRefused() =
        assertFalse("different port is a different origin", OriginGuard.allowed("POST", "http://192.168.1.50:9999", null, host))

    @Test fun crossOriginRefererRefused() =
        assertFalse(OriginGuard.allowed("POST", null, "http://evil.example/page", host))

    // --- degenerate inputs fail closed for writes ---
    @Test fun unparseableOriginRefused() = assertFalse(OriginGuard.allowed("POST", "garbage-no-scheme", null, host))

    @Test fun writeWithOriginButNoHostRefused() =
        assertFalse(OriginGuard.allowed("POST", "http://192.168.1.50:8888", null, null))

    // --- case-insensitive host compare ---
    @Test fun hostCaseInsensitive() =
        assertTrue(OriginGuard.allowed("POST", "http://Panel.Local:8888", null, "panel.local:8888"))

    // ===== hostAllowed (anti-DNS-rebinding) =====
    private val none = emptySet<String>()

    @Test fun ipv4LiteralAllowed() {
        assertTrue(OriginGuard.hostAllowed("192.168.1.50:8888", none))
        assertTrue(OriginGuard.hostAllowed("10.0.0.1", none))
    }

    @Test fun ipv6LiteralAllowed() {
        assertTrue("bracketed ipv6 with port", OriginGuard.hostAllowed("[fe80::1]:8888", none))
        assertTrue("bare ipv6", OriginGuard.hostAllowed("::1", none))
    }

    @Test fun localhostAllowed() = assertTrue(OriginGuard.hostAllowed("localhost:8888", none))

    @Test fun mdnsLocalAllowed() = assertTrue(OriginGuard.hostAllowed("kitchen-panel.local:8888", none))

    @Test fun missingHostAllowed() {
        assertTrue(OriginGuard.hostAllowed(null, none))
        assertTrue(OriginGuard.hostAllowed("", none))
    }

    @Test fun unknownDnsHostRefused() =
        assertFalse("a rebound public hostname must be refused", OriginGuard.hostAllowed("panel.attacker.example:8888", none))

    @Test fun configuredNameAllowed() =
        assertTrue(OriginGuard.hostAllowed("kitchen-panel.myhome.lan:8888", setOf("kitchen-panel.myhome.lan")))

    @Test fun configuredNameCaseInsensitive() =
        assertTrue(OriginGuard.hostAllowed("Kitchen-Panel.MyHome.LAN", setOf("kitchen-panel.myhome.lan")))

    @Test fun nonConfiguredNameStillRefused() =
        assertFalse(OriginGuard.hostAllowed("other.myhome.lan", setOf("kitchen-panel.myhome.lan")))

    @Test fun almostIpNotTreatedAsLiteral() =
        assertFalse("300 is not a valid octet — treat as a hostname, refuse", OriginGuard.hostAllowed("300.1.2.3", none))

    // ---- a browser that sends no Fetch Metadata at all (Safari < 16.4, and anything older) ---------

    private val safari = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/16.1 Safari/605.1.15"
    private val navigationAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    private val imageAccept = "image/avif,image/webp,image/png,image/svg+xml,*/*;q=0.8"

    /**
     * The defect this covers: typing the snapshot URL into such a browser was refused, and told the
     * reader it was cross-origin when the request carried no origin information at all. Reproduced on
     * two panels before it was changed — the same user agent plus `Sec-Fetch-Site: none` returned the
     * image, and without it returned 403.
     */
    @Test fun aNavigationFromABrowserWithoutFetchMetadataIsAdmitted() {
        assertTrue(
            "a typed URL must open: it asks for a document, which no subresource load does",
            OriginGuard.activeReadAllowed(null, null, host, null, safari, navigationAccept),
        )
    }

    @Test fun aSubresourceLoadFromThatSameBrowserStaysRefused() {
        assertFalse(
            "an <img> load is the case this guard exists for and must still be refused",
            OriginGuard.activeReadAllowed(null, null, host, null, safari, imageAccept),
        )
        assertFalse(
            "a bare wildcard is not a document request",
            OriginGuard.activeReadAllowed(null, null, host, null, safari, "*/*"),
        )
        assertFalse(
            "no Accept at all proves nothing, so it stays refused",
            OriginGuard.activeReadAllowed(null, null, host, null, safari, null),
        )
    }

    @Test fun theAcceptFallbackNeverOverridesFetchMetadata() {
        // A browser that does send Fetch Metadata is judged on it alone: a document Accept must not
        // rescue a cross-site read, or the fallback would become a way around the guard.
        assertFalse(OriginGuard.activeReadAllowed(null, null, host, "cross-site", safari, navigationAccept))
        assertFalse(OriginGuard.activeReadAllowed(null, null, host, "same-site", safari, navigationAccept))
        // And a stated origin that is not this host still loses, whatever it asked for.
        assertFalse(OriginGuard.activeReadAllowed("http://evil.example", null, host, null, safari, navigationAccept))
    }

    @Test fun headerLessAutomationIsUnaffected() {
        assertTrue("curl and fleet tooling send no user agent of this shape", OriginGuard.activeReadAllowed(null, null, host, null, null, null))
        assertTrue(OriginGuard.activeReadAllowed(null, null, host, null, "ha-paneld-fleet/1.0", null))
    }
}
