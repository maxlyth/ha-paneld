package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveGetAdmissionContractTest {
    private val serverSource by lazy {
        listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
    }

    @Test fun resourcefulGetRoutesGateBrowserAdmissionBeforeStartingWork() {
        assertRouteGatesBefore("/logs/stream", "handleLogStream(call)")
        assertRouteGatesBefore("/perf", "PerfReader.touch()")
        assertRouteGatesBefore("/screenshot.png", "interactive.screenshot()")
        assertRouteGatesBefore("/tame/suggest", "PerfReader.touch()")

        val status = routeBody("/status")
        assertTrue(status.indexOf("admitActiveRead(call)") in 0 until status.indexOf("UpdateChecker.check("))
    }

    private fun assertRouteGatesBefore(path: String, work: String) {
        val body = routeBody(path)
        assertTrue(
            "$path must gate active work",
            body.indexOf("admitActiveRead(call)") in 0 until body.indexOf(work),
        )
    }

    private fun routeBody(path: String): String {
        val start = serverSource.indexOf("get(\"$path\")")
        check(start >= 0) { "missing route $path" }
        val next = serverSource.indexOf("\n                    get(", start + 1)
        return serverSource.substring(start, if (next >= 0) next else serverSource.length)
    }
}
