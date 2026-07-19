package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagnosticCacheContractTest {
    private fun source(path: String): String =
        File("src/main/kotlin/io/github/maxlyth/hapaneld/$path").readText()

    @Test fun warmRouteConsumesOneLastKnownReportInsteadOfRunningProbes() {
        val server = source("http/PaneldServer.kt")
        val route = server.substringAfter("get(\"/diag\")").substringBefore("get(\"/logs/stream\")")

        assertTrue(route.contains("diagStaleOk()"))
        assertFalse(route.contains("DiagReader.dump"))
        assertTrue(server.contains("private val diagCache = Cached"))
        assertTrue(server.contains("val management = checkNotNull(snapCache.peek())"))
        assertTrue(server.contains("DiagReader.DisplaySizingEvidence("))
        assertTrue(server.contains("management.densityBase"))
        assertTrue(server.contains("management.densityCur"))
        assertTrue(server.contains("management.fontScale"))
        assertTrue(server.contains("diagCache.staleWhileRevalidate"))
        assertTrue(server.contains("if (diagCache.peek() == null)"))
        assertTrue(server.contains("snapCache.get()\n            return diagCache.get()"))
        assertTrue(server.contains("private val suCache = Cached(SU_TTL_MS) { Su.availableIsolated() }"))
        assertTrue(server.contains("privilege = management.privilege"))
        val supplier = server.substringAfter("private val diagCache = Cached").substringBefore("/** Call after any write")
        assertTrue(supplier.contains("capabilityRows = management.capabilityRows"))
        assertTrue(Regex("snapCache\\.peek\\(\\)").findAll(supplier).count() == 1)
        assertTrue(server.contains("val privilege: PrivilegedRouteObservation"))
        assertFalse(server.contains("suAvailable = suCache.peek()"))
        assertFalse(server.substringAfter("HTTP listening").substringBefore("fun stop(").contains("diagCache.get()"))
    }

    @Test fun diagnosticShellAndRootProbesHaveIndependentHardBounds() {
        val reader = source("http/DiagReader.kt")

        assertTrue(reader.contains("runBoundedLaunch("))
        assertTrue(reader.contains("BoundedLaunchGate()"))
        assertTrue(reader.contains("BoundedStreams.readBytes"))
        assertTrue(reader.contains("Su.runOutputIsolatedBounded"))
        assertTrue(reader.contains("deadline = deadline.cappedTo(EXEC_TIMEOUT_MS)"))
        assertTrue(reader.contains("process.waitFor(250L, TimeUnit.MILLISECONDS)"))
        val dump = reader.substringAfter("fun dump(").substringBefore("// Omitted from the report")
        assertFalse(dump.contains("Su.available()"))
        assertFalse(dump.contains("capabilities(ctx, profile, routes)"))
        assertTrue(reader.contains("if (showLed && daemon) HelperClient.send(\"LEDPROBE\")"))
        assertFalse(reader.contains("Runtime.getRuntime().exec"))
    }

    @Test fun httpBindDoesNotStartManagementObservation() {
        val server = source("http/PaneldServer.kt")
        val start = server.substring(
            server.indexOf("fun start()"),
            server.indexOf("fun prewarm()"),
        )
        val prewarm = server.substring(
            server.indexOf("fun prewarm()"),
            server.indexOf("fun stop()"),
        )

        assertFalse(start.contains("snapCache.get()"))
        assertTrue(prewarm.contains("snapCache.get()"))
        assertTrue(prewarm.contains("runPrewarmPhases("))
        assertFalse(prewarm.contains("scope.launch"))
        assertTrue(server.contains("if (stopping) return@staleWhileRevalidate false"))
    }
}
