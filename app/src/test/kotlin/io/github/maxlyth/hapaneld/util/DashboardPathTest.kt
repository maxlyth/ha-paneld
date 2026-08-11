package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route rules the renderer and the `home_dashboard` validator now share. These are the cases the
 * Custom path input can produce, asserted on the admission decision itself rather than on whether some
 * caller happened to survive them.
 */
class DashboardPathTest {

    @Test fun aViewBelowADashboardRootKeepsItsRouteQueryAndFragment() {
        assertEquals("/dashboard-test/office", DashboardPath.canonical("/dashboard-test/office", true))
        assertEquals("/office/view?kiosk=1#main", DashboardPath.canonical("/office/view?kiosk=1#main", true))
        assertEquals("/office/Upper%20Floor", DashboardPath.canonical("/office/Upper%20Floor", true))
        // The issue's own example, entered without a leading slash and with stray whitespace.
        assertEquals("/dashboard-test/laundry", DashboardPath.canonical("  dashboard-test/laundry  ", true))
    }

    @Test fun membershipIsTestedByRootSoAViewBelongsToItsDashboard() {
        assertEquals("/office", DashboardPath.root("/office/view?kiosk=1#main"))
        assertEquals("/office", DashboardPath.root("/office"))
        assertEquals("/dashboard-test", DashboardPath.root("dashboard-test/office/deeper"))
        assertNull(DashboardPath.root("https://elsewhere.example/office"))
    }

    @Test fun nothingThatLeavesThisHomeAssistantIsEverCanonical() {
        val escapes = listOf(
            "https://ha.example/wall-panel",
            "HTTPS://ha.example/wall-panel",
            "javascript:alert(1)",
            "//ha.example/wall-panel",
            // A protocol-relative form whose host happens to LOOK like a legal dashboard root. The
            // root-segment check alone would admit this one, so it is what makes the `//` guard real.
            "//office/view",
            "//office",
            "../wall-panel",
            "wall%2fpanel",
            "wall\\panel",
            "null",
            "/Office",              // dashboard url_paths are lower-case
            "/_office",             // a root may not start with an underscore
            "/office\u0007bell",    // an embedded control character
            "",
            "   ",
        )
        for (candidate in escapes) {
            assertNull("preserveRoute admitted $candidate", DashboardPath.canonical(candidate, true))
            assertNull("root admitted $candidate", DashboardPath.root(candidate))
        }
    }

    @Test fun aTraversalBelowALegalRootIsRefusedAsARouteButStillNamesThatRoot() {
        // Traversal is a SUFFIX concern: the dashboard is genuinely /office, and it is the view part
        // that must never escape it. Reducing to the root deliberately ignores the suffix, which is
        // why list membership can be tested with it while the route itself is still refused.
        for (traversal in listOf(
            "/office/../../etc",
            "/office/%2e%2e/evil",
            "/office/%2Foutside",
            "/office/%2e%2E/evil",
        )) {
            assertNull("route admitted $traversal", DashboardPath.canonical(traversal, true))
            assertEquals("$traversal still belongs to /office", "/office", DashboardPath.root(traversal))
        }
    }

    @Test fun aTruncatedPercentEscapeCannotSlipThroughTheDecoder() {
        assertNull(DashboardPath.canonical("/office/%2", true))
        assertNull(DashboardPath.canonical("/office/%zz", true))
    }

    @Test fun onlyRoutesThatNameNoDashboardFollowTheAccountDefault() {
        for (auto in listOf("", "   ", "/", "//", " /?kiosk ", "/#view")) {
            assertTrue("$auto should mean Auto", DashboardPath.followsAccountDefault(auto))
        }
        for (explicit in listOf("/lovelace", "/office/view", "office", "/dashboard-test/office?x=1")) {
            assertFalse("$explicit should be explicit", DashboardPath.followsAccountDefault(explicit))
        }
    }

    @Test fun rootSegmentsFollowHomeAssistantsOwnUrlPathShape() {
        for (ok in listOf("office", "dashboard-test", "lovelace", "0", "a_b-c9")) {
            assertTrue("$ok should be a legal url_path", DashboardPath.isRootSegment(ok))
        }
        for (bad in listOf("", "-office", "_office", "Office", "of/fice", "of fice", "of.fice")) {
            assertFalse("$bad should be rejected", DashboardPath.isRootSegment(bad))
        }
    }
}
