package io.github.maxlyth.hapaneld.device

import io.github.maxlyth.hapaneld.hardware.LedFactory
import io.github.maxlyth.hapaneld.hardware.NoOpLedController
import io.github.maxlyth.hapaneld.hardware.SocketLedController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-profile capability invariants + the LED-backend routing. These pin the su-vs-daemon contract:
 * a panel that can't exec `su` must be driven through the daemon, and the LED/screen-off paths must
 * agree with that. A new or edited profile that violates one of these is a real routing bug (a control
 * that silently does nothing), so failing here is the point.
 */
class DeviceProfileCapabilityTest {
    @Test fun wf1589tTamesTheCompetingBootLedDemoByDefault() {
        val demo = Wf1589t.tameVendorCandidates.single { it.pkg == "com.gulukai.pwmlightdemo" }
        assertTrue(demo.defaultTame)
        assertTrue(demo.note.contains("continuously cycles colours"))
    }

    private val all = DeviceProfile.knownProfiles

    @Test fun idsArePresentAndUnique() {
        all.forEach { assertTrue("blank id on ${it.displayName}", it.id.isNotBlank()) }
        val ids = all.map { it.id }
        assertEquals("duplicate profile ids", ids.size, ids.toSet().size)
    }

    @Test fun sandboxedAndDaemonBackedProfilesDeclareTheirDaemonRequirement() {
        all.forEach {
            if (!it.appCanSu) assertTrue("sandboxed profile must require daemon on ${it.id}", it.usesDaemon)
            val daemonHardware = it.ledMechanism in setOf(
                LedMechanism.SYSFS_DAEMON,
                LedMechanism.RK3576_IOCTL_DAEMON,
            ) || it.screenOff == ScreenOff.DAEMON_BLPOWER || it.hasButtonBacklight || it.evdevButtons.isNotEmpty()
            if (daemonHardware) assertTrue("daemon-backed feature omitted from usesDaemon on ${it.id}", it.usesDaemon)
        }
        assertTrue("WF1589T evdev power button requires the helper despite app su", Wf1589t.usesDaemon)
    }

    @Test fun noSuFormImpliesAppCannotSu() {
        // If there's no working su invocation, the app definitely can't su (converse need not hold —
        // a form can be declared yet blocked by the sandbox, e.g. TPA10).
        all.forEach {
            if (it.suForm == SuForm.NONE) assertFalse("suForm NONE but appCanSu on ${it.id}", it.appCanSu)
            if (it.appCanSu) assertTrue("appCanSu but suForm NONE on ${it.id}", it.suForm != SuForm.NONE)
        }
    }

    @Test fun screenOffPathAgreesWithSuCapability() {
        all.forEach {
            when (it.screenOff) {
                // su-driven bl_power needs the app to reach su.
                ScreenOff.SU_BLPOWER -> assertTrue("SU_BLPOWER but !appCanSu on ${it.id}", it.appCanSu)
                // daemon-driven bl_power is the sandbox-walled path.
                ScreenOff.DAEMON_BLPOWER -> assertFalse("DAEMON_BLPOWER but appCanSu on ${it.id}", it.appCanSu)
                ScreenOff.BRIGHTNESS_ZERO -> {} // the universal fallback — allowed either way
            }
        }
    }

    @Test fun daemonRoutedLedImpliesSandboxWalled() {
        // The two daemon LED mechanisms exist precisely because the app can't drive the node itself.
        all.forEach {
            if (it.ledMechanism == LedMechanism.SYSFS_DAEMON || it.ledMechanism == LedMechanism.RK3576_IOCTL_DAEMON) {
                assertFalse("daemon LED but appCanSu on ${it.id}", it.appCanSu)
            }
        }
    }

    @Test fun buttonBacklightIsAnExplicitHardwareFactNotAnRgbBackendInference() {
        assertTrue(Tpa10.hasButtonBacklight)
        all.filterNot { it === Tpa10 }.forEach {
            assertFalse("unexpected button-backlight on ${it.id}", it.hasButtonBacklight)
        }
        assertTrue(LedFactory.detect(Smt1019) is SocketLedController)
        assertFalse("SMT1019 daemon RGB must not imply a button-backlight", Smt1019.hasButtonBacklight)
    }

    @Test fun profileMetadataIsStructurallyValid() {
        val packageName = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        val eventType = Regex("^KEYCODE_[A-Z0-9_]+$")
        val cert = Regex("^[0-9a-f]{64}$")
        all.forEach { profile ->
            assertTrue("blank display name on ${profile.id}", profile.displayName.isNotBlank())
            assertTrue("blank soc class on ${profile.id}", profile.socClass.isNotBlank())
            profile.recommendedDensity?.let { assertTrue("invalid density on ${profile.id}", it in 72..640) }
            profile.recommendedFontScale?.let { assertTrue("invalid font scale on ${profile.id}", it in 0.5f..2f) }
            profile.physicalPpi?.let { assertTrue("invalid physical ppi on ${profile.id}", it in 50..1000) }
            assertTrue("non-finite room offset on ${profile.id}", profile.roomTempOffsetC.isFinite())
            assertEquals(
                "profile proximity calibration must provide both endpoints on ${profile.id}",
                profile.proximityNearRaw == null,
                profile.proximityFarRaw == null,
            )
            profile.recommendedWebView?.let { webView ->
                assertTrue("non-HTTPS WebView pin on ${profile.id}", webView.url.startsWith("https://"))
                assertTrue("unparseable WebView version on ${profile.id}", webView.major > 0)
                assertTrue("invalid WebView cert on ${profile.id}", cert.matches(webView.certSha256))
                assertTrue("invalid WebView artifact hash on ${profile.id}", cert.matches(webView.apkSha256))
            }
            val tamePackages = profile.tameVendorCandidates.map { it.pkg }
            assertEquals("duplicate tame package on ${profile.id}", tamePackages.size, tamePackages.toSet().size)
            profile.tameVendorCandidates.forEach {
                assertTrue("invalid tame package ${it.pkg} on ${profile.id}", packageName.matches(it.pkg))
                assertTrue("blank tame rationale for ${it.pkg} on ${profile.id}", it.note.isNotBlank())
            }
            profile.evdevButtons.forEach {
                assertTrue("invalid evdev node ${it.node} on ${profile.id}", it.node.matches(Regex("^/dev/input/event\\d+$")))
                assertTrue("invalid evdev code on ${profile.id}", it.code > 0)
                assertTrue("invalid event type ${it.eventType} on ${profile.id}", eventType.matches(it.eventType))
            }
        }
    }

    // --- LedFactory routing (only the deterministic, non-probing mechanisms; the ioctl/autodetect
    // paths touch native /dev/ledjni and aren't hermetic) ---

    @Test fun ledFactoryRoutesDeterministicMechanisms() {
        assertTrue("NONE should be no-op (NSPanel Pro)", LedFactory.detect(NSPanelPro) is NoOpLedController)
        assertTrue("SYSFS_DAEMON should be socket (TPA10)", LedFactory.detect(Tpa10) is SocketLedController)
        assertTrue("RK3576_IOCTL_DAEMON should be socket (SMT1019)", LedFactory.detect(Smt1019) is SocketLedController)
        assertTrue("NONE should be no-op (S9E)", LedFactory.detect(S9e) is NoOpLedController)
    }
}
