package io.github.maxlyth.hapaneld.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityBootstrapGateTest {
    @Test fun emptyAutomaticLearnerHoldsRenderer() {
        assertTrue(shouldHoldRendererForEntityBootstrap(
            learningEnabled = true,
            filterEnabled = false,
        ))
    }

    @Test fun intentionallyEmptyAutomaticFilterMayRenderUnavailableEntities() {
        assertFalse(shouldHoldRendererForEntityBootstrap(
            learningEnabled = true,
            filterEnabled = true,
        ))
    }

    @Test fun populatedAutomaticFilterMayRender() {
        assertFalse(shouldHoldRendererForEntityBootstrap(
            learningEnabled = true,
            filterEnabled = true,
        ))
    }

    @Test fun manualModeRetainsExistingUnfilteredBehaviour() {
        assertFalse(shouldHoldRendererForEntityBootstrap(
            learningEnabled = false,
            filterEnabled = false,
        ))
    }

    @Test fun learnerCompletionOnlyReloadsSelectedBuiltinRenderer() {
        assertTrue(shouldReloadBuiltinAfterEntityFilterChange("builtin", "builtin"))
        assertFalse(shouldReloadBuiltinAfterEntityFilterChange("", "builtin"))
        assertFalse(shouldReloadBuiltinAfterEntityFilterChange("other.renderer", "builtin"))
    }
}
