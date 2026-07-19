package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiDiagnosticsTest {
    @Test fun privilegedFallbackRequiresDemandedMissingDataAndAnAdmittedRoute() {
        val rssiOnly = WifiDiagnosticDemand(rssi = true, privilegedRoute = true)
        assertEquals(
            false,
            needsPrivilegedWifiStatus(WifiDiagnosticSnapshot(ssid = null, rssiDbm = -61), rssiOnly),
        )
        assertEquals(
            true,
            needsPrivilegedWifiStatus(WifiDiagnosticSnapshot(ssid = "hidden", rssiDbm = null), rssiOnly),
        )
        assertEquals(
            false,
            needsPrivilegedWifiStatus(
                WifiDiagnosticSnapshot(),
                WifiDiagnosticDemand(rssi = true, privilegedRoute = false),
            ),
        )
        assertEquals(
            false,
            needsPrivilegedWifiStatus(
                WifiDiagnosticSnapshot(),
                WifiDiagnosticDemand(ssid = false, rssi = false, privilegedRoute = true),
            ),
        )
    }

    @Test fun ssidNormalizationRemovesFrameworkQuotesAndRejectsRedaction() {
        assertEquals("Example Wi-Fi", normalizedWifiSsid("\"Example Wi-Fi\""))
        assertEquals("Example Wi-Fi", normalizedWifiSsid(" Example Wi-Fi "))
        assertNull(normalizedWifiSsid("<unknown ssid>"))
        assertNull(normalizedWifiSsid("unknown SSID"))
        assertNull(normalizedWifiSsid("  "))
        assertNull(normalizedWifiSsid(null))
    }

    @Test fun rssiNormalizationKeepsRealDbmAndRejectsFrameworkSentinels() {
        assertEquals(-63, normalizedWifiRssi(-63))
        assertEquals(0, normalizedWifiRssi(0))
        assertNull(normalizedWifiRssi(-127))
        assertNull(normalizedWifiRssi(1))
    }

    @Test fun privilegedStatusParsesModernAndLegacyWifiInfoWithoutCollectingIdentifiers() {
        assertEquals(
            WifiDiagnosticSnapshot("Example Wi-Fi", -61),
            parseWifiShellSnapshot(
                """
                Wi-Fi is enabled
                Wi-Fi is connected to "Example Wi-Fi"
                WifiInfo: SSID: Example Wi-Fi, BSSID: 00:11:22:33:44:55, RSSI: -61, Link speed: 433Mbps
                """.trimIndent(),
            ),
        )
        assertEquals(
            WifiDiagnosticSnapshot("Legacy, Network", -74),
            parseWifiShellSnapshot(
                "mWifiInfo SSID: Legacy, Network, BSSID: 00:11:22:33:44:55, RSSI: -74, score: 60",
            ),
        )
        assertEquals(
            WifiDiagnosticSnapshot(null, -80),
            parseWifiShellSnapshot("WifiInfo: SSID: <unknown ssid>, BSSID: null, RSSI: -80"),
        )
    }
}
