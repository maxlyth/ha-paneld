package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSafetyPolicyTest {
    private fun observation(
        keepAwake: Boolean = true,
        wakeLock: Boolean = true,
        wifiRequired: Boolean = true,
        wifiLock: Boolean = true,
        preventIdleDim: Boolean = true,
        timeoutMs: Int? = Int.MAX_VALUE,
        pluggedMask: Int? = 1,
        stayOnMask: Int? = 1,
        idle: Boolean? = false,
        exempt: Boolean? = false,
    ) = PowerSafetyObservation(
        keepAwakeConfigured = keepAwake,
        wakeLockHeld = wakeLock,
        wifiLockRequired = wifiRequired,
        wifiLockHeld = wifiLock,
        preventIdleDimConfigured = preventIdleDim,
        screenOffTimeoutMs = timeoutMs,
        interactive = true,
        pluggedMask = pluggedMask,
        stayOnWhilePluggedIn = stayOnMask,
        deviceIdleMode = idle,
        ignoringBatteryOptimizations = exempt,
        screenOffMechanism = "brightness-zero",
    )

    @Test fun `two positively observed guards classify safe`() {
        val assessment = PowerSafetyPolicy.assess(observation())
        assertEquals(PowerRiskLevel.SAFE, assessment.level)
        assertFalse(assessment.warning)
    }

    @Test fun `disabled app guard and ineffective native guard classify at risk`() {
        val assessment = PowerSafetyPolicy.assess(
            observation(keepAwake = false, wakeLock = false, preventIdleDim = false, timeoutMs = 60_000, stayOnMask = 0),
        )
        assertEquals(PowerRiskLevel.AT_RISK, assessment.level)
        assertTrue("keep_awake_disabled" in assessment.reasonCodes)
        assertTrue("native_screen_timeout_finite" in assessment.reasonCodes)
        assertTrue("stay_on_disabled" in assessment.reasonCodes)
    }

    @Test fun `configured but missing wake lock is not called safe`() {
        val assessment = PowerSafetyPolicy.assess(observation(wakeLock = false))
        assertEquals(PowerRiskLevel.CAUTION, assessment.level)
        assertTrue("wake_lock_not_held" in assessment.reasonCodes)
    }

    @Test fun `configured but missing required wifi lock is not called safe`() {
        val assessment = PowerSafetyPolicy.assess(observation(wifiLock = false))
        assertEquals(PowerRiskLevel.CAUTION, assessment.level)
        assertTrue("wifi_lock_not_held" in assessment.reasonCodes)
    }

    @Test fun `wifi lock is not required when Android exposes no wifi service`() {
        assertEquals(PowerRiskLevel.SAFE, PowerSafetyPolicy.assess(observation(wifiRequired = false, wifiLock = false)).level)
    }

    @Test fun `effective app lock requires wifi whenever wifi is present`() {
        assertFalse(PowerLockState(true, wifiLockRequired = true, wifiLockHeld = false).effective)
        assertTrue(PowerLockState(true, wifiLockRequired = false, wifiLockHeld = false).effective)
    }

    @Test fun `active device idle without exemption is at risk even with wake lock`() {
        val assessment = PowerSafetyPolicy.assess(observation(idle = true, exempt = false))
        assertEquals(PowerRiskLevel.AT_RISK, assessment.level)
        assertTrue("device_idle_active" in assessment.reasonCodes)
    }

    @Test fun `unknown power source remains unknown when only app guard is observed`() {
        val assessment = PowerSafetyPolicy.assess(
            observation(pluggedMask = null, stayOnMask = null, exempt = false),
        )
        assertEquals(PowerRiskLevel.UNKNOWN, assessment.level)
        assertTrue("power_source_unknown" in assessment.reasonCodes)
        assertFalse(assessment.summary.contains("unplugged", ignoreCase = true))
    }

    @Test fun `Android reported no external power is bounded wording not physical inference`() {
        val assessment = PowerSafetyPolicy.assess(observation(pluggedMask = 0, stayOnMask = 15))
        assertEquals(PowerRiskLevel.CAUTION, assessment.level)
        assertTrue("power_source_reports_unplugged" in assessment.reasonCodes)
        assertFalse(assessment.summary.contains("battery-powered", ignoreCase = true))
    }

    @Test fun `doze exemption plus active app guard is safe despite unreliable charging report`() {
        val assessment = PowerSafetyPolicy.assess(
            observation(pluggedMask = 0, stayOnMask = 0, exempt = true),
        )
        assertEquals(PowerRiskLevel.SAFE, assessment.level)
    }

    @Test fun `stay awake must cover the currently reported source bit`() {
        val usb = observation(keepAwake = false, wakeLock = false, pluggedMask = 2, stayOnMask = 1)
        assertEquals(false, PowerSafetyPolicy.stayAwakeEffective(usb))
        assertTrue("stay_on_misses_source" in PowerSafetyPolicy.assess(usb).reasonCodes)
    }

    @Test fun `repair preserves OEM stay-on bits and refuses an unknown baseline`() {
        assertEquals(null, PowerSafetyRepairPolicy.stayOnTarget(null))
        assertEquals(31, PowerSafetyRepairPolicy.stayOnTarget(16))
    }

    @Test fun `repair mutation status follows readback`() {
        assertEquals(PowerRepairStepStatus.APPLIED, PowerSafetyRepairPolicy.verifiedMutationStatus(true))
        assertEquals(PowerRepairStepStatus.FAILED, PowerSafetyRepairPolicy.verifiedMutationStatus(false))
    }

    @Test fun `Doze repair selects exactly one admitted mutator`() {
        assertEquals(
            "cmd deviceidle whitelist +io.github.maxlyth.hapaneld",
            PowerSafetyRepairPolicy.dozeMutationCommand("io.github.maxlyth.hapaneld", true, true),
        )
        assertEquals(
            "dumpsys deviceidle whitelist +io.github.maxlyth.hapaneld",
            PowerSafetyRepairPolicy.dozeMutationCommand("io.github.maxlyth.hapaneld", false, true),
        )
        assertEquals(null, PowerSafetyRepairPolicy.dozeMutationCommand("io.github.maxlyth.hapaneld", false, false))
    }

    @Test fun `safe assessment with a failed repair step remains partial`() {
        val safe = PowerSafetyPolicy.assess(observation())
        assertEquals(
            "partial",
            PowerSafetyRepairPolicy.resultStatus(
                safe,
                listOf(PowerRepairStepStatus.ALREADY, PowerRepairStepStatus.FAILED),
            ),
        )
    }
}
