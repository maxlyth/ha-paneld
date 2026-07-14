package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.test.assertNotNull

class PanelInfoDisplayTest {
    @Test fun separatesLogicalDensityFromProfiledPhysicalPpi() {
        val text = PanelInfo.displaySummary(1920, 1200, 212, 226)

        assertEquals("1920×1200 px · logical 212 dpi · physical ≈226 ppi", text)
        assertFalse(text.contains("native"))
    }

    @Test fun omitsPhysicalPpiWhenProfileEvidenceIsUnknown() {
        assertEquals("480×480 px · logical 160 dpi", PanelInfo.displaySummary(480, 480, 160, null))
    }

    @Test fun physicalSizeUsesOnlyProfiledPpi() {
        val size = assertNotNull(PanelInfo.physicalDisplaySize(1920, 1200, 226))

        assertEquals(10.0, size.diagonalInches, 0.05)
        assertEquals(21.6, size.widthCm, 0.05)
        assertEquals(13.5, size.heightCm, 0.05)
    }

    @Test fun physicalSizeIsOmittedWithoutTrustworthyPpi() {
        assertNull(PanelInfo.physicalDisplaySize(1920, 1200, null))
        assertNull(PanelInfo.physicalDisplaySize(1920, 1200, 0))
    }
}
