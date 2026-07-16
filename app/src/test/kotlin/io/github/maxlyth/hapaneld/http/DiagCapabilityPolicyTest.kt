package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.fakeProfile
import io.github.maxlyth.hapaneld.device.EvdevButton
import io.github.maxlyth.hapaneld.shizuku.ShizukuManagerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagCapabilityPolicyTest {
    @Test fun performanceUiNamesTheDevtoolsRelayAsRemoteDebugging() {
        val source = java.io.File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        assertTrue(source.contains("<h2>Remote WebView debugging "))
        assertFalse(source.contains("<h2>WebView debugging "))
    }

    @Test fun diagnosticButtonRequestUsesTheInjectedProfile() {
        val profile = fakeProfile(
            evdevButtons = listOf(
                EvdevButton("/dev/input/event7", 116, grab = true, eventType = "power"),
                EvdevButton("/dev/input/event3", 14, grab = false, eventType = "mute", sw = true),
            ),
        )
        assertEquals(
            "/dev/input/event7:KEY/116:grab,/dev/input/event3:SW/14:watch",
            DiagReader.evdevRequestDescription(profile),
        )
    }

    @Test fun profileWithoutEvdevButtonsHasNoRequestedStream() {
        assertNull(DiagReader.evdevRequestDescription(fakeProfile()))
    }

    @Test fun missingAppSuDoesNotClaimHelperBackedActionsAreUnavailable() {
        val cap = DiagReader.rootSuCapability(su = false, daemon = true)

        assertEquals("Root (su)", cap.name)
        assertEquals("degraded", cap.status)
        assertTrue(cap.note.contains("routed through the helper daemon"))
        assertFalse(cap.note.contains("unavailable"))
        assertFalse(cap.note.contains("no su on this firmware"))
    }

    @Test fun missingBothPrivilegeRoutesDefersToSpecificCapabilityRows() {
        val cap = DiagReader.rootSuCapability(su = false, daemon = false)

        assertEquals("none", cap.status)
        assertTrue(cap.note.contains("individual capability rows"))
        assertFalse(cap.note.contains("reboot/reload"))
    }

    @Test fun appVisibleSuIsReportedPrecisely() {
        val cap = DiagReader.rootSuCapability(su = true, daemon = false)

        assertEquals("ok", cap.status)
        assertEquals("available directly to ha-paneld", cap.note)
    }

    @Test fun rootedOrHelperBackedPanelsDoNotShowShizukuAsAParallelCapability() {
        for (manager in ShizukuManagerIdentity.Status.entries) {
            assertFalse(DiagReader.showShizukuCapability(rootish = true, consentEnabled = true, manager))
            assertFalse(DiagReader.showShizukuCapability(rootish = true, consentEnabled = false, manager))
        }
    }

    @Test fun genuinelyUnrootedPanelsShowShizukuOnlyWhenConfiguredOrInstalled() {
        assertFalse(
            DiagReader.showShizukuCapability(
                rootish = false,
                consentEnabled = false,
                ShizukuManagerIdentity.Status.MISSING,
            ),
        )
        assertTrue(
            DiagReader.showShizukuCapability(
                rootish = false,
                consentEnabled = true,
                ShizukuManagerIdentity.Status.MISSING,
            ),
        )
        assertTrue(
            DiagReader.showShizukuCapability(
                rootish = false,
                consentEnabled = false,
                ShizukuManagerIdentity.Status.TRUSTED,
            ),
        )
    }
}
