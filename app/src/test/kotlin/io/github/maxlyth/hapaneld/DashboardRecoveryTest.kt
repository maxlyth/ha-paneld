package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRecoveryTest {
    @Test fun `registration callback is ignored when activity started online`() {
        val gate = NetworkRecoveryGate(initiallyAvailable = true)
        assertFalse(gate.onAvailable())
    }

    @Test fun `first available reloads when activity started offline`() {
        val gate = NetworkRecoveryGate(initiallyAvailable = false)
        assertTrue(gate.onAvailable())
        assertFalse(gate.onAvailable())
    }

    @Test fun `real network loss makes the next available recover exactly once`() {
        val gate = NetworkRecoveryGate(initiallyAvailable = true)
        gate.onLost()
        assertTrue(gate.onAvailable())
        assertFalse(gate.onAvailable())
    }

    @Test fun `startup connection failures back off without waiting for HA countdown`() {
        val policy = DashboardRetryPolicy()
        assertEquals(5_000L, policy.connectionFailureDelay(wasConnected = false))
        assertEquals(10_000L, policy.afterRetry())
        assertEquals(10_000L, policy.connectionFailureDelay(wasConnected = false))
        assertEquals(20_000L, policy.afterRetry())
        assertEquals(20_000L, policy.connectionFailureDelay(wasConnected = false))
    }

    @Test fun `established dashboard keeps long websocket reconnect grace`() {
        val policy = DashboardRetryPolicy()
        assertEquals(90_000L, policy.connectionFailureDelay(wasConnected = true))
    }

    @Test fun `successful connection resets startup backoff`() {
        val policy = DashboardRetryPolicy()
        policy.afterRetry()
        policy.afterRetry()
        policy.reset()
        assertEquals(5_000L, policy.connectionFailureDelay(wasConnected = false))
    }
}
