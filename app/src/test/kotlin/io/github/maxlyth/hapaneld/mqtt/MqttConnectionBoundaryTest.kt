package io.github.maxlyth.hapaneld.mqtt

import io.github.maxlyth.hapaneld.enterMqttConnectedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttConnectionBoundaryTest {
    @Test fun connectedPreludeArmsLivenessBeforeInvalidatingPreviousAcks() {
        val events = mutableListOf<String>()

        val entered = enterMqttConnectedState(
            stopped = false,
            authenticate = { events += "authenticate" },
            setConnected = { events += "connected" },
            markLiveness = { events += "liveness" },
            invalidatePreviousAcks = { events += "invalidate" },
        )

        assertTrue(entered)
        assertEquals(listOf("authenticate", "connected", "liveness", "invalidate"), events)
    }

    @Test fun stoppedBridgeRejectsConnectedCallbackWithoutSideEffects() {
        val events = mutableListOf<String>()

        val entered = enterMqttConnectedState(
            stopped = true,
            authenticate = { events += "authenticate" },
            setConnected = { events += "connected" },
            markLiveness = { events += "liveness" },
            invalidatePreviousAcks = { events += "invalidate" },
        )

        assertFalse(entered)
        assertEquals(emptyList<String>(), events)
    }
}
