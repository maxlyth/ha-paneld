package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelHealthTest {
    @Test fun parsesChromiumMajor() {
        assertEquals(107, PanelHealth.chromiumMajor("com.android.webview 107.0.5304.105"))
        assertEquals(138, PanelHealth.chromiumMajor("com.android.webview 138.0.7204.63"))
        assertNull(PanelHealth.chromiumMajor("unknown"))
        assertNull(PanelHealth.chromiumMajor("com.android.webview"))
    }

    @Test fun flagsOldWebViewOnly() {
        assertTrue("107 is verified-blank → flag", PanelHealth.webViewTooOld("com.android.webview 107.0.5304.105"))
        assertFalse("fleet target 138 → ok", PanelHealth.webViewTooOld("com.android.webview 138.0.7204.63"))
        assertFalse("threshold boundary 110 → ok", PanelHealth.webViewTooOld("com.android.webview 110.0.0.0"))
        assertFalse("unparseable → don't cry wolf", PanelHealth.webViewTooOld("unknown"))
    }
}
