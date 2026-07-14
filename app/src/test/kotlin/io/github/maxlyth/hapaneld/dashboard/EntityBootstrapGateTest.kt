package io.github.maxlyth.hapaneld.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityBootstrapGateTest {
    @Test fun emptyAutomaticLearnerHoldsRenderer() {
        assertTrue(shouldHoldRendererForEntityBootstrap(
            learningEnabled = true,
            filterEnabled = false,
            entityIds = emptyList(),
        ))
    }

    @Test fun inconsistentEnabledButEmptyFilterStillHoldsRenderer() {
        assertTrue(shouldHoldRendererForEntityBootstrap(
            learningEnabled = true,
            filterEnabled = true,
            entityIds = emptyList(),
        ))
    }

    @Test fun populatedAutomaticFilterMayRender() {
        assertFalse(shouldHoldRendererForEntityBootstrap(
            learningEnabled = true,
            filterEnabled = true,
            entityIds = listOf("light.example"),
        ))
    }

    @Test fun manualModeRetainsExistingUnfilteredBehaviour() {
        assertFalse(shouldHoldRendererForEntityBootstrap(
            learningEnabled = false,
            filterEnabled = false,
            entityIds = emptyList(),
        ))
    }

    @Test fun learnerCompletionOnlyReloadsSelectedBuiltinRenderer() {
        assertTrue(shouldReloadBuiltinAfterEntityFilterChange("builtin", "builtin"))
        assertFalse(shouldReloadBuiltinAfterEntityFilterChange("", "builtin"))
        assertFalse(shouldReloadBuiltinAfterEntityFilterChange("other.renderer", "builtin"))
    }
}
