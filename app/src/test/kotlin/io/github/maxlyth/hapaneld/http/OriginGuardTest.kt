package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OriginGuard] — the cross-origin/CSRF gate for the unauthenticated :8888 surface. Pure logic. */
class OriginGuardTest {
    private val host = "192.168.1.50:8888"

    // --- reads are never guarded ---
    @Test fun getIsAlwaysAllowedEvenCrossOrigin() =
        assertTrue(OriginGuard.allowed("GET", "http://evil.example", null, host))

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
}
