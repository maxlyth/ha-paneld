package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.HaLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The renderer's token-selection / lazy-refresh policy (the panel side of external_auth). Pure: a fake
 * refresher stands in for the HA `/auth/token` call, so every branch is exercised without a network.
 * The transient-vs-rejected split matters on an unattended panel: a flaky network moment must never be
 * treated as a revoked credential (which would dead-end the dashboard until an admin re-provisions).
 */
class DashboardAuthTest {
    private val NOW = 1_000_000L
    private fun neverRefresh(u: String, r: String): HaLink.Refresh {
        throw AssertionError("must not refresh in this case")
    }
    private fun success(token: String, ttl: Long = 1800L): HaLink.Refresh =
        HaLink.Refresh.Success(HaLink.TokenSet(token, ttl))

    private val OWNER = DashboardAuth.CredentialOwner("https://ha", "old", "refresh", NOW - 1, "client")

    @Test fun `no url yields no session`() {
        val r = DashboardAuth.resolve("", "tok", "", 0, NOW, false, ::neverRefresh)
        assertNull(r.session); assertNull(r.persist)
    }

    @Test fun `static token is returned as-is with a long life and never refreshed`() {
        val r = DashboardAuth.resolve("https://ha", "llat", "", 0, NOW, false, ::neverRefresh)
        assertEquals("llat", r.session!!.accessToken)
        assertEquals(DashboardAuth.STATIC_TTL_SEC, r.session!!.expiresInSec)
        assertNull("static model never persists a refresh", r.persist)
    }

    @Test fun `static model with blank token fails closed`() {
        assertNull(DashboardAuth.resolve("https://ha", "", "", 0, NOW, false, ::neverRefresh).session)
    }

    @Test fun `refresh model reuses a still-fresh access token`() {
        // expiry comfortably beyond now+skew → reuse, no refresh.
        val r = DashboardAuth.resolve("https://ha", "acc", "refr", NOW + 3600, NOW, false, ::neverRefresh)
        assertEquals("acc", r.session!!.accessToken)
        assertEquals(3600L, r.session!!.expiresInSec)
        assertNull(r.persist)
    }

    @Test fun `refresh model mints a new token when expired and persists it`() {
        var called = false
        val refresher = { _: String, rt: String ->
            called = true; assertEquals("refr", rt); success("new-acc")
        }
        val r = DashboardAuth.resolve("https://ha", "old", "refr", NOW - 10, NOW, false, refresher)
        assertTrue(called)
        assertEquals("new-acc", r.session!!.accessToken)
        assertEquals(1800L, r.session!!.expiresInSec)
        assertEquals("new-acc" to (NOW + 1800L), r.persist)
    }

    @Test fun `near-expiry within skew triggers a refresh`() {
        val r = DashboardAuth.resolve("https://ha", "old", "refr", NOW + 30, NOW, false, { _, _ -> success("n") })
        assertEquals("n", r.session!!.accessToken) // 30s < REFRESH_SKEW_SEC(60) → refreshed
    }

    @Test fun `unknown expiry (0) forces a refresh`() {
        val r = DashboardAuth.resolve("https://ha", "acc", "refr", 0, NOW, false, { _, _ -> success("n") })
        assertEquals("n", r.session!!.accessToken)
    }

    // --- transient refresh failures (HA down / network blip): never a revocation ---

    @Test fun `transient failure falls back to a still-usable cached token`() {
        // Cached token has 30s left (< skew so a refresh was attempted) but the refresh failed
        // transiently → reuse it, and do NOT report revoked.
        val r = DashboardAuth.resolve("https://ha", "cached", "refr", NOW + 30, NOW, false, { _, _ -> HaLink.Refresh.Transient })
        assertEquals("cached", r.session!!.accessToken)
        assertEquals(30L, r.session!!.expiresInSec)
        assertNull("no persist on a failed refresh", r.persist)
        assertFalse("a transient failure is not a rejection", r.rejected)
    }

    @Test fun `transient failure with a fully-expired token fails closed but not rejected`() {
        val r = DashboardAuth.resolve("https://ha", "dead", "refr", NOW - 100, NOW, false, { _, _ -> HaLink.Refresh.Transient })
        assertNull(r.session)
        assertFalse("HA being down must never read as rejected login settings", r.rejected)
    }

    // --- terminal rejection: fail closed AND say so ---

    @Test fun `rejected refresh fails closed and reports rejection`() {
        val r = DashboardAuth.resolve("https://ha", "cached", "refr", NOW + 30, NOW, false, { _, _ -> HaLink.Refresh.Rejected })
        assertNull("rejected login settings must not fall back to the cached token", r.session)
        assertTrue(r.rejected)
    }

    @Test fun `success never reports rejection`() {
        val r = DashboardAuth.resolve("https://ha", "old", "refr", NOW - 10, NOW, false, { _, _ -> success("n") })
        assertFalse(r.rejected)
    }

    // --- force flag (frontend demands a fresh token after a 401) ---

    @Test fun `force refreshes even when the cached token looks fresh`() {
        // Cached token has plenty of clock-life left, but HA rejected it → force must refresh, not reuse.
        val r = DashboardAuth.resolve("https://ha", "stale-but-unexpired", "refr", NOW + 3600, NOW, true,
            { _, _ -> success("fresh") })
        assertEquals("fresh", r.session!!.accessToken)
        assertEquals("fresh" to (NOW + 1800L), r.persist)
    }

    @Test fun `force with transient failure fails closed - never re-hands the rejected token`() {
        val r = DashboardAuth.resolve("https://ha", "rejected", "refr", NOW + 3600, NOW, true, { _, _ -> HaLink.Refresh.Transient })
        assertNull("must not re-hand the rejected token on a forced refresh", r.session)
        assertFalse("transient stays non-rejected even under force", r.rejected)
    }

    @Test fun `force with terminal rejection reports rejection`() {
        val r = DashboardAuth.resolve("https://ha", "rejected", "refr", NOW + 3600, NOW, true, { _, _ -> HaLink.Refresh.Rejected })
        assertNull(r.session)
        assertTrue(r.rejected)
    }

    @Test fun `completed refresh is discarded after renderer or credential ownership changes`() {
        val refreshed = DashboardAuth.Result(
            DashboardAuth.Session("new", 1800),
            "new" to (NOW + 1800),
        )
        assertNull(DashboardAuth.retainIfOwned(OWNER, OWNER, false, refreshed).session)
        assertNull(
            DashboardAuth.retainIfOwned(
                OWNER,
                OWNER.copy(clientId = "replacement-client"),
                true,
                refreshed,
            ).session,
        )
        assertEquals(refreshed, DashboardAuth.retainIfOwned(OWNER, OWNER, true, refreshed))
    }
}
