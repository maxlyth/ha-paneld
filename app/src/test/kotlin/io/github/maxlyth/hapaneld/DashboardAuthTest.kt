package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.HaLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The renderer's token-selection / lazy-refresh policy (the panel side of external_auth). Pure: a fake
 * refresher stands in for the HA `/auth/token` call, so every branch is exercised without a network.
 */
class DashboardAuthTest {
    private val NOW = 1_000_000L
    private fun neverRefresh(u: String, r: String): HaLink.TokenSet? {
        throw AssertionError("must not refresh in this case")
    }

    @Test fun `no url yields no session`() {
        val r = DashboardAuth.resolve("", "tok", "", 0, NOW, ::neverRefresh)
        assertNull(r.session); assertNull(r.persist)
    }

    @Test fun `static token is returned as-is with a long life and never refreshed`() {
        val r = DashboardAuth.resolve("https://ha", "llat", "", 0, NOW, ::neverRefresh)
        assertEquals("llat", r.session!!.accessToken)
        assertEquals(DashboardAuth.STATIC_TTL_SEC, r.session!!.expiresInSec)
        assertNull("static model never persists a refresh", r.persist)
    }

    @Test fun `static model with blank token fails closed`() {
        assertNull(DashboardAuth.resolve("https://ha", "", "", 0, NOW, ::neverRefresh).session)
    }

    @Test fun `refresh model reuses a still-fresh access token`() {
        // expiry comfortably beyond now+skew → reuse, no refresh.
        val r = DashboardAuth.resolve("https://ha", "acc", "refr", NOW + 3600, NOW, ::neverRefresh)
        assertEquals("acc", r.session!!.accessToken)
        assertEquals(3600L, r.session!!.expiresInSec)
        assertNull(r.persist)
    }

    @Test fun `refresh model mints a new token when expired and persists it`() {
        var called = false
        val refresher = { _: String, rt: String ->
            called = true; assertEquals("refr", rt); HaLink.TokenSet("new-acc", 1800L)
        }
        val r = DashboardAuth.resolve("https://ha", "old", "refr", NOW - 10, NOW, refresher)
        assertTrue(called)
        assertEquals("new-acc", r.session!!.accessToken)
        assertEquals(1800L, r.session!!.expiresInSec)
        assertEquals("new-acc" to (NOW + 1800L), r.persist)
    }

    @Test fun `near-expiry within skew triggers a refresh`() {
        val r = DashboardAuth.resolve("https://ha", "old", "refr", NOW + 30, NOW, { _, _ -> HaLink.TokenSet("n", 1800L) })
        assertEquals("n", r.session!!.accessToken) // 30s < REFRESH_SKEW_SEC(60) → refreshed
    }

    @Test fun `unknown expiry (0) forces a refresh`() {
        val r = DashboardAuth.resolve("https://ha", "acc", "refr", 0, NOW, { _, _ -> HaLink.TokenSet("n", 1800L) })
        assertEquals("n", r.session!!.accessToken)
    }

    @Test fun `refresh failure falls back to a still-usable cached token`() {
        // Cached token has 30s left (< skew so a refresh was attempted) but refresh returned null → reuse it.
        val r = DashboardAuth.resolve("https://ha", "cached", "refr", NOW + 30, NOW, { _, _ -> null })
        assertEquals("cached", r.session!!.accessToken)
        assertEquals(30L, r.session!!.expiresInSec)
        assertNull("no persist on a failed refresh", r.persist)
    }

    @Test fun `refresh failure with a fully-expired token fails closed`() {
        val r = DashboardAuth.resolve("https://ha", "dead", "refr", NOW - 100, NOW, { _, _ -> null })
        assertNull(r.session)
    }
}
