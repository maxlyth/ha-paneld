package io.github.maxlyth.hapaneld.control

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PowerSafetyMutationPolicyTest {
    @Test fun `only disabling an enabled guard is a safety reduction`() {
        assertTrue(PowerSafetyMutationPolicy.requestsSafetyReduction(true, false, true, null))
        assertTrue(PowerSafetyMutationPolicy.requestsSafetyReduction(true, null, true, false))
        assertFalse(PowerSafetyMutationPolicy.requestsSafetyReduction(false, false, false, false))
        assertFalse(PowerSafetyMutationPolicy.requestsSafetyReduction(false, true, false, true))
        assertFalse(PowerSafetyMutationPolicy.requestsSafetyReduction(true, true, true, true))
        assertFalse(PowerSafetyMutationPolicy.requestsSafetyReduction(true, null, true, null))
    }

    @Test fun `hardened mqtt rejects only unapproved safety reduction`() {
        assertFalse(PowerSafetyMutationPolicy.allowPreventIdleDimTransition(true, true, false, true))
        assertTrue(PowerSafetyMutationPolicy.allowPreventIdleDimTransition(true, true, true, true))
        assertTrue(PowerSafetyMutationPolicy.allowPreventIdleDimTransition(true, false, false, true))
        assertTrue(PowerSafetyMutationPolicy.allowPreventIdleDimTransition(false, true, false, true))
        assertTrue(PowerSafetyMutationPolicy.allowPreventIdleDimTransition(true, true, false, false))
    }
}
