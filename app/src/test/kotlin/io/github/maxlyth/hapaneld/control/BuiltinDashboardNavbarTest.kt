package io.github.maxlyth.hapaneld.control

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [BuiltinDashboard] is a process-global object; reset the navbar-handoff state before AND after each
 *  test so it can't leak into (or out of) other test classes sharing the JVM. */
class BuiltinDashboardNavbarTest {
    @Before @After fun reset() {
        BuiltinDashboard.setForegroundListener(null)
        BuiltinDashboard.setNavbarRevealHandler(null)
        BuiltinDashboard.foreground = false
    }

    @Test fun foregroundListenerFiresOnChangeOnly() {
        val events = mutableListOf<Boolean>()
        BuiltinDashboard.setForegroundListener { events.add(it) }
        BuiltinDashboard.foreground = true // change → fire
        BuiltinDashboard.foreground = true // no change → silent (redundant onResume+onTopResumed)
        BuiltinDashboard.foreground = false // change → fire
        assertEquals(listOf(true, false), events)
    }

    @Test fun foregroundSetToSameValueIsSilent() {
        val events = mutableListOf<Boolean>()
        BuiltinDashboard.setForegroundListener { events.add(it) }
        BuiltinDashboard.foreground = false // already false from reset → no change
        assertTrue(events.isEmpty())
    }

    @Test fun clearedForegroundListenerGetsNoCallbacks() {
        val events = mutableListOf<Boolean>()
        BuiltinDashboard.setForegroundListener { events.add(it) }
        BuiltinDashboard.foreground = true
        BuiltinDashboard.setForegroundListener(null)
        BuiltinDashboard.foreground = false // no listener → nothing recorded
        assertEquals(listOf(true), events)
    }

    @Test fun revealHandlerSlotSemantics() {
        assertFalse("no handler → not enabled", BuiltinDashboard.navbarSwipeEnabled)
        BuiltinDashboard.requestNavbarReveal() // no handler → no-op, must not throw

        var reveals = 0
        BuiltinDashboard.setNavbarRevealHandler { reveals++ }
        assertTrue("handler set → enabled", BuiltinDashboard.navbarSwipeEnabled)
        BuiltinDashboard.requestNavbarReveal()
        BuiltinDashboard.requestNavbarReveal()
        assertEquals(2, reveals)

        BuiltinDashboard.setNavbarRevealHandler(null)
        assertFalse("handler cleared → not enabled", BuiltinDashboard.navbarSwipeEnabled)
        BuiltinDashboard.requestNavbarReveal() // cleared → no-op
        assertEquals(2, reveals)
    }
}
