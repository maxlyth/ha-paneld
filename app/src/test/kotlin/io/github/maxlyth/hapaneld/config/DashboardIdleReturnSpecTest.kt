package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashboardIdleReturnSpecTest {
    @Test fun idleReturnIsDeclaredAndEnforcedAsBuiltInRendererOnly() {
        val spec = SettingsRegistry.spec("dashboard_idle_return_min")
        assertTrue(spec != null)
        assertTrue(spec!!.help.startsWith("Built-in renderer:"))

        val server = listOf(
            "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
            "app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
            "../app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
        ).map(::File).first { it.isFile }.readText()
        assertTrue(server.contains("built-in-renderer-required"))
        assertTrue(server.contains("(dashboardPackage ?: config.dashboardPackage) != SystemController.BUILTIN_DASHBOARD"))
    }
}
