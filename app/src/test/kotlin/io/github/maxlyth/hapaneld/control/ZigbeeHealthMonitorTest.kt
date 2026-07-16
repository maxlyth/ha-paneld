package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZigbeeHealthMonitorTest {
    private fun observation(
        layout: ZigbeeGatewayLayout = ZigbeeGatewayLayout.VENDOR_NATIVE,
        pid: Int? = 10,
        cpu: Double? = 3.3,
        guardCpu: Double? = 0.1,
        role: String? = "Repeater",
        netInfo: ZigbeeNetInfo? = ZigbeeNetInfo(7, 123, 0x1234),
    ) = ZigbeeGatewayObservation(
        present = true,
        layout = layout,
        packageVersion = "sonoff-3.5.0",
        productVersion = "3.8.0",
        gatewayPid = pid,
        gatewayCpu = cpu,
        guardPid = 11,
        guardCpu = guardCpu,
        role = role,
        netInfo = netInfo,
        recursiveWatchdogAssignment = false,
    )

    @Test fun cpuNormalizationSupportsWholeMachineAndOneCoreScales() {
        assertEquals(86.0, normalizeGatewayCpu(86.0, 4, false)!!, 0.01)
        assertEquals(86.0, normalizeGatewayCpu(21.5, 4, true)!!, 0.01)
        assertNull(normalizeGatewayCpu(-1.0, 4, false))
    }

    @Test fun netInfoParserIsBoundedAndReturnsOnlyHealthFields() {
        val parsed = ZigbeeNetInfoParser.parse(
            """noise {"networkKey":"secret","radioMac":"aa:bb","netinfo":{"roleType":0,"nodeId":"0","panId":"0xffff"}}""",
        )
        assertTrue(parsed!!.explicitlyInvalid)
        assertFalse(parsed.toString().contains("secret"))
        assertNull(ZigbeeNetInfoParser.parse("{".padEnd(5000, 'x')))
    }

    @Test fun mqttAttributesExcludeRawSensitiveGatewayData() {
        val attributes = ZigbeeHealthSnapshot(
            state = ZigbeeHealthState.HEALTHY,
            joined = true,
            role = "Repeater",
        ).mqttAttributes()
        assertFalse(attributes.contains("network_key"))
        assertFalse(attributes.contains("password"))
        assertFalse(attributes.contains("radio_mac"))
        assertFalse(attributes.contains("netinfo"))
    }

    @Test fun startupGracePreventsContainmentUntilItExpires() {
        val policy = ZigbeeHealthPolicy(startupGraceMs = 15 * 60_000L)
        policy.resetGrace(1)
        repeat(5) {
            val decision = policy.evaluate(
                1 + it * 60_000L,
                true,
                observation(cpu = 90.0, role = null, netInfo = ZigbeeNetInfo(0, 0, 0xffff)),
            )
            assertEquals(ZigbeeHealthState.STARTING, decision.state)
            assertFalse(decision.shouldContain)
        }
        repeat(4) {
            val after = policy.evaluate(
                15 * 60_000L + 1 + it,
                true,
                observation(cpu = 90.0, role = null, netInfo = ZigbeeNetInfo(0, 0, 0xffff)),
            )
            assertFalse(after.shouldContain)
        }
        val after = policy.evaluate(
            15 * 60_000L + 10,
            true,
            observation(cpu = 90.0, role = null, netInfo = ZigbeeNetInfo(0, 0, 0xffff)),
        )
        assertTrue(after.shouldContain)
    }

    @Test fun fiveConsecutiveHighCpuSamplesAreRequired() {
        val policy = ZigbeeHealthPolicy(startupGraceMs = 0)
        policy.resetGrace(1)
        repeat(4) {
            assertFalse(
                policy.evaluate(
                    2 + it.toLong(),
                    true,
                    observation(cpu = 51.0, role = null, netInfo = ZigbeeNetInfo(0, 0, 0xffff)),
                ).shouldContain,
            )
        }
        assertTrue(
            policy.evaluate(
                10,
                true,
                observation(cpu = 51.0, role = null, netInfo = ZigbeeNetInfo(0, 0, 0xffff)),
            ).shouldContain,
        )
    }

    @Test fun threePidChangesWithinTenMinutesDeclareRunaway() {
        val policy = ZigbeeHealthPolicy(startupGraceMs = 0)
        policy.resetGrace(1)
        listOf(10, 11, 12, 13).forEachIndexed { index, pid ->
            val decision = policy.evaluate(
                2 + index * 60_000L,
                true,
                observation(pid = pid, cpu = 2.0),
            )
            if (pid == 13) assertTrue(decision.shouldContain)
        }
    }

    @Test fun joinedHighCpuWarnsWithoutContainment() {
        val policy = ZigbeeHealthPolicy(startupGraceMs = 0)
        policy.resetGrace(1)
        var decision = policy.evaluate(2, true, observation(cpu = 80.0))
        repeat(4) { decision = policy.evaluate(3L + it, true, observation(cpu = 80.0)) }
        assertEquals(ZigbeeHealthState.DEGRADED_HIGH_CPU, decision.state)
        assertFalse(decision.shouldContain)
    }

    @Test fun unknownFourXLayoutFailsSafe() {
        val policy = ZigbeeHealthPolicy(startupGraceMs = 0)
        policy.resetGrace(1)
        var decision = policy.evaluate(
            2, true,
            observation(
                layout = ZigbeeGatewayLayout.UNKNOWN_4X,
                cpu = 90.0,
                role = null,
                netInfo = ZigbeeNetInfo(0, 0, 0xffff),
            ),
        )
        repeat(4) {
            decision = policy.evaluate(
                3L + it,
                true,
                observation(
                    layout = ZigbeeGatewayLayout.UNKNOWN_4X,
                    cpu = 90.0,
                    role = null,
                    netInfo = ZigbeeNetInfo(0, 0, 0xffff),
                ),
            )
        }
        assertEquals(ZigbeeHealthState.UNKNOWN, decision.state)
        assertFalse(decision.shouldContain)
    }

    @Test fun containmentIsSingleShotAndExplicitRetryRearmsIt() {
        var now = 1L
        var contains = 0
        val source = object : ZigbeeGatewayHealthSource {
            override fun observe() = observation(
                cpu = 90.0,
                role = null,
                netInfo = ZigbeeNetInfo(0, 0, 0xffff),
            )
            override fun contain(layout: ZigbeeGatewayLayout): ZigbeeContainmentResult {
                contains++
                return ZigbeeContainmentResult.COMPLETE
            }
        }
        val monitor = ZigbeeHealthMonitor(
            configuredOn = { true },
            source = source,
            onContain = {},
            onSnapshot = { _, _ -> },
            nowMs = { now++ },
            policy = ZigbeeHealthPolicy(startupGraceMs = 0),
        )
        repeat(6) { monitor.sample() }
        repeat(6) { monitor.sample() }
        assertEquals(1, contains)
        monitor.explicitRetry()
        repeat(6) { monitor.sample() }
        assertEquals(2, contains)
        monitor.stop()
    }

    @Test fun startIsIdempotentAcrossDuplicateServiceCommands() {
        val monitor = ZigbeeHealthMonitor(
            configuredOn = { false },
            source = object : ZigbeeGatewayHealthSource {
                override fun observe() = observation(pid = null)
                override fun contain(layout: ZigbeeGatewayLayout) = ZigbeeContainmentResult.FAILED
            },
            onContain = {},
            onSnapshot = { _, _ -> },
        )
        monitor.start()
        assertEquals(1L, monitor.lifecycleGeneration())
        monitor.start()
        assertEquals(1L, monitor.lifecycleGeneration())
        monitor.stop()
    }
}
