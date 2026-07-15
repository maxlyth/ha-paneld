package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdminDashboardTargetTest {
    @Test fun `ready configured builtin is available without Companion`() {
        assertEquals(
            AdminDashboardTarget.Builtin,
            resolveAdminDashboardTarget("builtin", builtinReady = true) { false },
        )
    }

    @Test fun `unready configured builtin does not fall back to Companion`() {
        assertNull(resolveAdminDashboardTarget("builtin", builtinReady = false) { true })
    }

    @Test fun `configured foreign renderer is selected when launchable`() {
        assertEquals(
            AdminDashboardTarget.Foreign("com.example.renderer"),
            resolveAdminDashboardTarget("com.example.renderer", builtinReady = false) {
                it == "com.example.renderer"
            },
        )
    }

    @Test fun `missing configured foreign renderer does not fall back to Companion`() {
        assertNull(resolveAdminDashboardTarget("com.example.renderer", builtinReady = false) {
            it == "io.homeassistant.companion.android.minimal"
        })
    }

    @Test fun `blank configuration keeps minimal Companion fallback`() {
        assertEquals(
            AdminDashboardTarget.Foreign("io.homeassistant.companion.android.minimal"),
            resolveAdminDashboardTarget("", builtinReady = false) { true },
        )
    }

    @Test fun `blank configuration falls through to full Companion`() {
        assertEquals(
            AdminDashboardTarget.Foreign("io.homeassistant.companion.android"),
            resolveAdminDashboardTarget("", builtinReady = false) {
                it == "io.homeassistant.companion.android"
            },
        )
    }

    @Test fun `blank configuration suppresses tile without Companion`() {
        assertNull(resolveAdminDashboardTarget("", builtinReady = true) { false })
    }
}
