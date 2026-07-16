package io.github.maxlyth.hapaneld.dashboard

import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class EntityBrowserObserverCostTest {
    @Test fun browserPressureIsReportedThroughFixedFeatureCostOperation() {
        val registry = FeatureCostRegistry({ 0L }, { -1L }, { 1L })

        recordBrowserObserverCosts(
            registry,
            EntityLearningProtocol.BrowserObserverCosts(
                frames = 4,
                entities = 7,
                frameChars = 8_192,
                parseMicros = 2_100,
                stringifyMicros = 900,
                dropped = 11,
                coalesced = 13,
            ),
        )

        val operations = JSONObject(registry.json()).getJSONArray("operations")
        val observer = (0 until operations.length()).asSequence()
            .map(operations::getJSONObject)
            .first { it.getString("id") == FeatureCostOperation.ENTITY_BROWSER_OBSERVER.id }
        assertEquals(1L, observer.getLong("calls"))
        assertEquals(3_000_000L, observer.getLong("wall_ns_total"))
        assertEquals(4L, observer.getLong("external_events"))
        assertEquals(8_192L, observer.getLong("external_input_chars"))
        assertEquals(7L, observer.getLong("work_units"))
        assertEquals(11L, observer.getLong("dropped"))
        assertEquals(13L, observer.getLong("coalesced"))
    }
}
