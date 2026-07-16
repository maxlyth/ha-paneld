package io.github.maxlyth.hapaneld.metrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeatureCostDiagnosticsContractTest {
    private val infoScript = listOf(
        File("src/main/assets/info.js"),
        File("app/src/main/assets/info.js"),
        File("../app/src/main/assets/info.js"),
    ).first(File::isFile).readText()
    private val serverSource = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
    ).first(File::isFile).readText()
    private val perfSource = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PerfReader.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PerfReader.kt"),
    ).first(File::isFile).readText()
    private val registrySource = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/metrics/FeatureCosts.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/metrics/FeatureCosts.kt"),
    ).first(File::isFile).readText()

    @Test fun internalFeatureCostsAreNotRenderedAsAUserFacingCard() {
        assertFalse(infoScript.contains("featureCostRows"))
        assertFalse(infoScript.contains("featurecost"))
        assertFalse(serverSource.contains("<h2>Feature costs"))
        assertFalse(serverSource.contains("id=\"featurecost\""))
    }

    @Test fun engineeringInstrumentationAndHarvestEndpointsRemainAvailable() {
        assertTrue(serverSource.contains("get(\"/perf/costs\")"))
        assertTrue(serverSource.contains("call.respondText(FeatureCosts.json()"))
        assertTrue(serverSource.contains("get(\"/perf/history\")"))
        assertTrue(serverSource.contains("entityLearning.performanceHistoryJson(hours)"))
        assertFalse(perfSource.contains("\"featureCosts\":"))
        assertFalse(perfSource.contains("FeatureCosts.json()"))
        assertTrue(registrySource.contains("Process-local registry. Deliberately reset only by process death"))
    }
}
