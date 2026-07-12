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
    private val all = listOf(
        NSPanelPro, Tpa10, Smt1019, Wf1589t, S9e, EchoShow5Gen2, ZxSmt156, Generic,
        ShellyWallDisplay, ShellyWallDisplayV2,
    )

    @Test fun idsArePresentAndUnique() {
        all.forEach { assertTrue("blank id on ${it.displayName}", it.id.isNotBlank()) }
        val ids = all.map { it.id }
        assertEquals("duplicate profile ids", ids.size, ids.toSet().size)
    }

    @Test fun usesDaemonIsTheInverseOfAppCanSu() {
        // The daemon is the privileged path exactly when the app can't exec su.
        all.forEach { assertEquals("usesDaemon/appCanSu mismatch on ${it.id}", !it.appCanSu, it.usesDaemon) }
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

    // --- LedFactory routing (only the deterministic, non-probing mechanisms; the ioctl/autodetect
    // paths touch native /dev/ledjni and aren't hermetic) ---

    @Test fun ledFactoryRoutesDeterministicMechanisms() {
        assertTrue("NONE should be no-op (NSPanel Pro)", LedFactory.detect(NSPanelPro) is NoOpLedController)
        assertTrue("SYSFS_DAEMON should be socket (TPA10)", LedFactory.detect(Tpa10) is SocketLedController)
        assertTrue("RK3576_IOCTL_DAEMON should be socket (SMT1019)", LedFactory.detect(Smt1019) is SocketLedController)
        assertTrue("NONE should be no-op (S9E)", LedFactory.detect(S9e) is NoOpLedController)
    }
}
