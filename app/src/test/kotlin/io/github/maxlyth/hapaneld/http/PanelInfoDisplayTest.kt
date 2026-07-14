package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PanelInfoDisplayTest {
    @Test fun separatesLogicalDensityFromProfiledPhysicalPpi() {
        val text = PanelInfo.displaySummary(1920, 1200, 212, 226)

        assertEquals("1920×1200 px · logical 212 dpi · physical ≈226 ppi", text)
        assertFalse(text.contains("native"))
    }

    @Test fun omitsPhysicalPpiWhenProfileEvidenceIsUnknown() {
        assertEquals("480×480 px · logical 160 dpi", PanelInfo.displaySummary(480, 480, 160, null))
    }
}
