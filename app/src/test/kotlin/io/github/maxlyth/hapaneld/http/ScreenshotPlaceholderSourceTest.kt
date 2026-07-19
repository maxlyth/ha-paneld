package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotPlaceholderSourceTest {
    @Test
    fun coldDashboardShowsPersistedScreenshotWithoutClaimingCaptureIsReady() {
        val source = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()

        assertTrue(source.contains("s == null && cachedShot != null"))
        assertTrue(source.contains("val shotTitle = \"\"\"<h2>Screenshot"))
        assertTrue(source.contains("class=\"card-title-action\""))
        assertTrue(source.contains("title=\"Capture a fresh screenshot\">↻ Refresh</a></h2>"))
        assertTrue(!source.contains("the last successful capture stays visible"))
        assertTrue(
            source.contains(
                """<div class="card" id="shotcard" data-capture-ok="0">${'$'}shotTitle""",
            ),
        )
        assertTrue(source.contains("""shotInner(cachedShot)"""))
        assertTrue(
            source.contains(
                """<div class="card" id="shotcard" data-capture-ok="0" style="display:none">""",
            ),
        )
        assertTrue(
            source.contains(
                """<div class="card" id="shotcard" data-capture-ok="1">${'$'}shotTitle""",
            ),
        )
    }
}
