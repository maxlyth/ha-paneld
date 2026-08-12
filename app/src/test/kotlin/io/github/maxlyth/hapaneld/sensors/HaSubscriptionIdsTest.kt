package io.github.maxlyth.hapaneld.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ids are correlation keys on one multiplexed socket. A collision does not fail loudly — it makes
 * one message answer for another — so the arithmetic is asserted directly rather than inferred from a
 * green stream test.
 */
class HaSubscriptionIdsTest {
    private val registryEvents = 3

    private fun ids(batches: Int, registry: Boolean, lifecycle: Boolean) =
        haSubscriptionIds(batches, registry, lifecycle, registryEvents)

    @Test fun everySubscriptionIdIsDistinctAcrossEveryCombinationOfDemand() {
        for (batches in 0..4) {
            for (registry in listOf(false, true)) {
                for (lifecycle in listOf(false, true)) {
                    val subject = ids(batches, registry, lifecycle)
                    val all = subject.entityBatchIds + subject.registryIds + subject.lifecycleIds.keys
                    assertEquals(
                        "batches=$batches registry=$registry lifecycle=$lifecycle must allocate distinct ids",
                        all.size,
                        all.toSet().size,
                    )
                }
            }
        }
    }

    /**
     * A safety net, deliberately not the primary guard. `FIRST_PING_ID` starts logical pings at 10, and
     * that cushion is wide enough that an offset which forgot the five lifecycle ids would STILL not
     * collide — the mutation battery proved it by leaving this assertion green. The property that
     * actually pins the offset is [thePingOffsetCountsEverySubscriptionIncludingLifecycle]; keep both,
     * but do not mistake this one for coverage of the arithmetic.
     */
    @Test fun noPingIdCanCollideWithASubscriptionId() {
        for (batches in 0..4) {
            for (registry in listOf(false, true)) {
                for (lifecycle in listOf(false, true)) {
                    val subject = ids(batches, registry, lifecycle)
                    val taken = subject.allSubscriptionIds
                    // The stream's first logical ping is 10 and climbs; check a generous run of them.
                    (0 until 50).forEach { step ->
                        val wireId = 10 + step + subject.pingIdOffset
                        assertTrue(
                            "ping wire id $wireId collides with a subscription " +
                                "(batches=$batches registry=$registry lifecycle=$lifecycle)",
                            wireId !in taken,
                        )
                    }
                }
            }
        }
    }

    @Test fun thePingOffsetCountsEverySubscriptionIncludingLifecycle() {
        val subject = ids(batches = 2, registry = true, lifecycle = true)
        assertEquals(2 + registryEvents + HaLifecycleEvent.entries.size, subject.pingIdOffset)
        assertEquals(subject.allSubscriptionIds.size, subject.pingIdOffset)
    }

    @Test fun withdrawnDemandAllocatesNothingForThatKind() {
        val none = ids(batches = 1, registry = false, lifecycle = false)
        assertTrue(none.registryIds.isEmpty())
        assertTrue(none.lifecycleIds.isEmpty())
        assertEquals(1, none.pingIdOffset)
    }

    @Test fun everyLifecycleTypeGetsExactlyOneId() {
        val subject = ids(batches = 0, registry = false, lifecycle = true)
        assertEquals(HaLifecycleEvent.entries.size, subject.lifecycleIds.size)
        assertEquals(HaLifecycleEvent.entries.toSet(), subject.lifecycleIds.values.toSet())
    }

    @Test fun entityBatchIdsStartAtOneAndAreContiguous() {
        assertEquals(listOf(1, 2, 3), ids(batches = 3, registry = false, lifecycle = false).entityBatchIds)
    }
}
