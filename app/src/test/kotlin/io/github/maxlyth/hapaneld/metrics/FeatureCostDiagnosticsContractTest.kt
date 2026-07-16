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

    @Test fun diagnosticsDoNotPresentInclusiveElapsedTimeAsAFlatCostRanking() {
        assertFalse(
            "inclusive elapsed totals must not be sorted into a misleading flat cost ranking",
            infoScript.contains("active.sort(function(a,b){return (b.wall_ns_total||0)-(a.wall_ns_total||0);}"),
        )
        assertTrue(infoScript.contains("elapsed is inclusive latency; parent and child totals overlap"))
        assertTrue(infoScript.contains("Nested elapsed totals overlap and are not an additive ranking."))
        assertTrue(infoScript.contains("o.parent_id?'↳ '"))
    }

    @Test fun disabledProjectionHasAnExplicitDiagnosticsState() {
        assertTrue(infoScript.contains("fc.enabled===false"))
        assertTrue(infoScript.contains("disabled in this build"))
    }
}
