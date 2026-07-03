package io.github.maxlyth.hapaneld.device

import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Fingerprint → profile mapping ([DeviceProfile.match]). Guards the per-platform detection, especially
 * the order-sensitive collisions where two panels share a Build field and the wrong branch order would
 * silently hand a panel the wrong profile (wrong LED/relay/su path).
 */
class DeviceProfileMatchTest {
    private fun match(model: String, device: String, pv: String = "") =
        DeviceProfile.match(model, device, pv)

    @Test fun nsPanelProByPx30AndGen2Rk3326() {
        assertSame(NSPanelPro, match("PX30_EVB", "px30"))          // Gen1, model
        assertSame(NSPanelPro, match("rk3326_evb", ""))           // Gen1/Gen2 model
        assertSame(NSPanelPro, match("", "rk3326-s"))             // Gen2 device
        assertSame(NSPanelPro, match("px30", "px30", "NSPanel120P_3.7.1"))
    }

    @Test fun tpa10ByModelOrDevice() {
        assertSame(Tpa10, match("TPA10", "tpa10"))
        assertSame(Tpa10, match("something", "tpa10"))
    }

    @Test fun shellyV2ByCodenameOrSku() {
        assertSame(ShellyWallDisplayV2, match("blake", ""))
        assertSame(ShellyWallDisplayV2, match("jenna", ""))
        assertSame(ShellyWallDisplayV2, match("SAWD-3A1XE10EU2", ""))
        assertSame(ShellyWallDisplayV2, match("", "sawd-4a1xx"))
    }

    @Test fun shellyLegacyByCodenameSkuOrChipset() {
        assertSame(ShellyWallDisplay, match("stargate", ""))
        assertSame(ShellyWallDisplay, match("atlantis", ""))
        assertSame(ShellyWallDisplay, match("SAWD-0A1XX10EU1", ""))
        assertSame(ShellyWallDisplay, match("", "k400_mt6580"))
        assertSame(ShellyWallDisplay, match("", "e500_7731e"))
    }

    @Test fun wf1589tByDeviceOrModel() {
        assertSame(Wf1589t, match("rk3576_u", "wf1589t"))
        assertSame(Wf1589t, match("rk3576_u", "unrelateddev"))   // model rk3576_u alone
    }

    @Test fun s9eByModelDeviceOrProductVersion() {
        assertSame(S9e, match("", "s9e"))
        assertSame(S9e, match("s9", "rk3566_r", "S9_Android_1.1.0"))  // generic Build, vendor in ro.product.version
        assertSame(S9e, match("foo", "bar", "s9_android_1.1.0"))
    }

    @Test fun genericWhenNothingMatches() {
        assertSame(Generic, match("mysterypanel", "unknowndev"))
        assertSame(Generic, match("", "", ""))
    }

    // --- order-sensitive collisions: the whole reason detection order matters ---

    @Test fun smt1019WinsOverWf1589tDespiteSharedRk3576Model() {
        // Both share model "rk3576_u"; the SMT1019 (device wf2489*) is a locked-down no-root unit and
        // MUST match before the WF1589T clause, or it wrongly gets the WF1589T's app-direct LED (GitHub #8).
        assertSame(Smt1019, match("rk3576_u", "wf2489t"))
        assertSame(Smt1019, match("rk3576_u", "WF2489T"))
    }

    @Test fun tpa10WinsOverS9eDespiteSharedRk3566() {
        // rk3566 is shared with the S9E, but a TPA10 (device "tpa10") must resolve to Tpa10, not S9e.
        assertSame(Tpa10, match("tpa10", "tpa10", ""))
    }

    @Test fun matchIsCaseInsensitive() {
        assertSame(NSPanelPro, match("PX30_EVB", "PX30"))
        assertSame(Tpa10, match("TPA10", "TPA10"))
        assertSame(Smt1019, match("RK3576_U", "WF2489T"))
    }
}
