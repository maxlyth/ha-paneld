package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Advertiser health reporting — the decisions that keep a broken mDNS responder from failing silently.
 *
 * A panel whose JmDNS bound before DHCP (loopback), whose bind went stale after an address change, or
 * whose responder died keeps answering HTTP and keeps serving its last known roster. Nothing else in the
 * process notices, so these two pure helpers are what drive the rebuild and the operator-facing banner.
 */
class MdnsHealthTest {

    private fun health(
        advertising: Boolean = true,
        boundIp: String? = "10.0.4.109",
        lanIp: String? = "10.0.4.109",
        responderDead: Boolean = false,
    ) = MdnsHealth(advertising, boundIp, lanIp, responderDead)

    // --- mdnsHealthWarning ---
    @Test fun healthyAdvertiserWarnsAboutNothing() {
        assertNull(mdnsHealthWarning(health()))
    }

    @Test fun noLanAddressIsNotAnMdnsWarning() {
        // Before DHCP there is nothing to advertise; the panel's connectivity cards already cover it, and
        // start() legitimately defers rather than binding loopback.
        assertNull(mdnsHealthWarning(health(advertising = false, boundIp = null, lanIp = null)))
    }

    @Test fun stoppedResponderWarns() {
        val warning = mdnsHealthWarning(health(advertising = false, boundIp = null))
        assertNotNull(warning)
        assertTrue(warning!!.contains("not running"))
    }

    @Test fun deadResponderWarnsEvenWhileJmdnsLooksPresent() {
        // The exact failure that stranded panels: the object is still there, the roster still serves, but
        // the responder is gone and the panel has silently left every other panel's switcher.
        val warning = mdnsHealthWarning(health(advertising = true, responderDead = true))
        assertNotNull(warning)
        assertTrue(warning!!.contains("not running"))
    }

    @Test fun staleBindReportsBothAddresses() {
        val warning = mdnsHealthWarning(health(boundIp = "127.0.0.1", lanIp = "10.0.4.109"))
        assertNotNull(warning)
        assertTrue(warning!!.contains("127.0.0.1"))
        assertTrue(warning.contains("10.0.4.109"))
    }

    @Test fun aDeadResponderOutranksAStaleBind() {
        // Both are true after a loopback bind is detected; the rebuild message is the useful one.
        val warning = mdnsHealthWarning(health(boundIp = "127.0.0.1", responderDead = true))
        assertTrue(warning!!.contains("not running"))
    }

    // --- nextMissedSweeps ---
    @Test fun seeingSelfClearsTheMissStreak() {
        assertEquals(0, nextMissedSweeps(previous = 2, sawSelf = true))
        assertEquals(0, nextMissedSweeps(previous = 0, sawSelf = true))
    }

    @Test fun missingSelfAccumulates() {
        assertEquals(1, nextMissedSweeps(previous = 0, sawSelf = false))
        assertEquals(3, nextMissedSweeps(previous = 2, sawSelf = false))
    }

    @Test fun oneMissedSweepDoesNotCondemnAHealthyAdvertiser() {
        // A congested sweep (whole-fleet restart) must not flap a working responder: the streak has to
        // survive a single miss, which is why the rebuild threshold is several consecutive sweeps.
        var streak = 0
        streak = nextMissedSweeps(streak, sawSelf = false)
        streak = nextMissedSweeps(streak, sawSelf = true)
        assertEquals(0, streak)
    }
}
