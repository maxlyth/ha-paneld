package io.github.maxlyth.hapaneld.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardPerformanceHistoryTest {
    @Test fun coalescesFiveSecondSamplesIntoOneExactMinuteAggregate() {
        val pending = DashboardPerformanceAccumulator(maxEntries = 4)

        assertNull(pending.add(sample(updates = 10, bytes = 20_000, worstMicros = 500_000)))
        assertNull(pending.add(sample(
            updates = 15,
            bytes = 30_000,
            worstMicros = 200_000,
            filterActive = false,
            entityCount = 17,
        )))

        val aggregate = pending.drain().single()
        assertEquals(10_000, aggregate.sampleMs)
        assertEquals(4, aggregate.frames)
        assertEquals(25, aggregate.updates)
        assertEquals(50_000, aggregate.payloadBytes)
        assertEquals(4, aggregate.hydrationUpdates)
        assertEquals(200, aggregate.observerMicros)
        assertEquals(2, aggregate.droppedFrames)
        assertEquals(2, aggregate.interactionCount)
        assertEquals(500_000, aggregate.interactionMaxMicros)
        assertEquals(125_000, aggregate.inputDelayMicros)
        assertEquals(250_000, aggregate.interactionProcessingMicros)
        assertEquals(125_000, aggregate.presentationMicros)
        assertEquals(70_000, aggregate.stateTaskMicros)
        assertEquals(30_000, aggregate.stateTaskMaxMicros)
        assertEquals(2, aggregate.loafCount)
        assertEquals(11_000, aggregate.blockingMicros)
        assertEquals(8_000, aggregate.loafMaxMicros)
        assertEquals(6_000, aggregate.scriptMicros)
        assertEquals(8_000, aggregate.renderMicros)
        assertEquals(2, aggregate.longTaskCount)
        assertEquals(false, aggregate.filterActive)
        assertEquals(17, aggregate.entityCount)
    }

    @Test fun distinctTargetMinutesAreBoundedAndOldestIsReportedAsDropped() {
        val pending = DashboardPerformanceAccumulator(maxEntries = 2)
        pending.add(sample(minute = 1))
        pending.add(sample(minute = 2))

        val dropped = pending.add(sample(minute = 3))

        assertNotNull(dropped)
        assertEquals(1, dropped!!.key.minute)
        assertEquals(listOf(2L, 3L), pending.drain().map { it.key.minute })
        assertEquals(0, pending.size())
    }

    private fun sample(
        minute: Long = 123,
        updates: Long = 1,
        bytes: Long = 100,
        worstMicros: Long = 100_000,
        filterActive: Boolean = true,
        entityCount: Int = 42,
    ) = DashboardPerformanceSample(
        instance = "https://ha.example",
        path = "wall/default",
        minute = minute,
        filterActive = filterActive,
        entityCount = entityCount,
        batch = EntityFilterProtocol.TrafficBatch(
            sampleMs = 5_000,
            frames = 2,
            frameChars = bytes,
            entityUpdates = updates,
            processingMicros = 100,
            droppedFrames = 1,
            hydrationUpdates = 2,
            stateTaskMicros = if (updates == 10L) 30_000 else 40_000,
            stateTaskMaxMicros = if (updates == 10L) 30_000 else 20_000,
            interactionBins = LongArray(10).also { it[3] = 1 },
            interactionMaxMicros = worstMicros,
            inputDelayMicros = worstMicros / 4,
            interactionProcessingMicros = worstMicros / 2,
            presentationMicros = worstMicros / 4,
            loafCount = 1,
            blockingMicros = if (updates == 10L) 5_000 else 6_000,
            loafMaxMicros = if (updates == 10L) 8_000 else 7_000,
            scriptMicros = 3_000,
            renderMicros = 4_000,
            longTaskCount = 1,
        ),
    )
}
