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

    @Test fun `learned network wait drives progress without claiming completion`() {
        assertEquals(0, networkWaitProgress(elapsedMs = 10_000L, estimateMs = 0L))
        assertEquals(500, networkWaitProgress(elapsedMs = 30_000L, estimateMs = 60_000L))
        assertEquals(950, networkWaitProgress(elapsedMs = 60_000L, estimateMs = 60_000L))
        assertEquals(950, networkWaitProgress(elapsedMs = 90_000L, estimateMs = 60_000L))
    }

    @Test fun `startup stages distinguish network address delay`() {
        fun stage(present: Boolean, link: Boolean, address: Boolean, default: Boolean) =
            startupNetworkStage(StartupNetworkSnapshot(present, link, address, default))

        assertEquals("Starting Android network services", stage(false, false, false, false))
        assertEquals("Waiting for a network link", stage(true, false, false, false))
        assertEquals(
            "Network link connected\nWaiting for a network address",
            stage(true, true, false, false),
        )
        assertEquals("Network address received\nPreparing the connection", stage(true, true, true, false))
        assertEquals("Network ready\nOpening Home Assistant", stage(true, true, true, true))
    }

    @Test fun `renderer generation rejects replaced and closed callbacks`() {
        val gate = RendererGenerationGate()
        val first = gate.open()
        assertTrue(gate.owns(first))

        val second = gate.open()
        assertFalse(gate.owns(first))
        assertTrue(gate.owns(second))

        gate.invalidate()
        assertFalse(gate.owns(second))
        val third = gate.open()
        gate.close()
        assertFalse(gate.owns(third))
        assertTrue(runCatching { gate.open() }.isFailure)
    }

    @Test fun `dashboard navigation stays on the configured authority`() {
        assertTrue(dashboardNavigationAllowed("https://ha.example", "https://HA.EXAMPLE/lovelace/0"))
        assertTrue(dashboardNavigationAllowed("http://ha.example", "https://ha.example/lovelace/0"))
        assertTrue(dashboardNavigationAllowed("http://ha.example:8123", "https://ha.example:8123/lovelace/0"))
        assertFalse(dashboardNavigationAllowed("https://ha.example", "https://ha.example:8443/lovelace/0"))
        assertFalse(dashboardNavigationAllowed("https://ha.example", "file://ha.example/data/local/tmp/page"))
        assertFalse(dashboardNavigationAllowed("https://ha.example", "https://other.example/lovelace/0"))
        assertFalse(dashboardNavigationAllowed("not a url", "https://ha.example/lovelace/0"))
    }
}
