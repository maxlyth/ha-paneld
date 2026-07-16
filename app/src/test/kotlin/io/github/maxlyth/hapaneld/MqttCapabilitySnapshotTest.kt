package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.config.Capabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttCapabilitySnapshotTest {
    @Test
    fun `connect and reannounce each invoke the capability supplier exactly once`() {
        var calls = 0
        val source = MqttDiscoveryCapabilitySource(supplier = {
            calls += 1
            Capabilities(companionInstalled = calls == 1)
        })

        val connectAnnouncement = source.snapshot()
        assertEquals(1, calls)
        assertTrue(connectAnnouncement!!.companionInstalled)
        assertEquals(connectAnnouncement, source.initialSnapshot())

        val reannouncement = source.snapshot()
        assertEquals(2, calls)
        assertFalse(reannouncement!!.companionInstalled)
        assertEquals(connectAnnouncement, source.initialSnapshot())
    }

    @Test
    fun `missing capability supplier leaves legacy controller fallbacks available`() {
        val source = MqttDiscoveryCapabilitySource(null)

        assertNull(source.snapshot())
        assertNull(source.initialSnapshot())
    }

    @Test
    fun `failed live snapshot degrades closed without aborting discovery`() {
        var calls = 0
        var failures = 0
        val source = MqttDiscoveryCapabilitySource(
            supplier = {
                calls += 1
                throw IllegalStateException("probe failed")
            },
            onFailure = { failures += 1 },
        )

        val snapshot = source.snapshot()

        assertEquals(Capabilities(), snapshot)
        assertEquals(Capabilities(), source.initialSnapshot())
        assertEquals(1, calls)
        assertEquals(1, failures)
    }

    @Test
    fun `failed first snapshot can recover on a later bounded refresh`() {
        var now = 1_000L
        var calls = 0
        val source = MqttDiscoveryCapabilitySource(
            supplier = {
                calls++
                if (calls == 1) throw IllegalStateException("root not ready")
                Capabilities(
                    zigbeePresent = true,
                    cpuGovernors = true,
                    networkAdb = true,
                    relays = 2,
                    buttonLeds = 4,
                )
            },
            nowMs = { now },
        )

        assertEquals(Capabilities(), source.snapshot())
        now += 5_000L
        assertEquals(Capabilities(), source.snapshot(maxAgeMs = 30_000L))
        assertEquals(1, calls)

        now += 30_001L
        val recovered = source.snapshot(maxAgeMs = 30_000L)!!
        assertTrue(recovered.zigbeePresent)
        assertTrue(recovered.cpuGovernors)
        assertTrue(recovered.networkAdb)
        assertEquals(2, recovered.relays)
        assertEquals(4, recovered.buttonLeds)
        assertEquals(2, calls)
    }

    @Test
    fun `channel shape grows after transient negatives and never shrinks on later unavailability`() {
        val shape = MqttCapabilityShape()

        val unavailable = shape.observe(Capabilities())
        assertTrue(unavailable.possible.keys().isEmpty())
        assertFalse(unavailable.grew)

        val recovered = shape.observe(
            Capabilities(
                zigbeePresent = true,
                cpuGovernors = true,
                networkAdb = true,
                relays = 2,
                buttonLeds = 4,
            ),
        )
        assertTrue(recovered.grew)
        assertEquals(
            setOf(
                "zigbee_router", "cpu_governor", "network_adb",
                "relay1", "relay2",
                "button_led1", "button_led2", "button_led3", "button_led4",
            ),
            recovered.possible.keys(),
        )
        assertEquals(recovered.possible, recovered.live)
        assertEquals(recovered.possible.keys(), recovered.changedKeys())

        val transientFailure = shape.observe(Capabilities())
        assertFalse(transientFailure.grew)
        assertEquals(recovered.possible, transientFailure.possible)
        assertTrue(transientFailure.live.keys().isEmpty())
        assertEquals(recovered.live, transientFailure.previousLive)
        assertEquals(recovered.possible.keys(), transientFailure.changedKeys())

        val partialRecovery = shape.observe(Capabilities(relays = 1, buttonLeds = 2))
        assertEquals(recovered.possible, partialRecovery.possible)
        assertEquals(setOf("relay1", "button_led1", "button_led2"), partialRecovery.live.keys())
        assertEquals(
            setOf("relay1", "button_led1", "button_led2"),
            partialRecovery.changedKeys(),
        )
    }

    @Test
    fun `reannouncement snapshot reuses a monotonic bounded cache`() {
        var now = 1_000L
        var calls = 0
        val source = MqttDiscoveryCapabilitySource(
            supplier = { Capabilities(companionInstalled = ++calls % 2 == 1) },
            nowMs = { now },
        )

        val connected = source.snapshot()
        now += 2_000L
        val burst = source.snapshot(maxAgeMs = 5_000L)
        assertEquals(connected, burst)
        assertEquals(1, calls)

        now += 5_001L
        val refreshed = source.snapshot(maxAgeMs = 5_000L)
        assertFalse(refreshed!!.companionInstalled)
        assertEquals(2, calls)
    }

    @Test
    fun `clock rollback expires rather than extending a capability snapshot`() {
        var now = 1_000L
        var calls = 0
        val source = MqttDiscoveryCapabilitySource(
            supplier = { Capabilities(companionInstalled = ++calls == 1) },
            nowMs = { now },
        )

        source.snapshot()
        now = 999L
        val refreshed = source.snapshot(maxAgeMs = 30_000L)

        assertFalse(refreshed!!.companionInstalled)
        assertEquals(2, calls)
    }

    @Test
    fun `privilege snapshot probes each needed authority at most once`() {
        var suCalls = 0
        var helperCalls = 0
        var shizukuCalls = 0

        val snapshot = probePrivilegedCapabilities(
            suProbe = { suCalls += 1; false },
            helperProbe = { helperCalls += 1; true },
            shizukuProbe = { shizukuCalls += 1; false },
        )

        assertFalse(snapshot.suAvailable)
        assertFalse(snapshot.shizukuAvailable)
        assertTrue(snapshot.anyAvailable)
        assertEquals(1, suCalls)
        assertEquals(1, helperCalls)
        assertEquals(1, shizukuCalls)
    }

    @Test
    fun `known root or Shizuku route avoids an unnecessary helper probe`() {
        for ((su, shizuku) in listOf(true to false, false to true, true to true)) {
            var suCalls = 0
            var helperCalls = 0
            var shizukuCalls = 0

            val snapshot = probePrivilegedCapabilities(
                suProbe = { suCalls += 1; su },
                helperProbe = { helperCalls += 1; true },
                shizukuProbe = { shizukuCalls += 1; shizuku },
            )

            assertEquals(su, snapshot.suAvailable)
            assertEquals(shizuku, snapshot.shizukuAvailable)
            assertTrue(snapshot.anyAvailable)
            assertEquals(1, suCalls)
            assertEquals(0, helperCalls)
            assertEquals(1, shizukuCalls)
        }
    }
}
