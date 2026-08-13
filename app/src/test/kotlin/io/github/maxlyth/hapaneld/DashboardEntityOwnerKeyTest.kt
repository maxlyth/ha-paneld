package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Owner keys carry an instance and a dashboard scope in one string. Two upgrade defects both came from
 * comparing the whole string when only one component was meant to decide, so the split is asserted
 * directly here and the two call sites are asserted against it.
 */
class DashboardEntityOwnerKeyTest {

    @Test fun `a target key splits into its instance and its captured scope`() {
        val key = dashboardEntityTargetKey("ha-abc", "/lovelace/kiosk")
        // The scope component is the dashboard ROOT: a view and its dashboard yield the same entity set.
        assertEquals("6:ha-abc/lovelace", key)
        assertEquals("ha-abc", dashboardEntityInstanceOf(key))
        assertEquals("/lovelace", dashboardEntityPathOf(key))
    }

    @Test fun `an instance whose own name contains the separator still splits correctly`() {
        // The length prefix exists for this: a naive indexOf on '/' or ':' would tear the key apart.
        val key = dashboardEntityTargetKey("https://ha.example:8123", "/office")
        assertEquals("https://ha.example:8123", dashboardEntityInstanceOf(key))
        assertEquals("/office", dashboardEntityPathOf(key))
    }

    @Test fun `a route-qualified owner from an older build still names the rooted dashboard`() {
        // The upgrade case behind the stranded-install defect. An owner stored before scope-rooting
        // reads as the same dashboard once its captured path is rooted, which is what makes re-rooting
        // it lossless rather than a re-learn.
        val legacyOwner = "6:ha-abc/lovelace/kiosk"
        assertEquals("ha-abc", dashboardEntityInstanceOf(legacyOwner))
        assertEquals("/lovelace", dashboardEntityScopePath(dashboardEntityPathOf(legacyOwner)))
        // A genuinely different dashboard must NOT root to the same place, or a real dashboard change
        // would silently inherit another dashboard's learned list instead of re-learning.
        assertEquals("/kitchen", dashboardEntityScopePath(dashboardEntityPathOf("6:ha-abc/kitchen/tab")))
    }

    @Test fun `an unparseable key yields neither an instance nor a scope`() {
        for (bad in listOf("", "ha-abc", "0:", "99:ha-abc/lovelace", "x:ha-abc/lovelace")) {
            assertEquals("instance of $bad", "", dashboardEntityInstanceOf(bad))
            assertEquals("scope of $bad", "", dashboardEntityPathOf(bad))
        }
    }
}
