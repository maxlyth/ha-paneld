package io.github.maxlyth.hapaneld.dashboard

import io.github.maxlyth.hapaneld.HaAuthOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeDashboardLaunchCacheTest {

    private val owner = HaAuthOwner(
        url = "https://ha.example:8123",
        refreshToken = "refresh-a",
        clientId = "client-a",
        staticAccessToken = "",
    )

    @Test fun `every owner identity component changes the fingerprint`() {
        val base = HomeDashboardLaunchCache.ownerFingerprint(owner, "/office")
        assertEquals(base, HomeDashboardLaunchCache.ownerFingerprint(owner.copy(), "/office"))
        assertNotEquals(
            base,
            HomeDashboardLaunchCache.ownerFingerprint(owner.copy(url = "https://moved.example:8123"), "/office"),
        )
        assertNotEquals(
            base,
            HomeDashboardLaunchCache.ownerFingerprint(owner.copy(refreshToken = "refresh-b"), "/office"),
        )
        assertNotEquals(
            base,
            HomeDashboardLaunchCache.ownerFingerprint(owner.copy(clientId = "client-b"), "/office"),
        )
        assertNotEquals(
            base,
            HomeDashboardLaunchCache.ownerFingerprint(owner.copy(staticAccessToken = "static"), "/office"),
        )
        assertNotEquals(base, HomeDashboardLaunchCache.ownerFingerprint(owner, "/kitchen"))
    }

    @Test fun `the configured path is trimmed the way the resolution owner trims it`() {
        assertEquals(
            HomeDashboardLaunchCache.ownerFingerprint(owner, "/office"),
            HomeDashboardLaunchCache.ownerFingerprint(owner, "  /office  "),
        )
    }

    @Test fun `no credential material appears in the fingerprint`() {
        val fingerprint = HomeDashboardLaunchCache.ownerFingerprint(owner, "/office")
        assertEquals(16, fingerprint.length)
        assert(!fingerprint.contains("refresh-a"))
    }

    @Test fun `stored path guard accepts what a live resolution can produce`() {
        assertEquals("/lovelace", HomeDashboardLaunchCache.sanitizedStoredPath("/lovelace"))
        assertEquals("/energy", HomeDashboardLaunchCache.sanitizedStoredPath(" /energy "))
        assertEquals(
            "/office/view-2?kiosk#frag",
            HomeDashboardLaunchCache.sanitizedStoredPath("/office/view-2?kiosk#frag"),
        )
    }

    @Test fun `stored path guard fails closed on anything that is not an on-origin path`() {
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath(null))
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath(""))
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("   "))
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("lovelace")) // no leading slash
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("//evil.example/pwn")) // protocol-relative
        // A single-label host would PASS the root alphabet, so only the "//" rejection stops it —
        // and plain hostnames like this resolve on a LAN. The first battery run proved the dotted
        // variant above is also caught by the root check, masking a dropped "//" guard.
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("//evil/pwn"))
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("https://evil.example/")) // absolute URL
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("/\\evil.example")) // backslash → '/' in Chromium
        // A backslash past the root segment is rejected ONLY by the backslash clause — the root
        // alphabet never sees it. Same masking lesson as the "//" case above.
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("/office/\\evil"))
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("/office\u0000")) // control character
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("/Office")) // outside the root alphabet
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("/-office")) // root must start alphanumeric
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("/?kiosk")) // no root segment
        // The live resolver rejects dot segments and percent-encoded separators throughout the
        // SUFFIX, not only in the root. A stored row must be admitted by those same semantics: a
        // restored or hand-edited `/office/../auth` is exactly the escape a first-segment-only
        // check allowed through.
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("/office/../auth"))
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("/office/%2e%2e/auth"))
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("/office/%2fauth"))
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("/office/."))
        // Merely REDUCIBLE to a canonical route is not enough: we only ever store the canonical
        // form, so a trailing separator is a value we never wrote and is treated as corruption.
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("/office/"))
        assertNull(HomeDashboardLaunchCache.sanitizedStoredPath("office"))
        assertNull(
            HomeDashboardLaunchCache.sanitizedStoredPath(
                "/" + "a".repeat(HomeDashboardLaunchCache.MAX_STORED_PATH_CHARS),
            ),
        )
    }

    @Test fun `a route change inside one dashboard is not the same route`() {
        // Root-only comparison let a delayed correction overrule a user's view change.
        assert(HomeDashboardLaunchCache.sameDashboardRoute("/office", "/office"))
        assert(HomeDashboardLaunchCache.sameDashboardRoute("/office/", "/office"))
        assert(!HomeDashboardLaunchCache.sameDashboardRoute("/office/view-2", "/office"))
        assert(!HomeDashboardLaunchCache.sameDashboardRoute("/kitchen", "/office"))
    }

    @Test fun `correction convergence is judged by dashboard root and never proven by silence`() {
        assert(HomeDashboardLaunchCache.correctionConverged("/office", "/office"))
        assert(HomeDashboardLaunchCache.correctionConverged("/office/some-view", "/office"))
        assert(HomeDashboardLaunchCache.correctionConverged("/office", "/office/view-2?kiosk"))
        assert(!HomeDashboardLaunchCache.correctionConverged("/kitchen", "/office"))
        assert(!HomeDashboardLaunchCache.correctionConverged(null, "/office"))
        assert(!HomeDashboardLaunchCache.correctionConverged("", "/office"))
        assert(!HomeDashboardLaunchCache.correctionConverged("/", "/office"))
    }

    @Test fun `a completed live answer classifies confirm correct and clear distinctly`() {
        val live = EntityLearningProtocol.HomeDashboardResolution(
            "/office", EntityLearningProtocol.HomeDashboardSource.USER_DEFAULT,
        )
        assertEquals(
            HomeDashboardLaunchCache.RefreshOutcome.CONFIRMED,
            HomeDashboardLaunchCache.refreshOutcome("/office", live),
        )
        assertEquals(
            HomeDashboardLaunchCache.RefreshOutcome.CORRECTED,
            HomeDashboardLaunchCache.refreshOutcome("/kitchen", live),
        )
        assertEquals(
            HomeDashboardLaunchCache.RefreshOutcome.NO_LEGAL_DASHBOARDS,
            HomeDashboardLaunchCache.refreshOutcome(
                "/office", EntityLearningProtocol.HomeDashboardResolution(),
            ),
        )
    }
}
