package io.github.maxlyth.hapaneld.http

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class FaviconAssetTest {
    @Test fun browserTabsUseDedicatedSafeAreaCropWithoutChangingHeaderLogo() {
        val favicon = File("src/main/assets/favicon.svg").readText()
        val logo = File("src/main/assets/icon.svg").readText()
        val api = File("src/main/assets/api.html").readText()
        val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()

        assertTrue(favicon.contains("viewBox=\"39 39 30 30\""))
        assertTrue(logo.contains("viewBox=\"20 20 68 68\""))
        assertTrue(api.contains("<link rel=\"icon\" type=\"image/svg+xml\" href=\"/favicon.svg\">"))
        assertTrue(server.contains("get(\"/favicon.svg\")"))
        assertTrue(server.contains("call.respondText(asset(\"favicon.svg\"), ContentType.Image.SVG)"))
        // The favicon link and the header logo now live once, in the single shared page shell
        // (pageShell) that renders every :8888 surface — not once per duplicated shell.
        assertEquals(1, Regex("<link rel=\\\"icon\\\" type=\\\"image/svg\\+xml\\\" href=\\\"/favicon\\.svg\\\">").findAll(server).count())
        assertEquals(1, Regex("<img src=\\\"/icon\\.svg\\\" class=\\\"logo\\\"").findAll(server).count())
    }
}
