package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TameReenableRouteTest {
    private val server = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
    ).first { it.isFile }.readText()

    @Test fun `untame explicitly enables a firmware disabled package before dropping desired ownership`() {
        val route = server.substring(
            server.indexOf("post(\"/tame\")"),
            server.indexOf("get(\"/tame/suggest\")"),
        )
        assertTrue(route.contains("if (untame && !withContext(Dispatchers.IO) { tame.reenable(pkg) })"))
        assertTrue(route.contains("could not re-enable package"))
        val enable = route.indexOf("tame.reenable(pkg)")
        assertTrue(enable < route.indexOf("updateTameSelection", enable))
    }
}
