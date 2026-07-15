package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRestartCoordinatorTest {
    @Test
    fun `process restart is single flight and waits for response grace`() {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val events = mutableListOf<String>()
        val coordinator = ProfileRestartCoordinator(
            schedule = { delay, action -> scheduled += delay to action },
            restartProcess = { events += "restart-process" },
        )

        assertTrue(coordinator.request())
        assertFalse(coordinator.request())
        assertEquals(listOf(ProfileRestartCoordinator.RESPONSE_GRACE_MS), scheduled.map { it.first })

        scheduled.removeAt(0).second()
        assertEquals(listOf("restart-process"), events)
        assertTrue(scheduled.isEmpty())
    }
}
