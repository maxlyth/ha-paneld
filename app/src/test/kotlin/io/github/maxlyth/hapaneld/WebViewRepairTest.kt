package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.isScannableHost
import io.github.maxlyth.hapaneld.util.scannableHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions behind a blocked screen that can repair itself, tested away from any view.
 *
 * Everything the screen chooses — whether to offer the repair, which true reason to give when it cannot,
 * whether a code is worth the space, and whether the address behind that code is one anybody could reach
 * — is a pure function here. What is left in the activity is drawing, and the source contracts in
 * `DashboardScreenPolicyTest` and `StatusSurfaceWiringContractTest` pin the wiring this cannot execute.
 */
class WebViewRepairTest {

    private val capable = WebViewRepairCapability(
        hasKnownGoodBuild = true,
        privileged = true,
        managedElsewhere = false,
    )

    @After fun detach() = WebViewRepairRuntime.detach()

    // --- which screen may offer a repair at all ---

    @Test fun `only a missing WebView capability is ever offered a repair`() {
        // Exhaustive by size, the same discipline providerRepairableAdmission and admissionRetryClass
        // use: a new outcome must be answered here rather than inherit an offer or a silence.
        assertEquals(11, AdmissionOutcome.entries.size)
        AdmissionOutcome.entries.forEach {
            val expected =
                if (it == AdmissionOutcome.BRIDGE_UNAVAILABLE) WebViewRepairOffer.OFFER
                else WebViewRepairOffer.NOT_REPAIRABLE
            assertEquals(it.name, expected, webViewRepairOffer(it, capable))
        }
    }

    @Test fun `the offer cannot widen without the shared rule widening first`() {
        // The point of delegating rather than restating: this asserts the two agree across every
        // outcome, so a screen can never offer a repair for a verdict the rebind rule does not treat as
        // provider-repairable. BRIDGE_ATTACH_FAILED is the case that matters — it is the near neighbour,
        // it is deliberately excluded there, and this is what stops it drifting back in here.
        AdmissionOutcome.entries.forEach {
            assertEquals(
                it.name,
                providerRepairableAdmission(it),
                webViewRepairOffer(it, capable) != WebViewRepairOffer.NOT_REPAIRABLE,
            )
        }
        assertEquals(
            WebViewRepairOffer.NOT_REPAIRABLE,
            webViewRepairOffer(AdmissionOutcome.BRIDGE_ATTACH_FAILED, capable),
        )
    }

    @Test fun `a screen with no verdict at all offers nothing`() {
        assertEquals(WebViewRepairOffer.NOT_REPAIRABLE, webViewRepairOffer(null, capable))
    }

    // --- and what it says when it cannot ---

    @Test fun `not knowing yet is a separate answer from knowing there is nothing to install`() {
        // THE REGRESSION THIS PINS. Deciding costs a privileged probe that cannot run on the drawing
        // thread, so the answer is cached and briefly absent. Collapsing absence into "no known-good
        // version for this panel" would print a confident, wrong sentence on exactly the panels that
        // can repair themselves, and it would print it first — before the true answer arrived.
        assertEquals(
            WebViewRepairOffer.UNKNOWN_CAPABILITY,
            webViewRepairOffer(AdmissionOutcome.BRIDGE_UNAVAILABLE, null),
        )
        assertEquals(
            WebViewRepairOffer.NO_KNOWN_GOOD_BUILD,
            webViewRepairOffer(AdmissionOutcome.BRIDGE_UNAVAILABLE, capable.copy(hasKnownGoodBuild = false)),
        )
    }

    @Test fun `each refusal names the reason that is actually true of this panel`() {
        assertEquals(
            WebViewRepairOffer.NEEDS_PRIVILEGE,
            webViewRepairOffer(AdmissionOutcome.BRIDGE_UNAVAILABLE, capable.copy(privileged = false)),
        )
        assertEquals(
            WebViewRepairOffer.NO_KNOWN_GOOD_BUILD,
            webViewRepairOffer(AdmissionOutcome.BRIDGE_UNAVAILABLE, capable.copy(hasKnownGoodBuild = false)),
        )
        assertEquals(
            WebViewRepairOffer.MANAGED_ELSEWHERE,
            webViewRepairOffer(AdmissionOutcome.BRIDGE_UNAVAILABLE, capable.copy(managedElsewhere = true)),
        )
    }

    @Test fun `a panel whose store owns the engine is told that first`() {
        // Precedence, not just membership. A panel can be all three at once, and the ordering decides
        // which sentence it reads: being told to find a root helper is useless advice on a panel whose
        // updates arrive on their own, and it is the advice a naive ordering would give.
        val everything = WebViewRepairCapability(
            hasKnownGoodBuild = false,
            privileged = false,
            managedElsewhere = true,
        )
        assertEquals(
            WebViewRepairOffer.MANAGED_ELSEWHERE,
            webViewRepairOffer(AdmissionOutcome.BRIDGE_UNAVAILABLE, everything),
        )
        assertEquals(
            WebViewRepairOffer.NO_KNOWN_GOOD_BUILD,
            webViewRepairOffer(
                AdmissionOutcome.BRIDGE_UNAVAILABLE,
                everything.copy(managedElsewhere = false),
            ),
        )
    }

    // --- asking for the repair ---

    /** One install lane, exactly as the service's own single slot behaves. */
    private class Lane {
        var running = false
        var starts = 0
        fun start(): Boolean {
            if (running) return false
            running = true
            starts++
            return true
        }
    }

    @Test fun `tapping repair twice starts one install and says why the second did nothing`() {
        val lane = Lane()
        WebViewRepairRuntime.attach(
            capability = { capable },
            start = lane::start,
            progress = { WebViewRepairProgress(running = lane.running, message = "") },
        )
        assertEquals(WebViewRepairRequest.STARTED, WebViewRepairRuntime.request())
        // Every later tap while it runs — an impatient second press, or a screen redrawn mid-install
        // whose button is pressed again — is refused rather than queued, and the refusal is
        // distinguishable from a failure so the screen can say "already installing" instead of "broken".
        assertEquals(WebViewRepairRequest.BUSY, WebViewRepairRuntime.request())
        assertEquals(WebViewRepairRequest.BUSY, WebViewRepairRuntime.request())
        assertEquals(1, lane.starts)
    }

    @Test fun `a panel whose service has not attached refuses instead of throwing`() {
        // The screen can legitimately be drawn before the service is up — this verdict is reached during
        // the activity's own start-up. Nothing here may throw onto the drawing thread.
        assertNull(WebViewRepairRuntime.capability())
        assertEquals(WebViewRepairRequest.UNAVAILABLE, WebViewRepairRuntime.request())
        assertEquals(WebViewRepairProgress(running = false, message = ""), WebViewRepairRuntime.progress())
    }

    @Test fun `detaching stops the offer rather than leaving a stale one behind`() {
        val lane = Lane()
        WebViewRepairRuntime.attach(
            capability = { capable },
            start = lane::start,
            progress = { WebViewRepairProgress(running = lane.running, message = "") },
        )
        assertEquals(capable, WebViewRepairRuntime.capability())
        WebViewRepairRuntime.detach()
        assertNull(WebViewRepairRuntime.capability())
        assertEquals(WebViewRepairRequest.UNAVAILABLE, WebViewRepairRuntime.request())
        assertEquals(0, lane.starts)
    }

    @Test fun `progress is read through to the installer rather than copied at attach time`() {
        // A snapshot taken once would freeze on "Working…" for the whole install. The runtime holds the
        // question, never the answer.
        val lane = Lane()
        var message = ""
        WebViewRepairRuntime.attach(
            capability = { capable },
            start = lane::start,
            progress = { WebViewRepairProgress(running = lane.running, message = message) },
        )
        assertEquals(WebViewRepairProgress(false, ""), WebViewRepairRuntime.progress())
        WebViewRepairRuntime.request()
        message = "downloading"
        assertEquals(WebViewRepairProgress(true, "downloading"), WebViewRepairRuntime.progress())
        lane.running = false
        message = "error: no permitted installer"
        // The end of an install that changed nothing. A success never arrives here, because installing an
        // engine restarts the process that would have read it.
        assertEquals(
            WebViewRepairProgress(false, "error: no permitted installer"),
            WebViewRepairRuntime.progress(),
        )
    }

    @Test fun `a capability that changes while the panel waits is seen on the next read`() {
        // Both inputs move on a panel parked here: a root helper finishes installing, or a profile
        // arrives with a build pinned for this model. A screen that cached the first answer would keep
        // refusing a repair the panel had since become able to perform.
        var current: WebViewRepairCapability? = null
        WebViewRepairRuntime.attach(
            capability = { current },
            start = { false },
            progress = { WebViewRepairProgress(false, "") },
        )
        assertEquals(
            WebViewRepairOffer.UNKNOWN_CAPABILITY,
            webViewRepairOffer(AdmissionOutcome.BRIDGE_UNAVAILABLE, WebViewRepairRuntime.capability()),
        )
        current = capable.copy(privileged = false)
        assertEquals(
            WebViewRepairOffer.NEEDS_PRIVILEGE,
            webViewRepairOffer(AdmissionOutcome.BRIDGE_UNAVAILABLE, WebViewRepairRuntime.capability()),
        )
        current = capable
        assertEquals(
            WebViewRepairOffer.OFFER,
            webViewRepairOffer(AdmissionOutcome.BRIDGE_UNAVAILABLE, WebViewRepairRuntime.capability()),
        )
    }

    // --- the code that moves the repair to a phone ---

    @Test fun `only the two credential screens send anybody to their phone`() {
        assertEquals(11, AdmissionOutcome.entries.size)
        val sent = AdmissionOutcome.entries.filter { configureQrPath(it) != null }
        assertEquals(
            listOf(AdmissionOutcome.CREDENTIAL_REFUSED, AdmissionOutcome.SIGN_IN_REQUIRED).sorted(),
            sent.sorted(),
        )
        // Including the screen this lane just taught to repair itself: it now has an answer on the
        // panel, so a code would be clutter offered in place of one.
        assertNull(configureQrPath(AdmissionOutcome.BRIDGE_UNAVAILABLE))
        assertNull(configureQrPath(null))
    }

    @Test fun `the code opens the sign-in control itself, not the top of a crowded page`() {
        // The anchor is the whole justification for the code. Landing on /configure would leave somebody
        // scrolling a long page on a phone to find the one row they came for, which is the work the code
        // was supposed to remove.
        assertEquals("/configure#cfg-ha-oauth", configureQrPath(AdmissionOutcome.SIGN_IN_REQUIRED))
        assertEquals("/configure#cfg-ha-oauth", configureQrPath(AdmissionOutcome.CREDENTIAL_REFUSED))
    }

    @Test fun `a screen that shows a code says less, and only where a code is shown`() {
        // The trade this pins: the ordinary credential explanation plus a scannable code does not fit a
        // 480x480 panel — measured at y=479 of 480, with the button pushed into a scroll nobody standing
        // at a wall would find. So a code screen carries one sentence instead of three. Every screen
        // WITHOUT a code must keep the longer copy, which is why this is keyed to the same two outcomes
        // rather than applied to the verdict generally.
        assertEquals(11, AdmissionOutcome.entries.size)
        val shortened = AdmissionOutcome.entries.filter { configureQrDetail(it) != null }
        assertEquals(shortened.sorted(), AdmissionOutcome.entries.filter { configureQrPath(it) != null }.sorted())
        assertTrue(
            configureQrDetail(AdmissionOutcome.SIGN_IN_REQUIRED)!!.endsWith("Scan this to sign in from a phone:"),
        )
        assertNull(configureQrDetail(AdmissionOutcome.BRIDGE_UNAVAILABLE))
        assertNull(configureQrDetail(null))
    }

    // --- and whether the address behind it is worth printing ---

    @Test fun `an address nobody could reach is not offered as one`() {
        // THE TRAP THIS EXISTS FOR. LocalAdminEndpoint.externalUrl falls back to 127.0.0.1 when it is
        // handed nothing, so a panel with no network produces a scannable, plausible and completely
        // useless code that opens the scanning phone's own loopback.
        assertNull(scannableHost(null, null))
        assertNull(scannableHost("127.0.0.1", null))
        assertNull(scannableHost("", ""))
        // A failed DHCP lease is the realistic case, and it looks like a LAN address to a naive check.
        assertNull(scannableHost("169.254.7.9", null))
        assertNull(scannableHost("0.0.0.0", null))
    }

    @Test fun `an ordinary home network address is offered`() {
        assertEquals("192.168.1.40", scannableHost("192.168.1.40", null))
        assertEquals("172.16.4.9", scannableHost("172.16.4.9", "fd00::1"))
        // IPv4 first, exactly as the URL builder prefers it, so the code and the printed address agree.
        assertEquals("192.168.1.40", scannableHost("192.168.1.40", "fd00::1"))
        assertEquals("fd00::1", scannableHost(null, "fd00::1"))
        assertEquals("fd00::1", scannableHost("127.0.0.1", "fd00::1"))
    }

    @Test fun `the reachability test is the opposite question from the request-source one`() {
        // isRoutable exists to reject a source that is not globally routable, so it rejects every address
        // a home panel actually has. Using it here would have hidden the code on every working panel and
        // shown it on none. These are the three families that genuinely fail.
        assertTrue(isScannableHost("192.168.1.40"))
        assertTrue(isScannableHost("172.16.4.9"))
        assertTrue(isScannableHost("fd00::1"))
        assertFalse(isScannableHost("127.0.0.1"))
        assertFalse(isScannableHost("169.254.7.9"))
        assertFalse(isScannableHost("0.0.0.0"))
        assertFalse("an unparseable value is not an address", isScannableHost("—"))
    }
}
