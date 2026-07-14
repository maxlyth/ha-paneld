package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerCacheTest {
    private val stable = UpdateChecker.RequestedPolicies(
        paneldChannel = "stable",
        companion = UpdateChecker.CompanionPolicy("stable", "2026.5.4"),
    )
    private val paneldUpdate = UpdateChecker.UpdateInfo("ha-paneld", "0.9.1", "0.9.2", "paneld-url")
    private val companionUpdate = UpdateChecker.UpdateInfo("HA Companion", "2026.5.3", "2026.5.4", "companion-url")

    @Test fun bothResolvedReplaceTheCacheAndCompleteTheTransaction() {
        val result = reconcile(
            previous = emptyList(),
            paneldResolution = UpdateChecker.Resolution.Resolved(paneldUpdate),
            companionResolution = UpdateChecker.Resolution.Resolved(companionUpdate),
        )

        assertEquals(listOf(paneldUpdate, companionUpdate), result.available)
        assertEquals("stable", result.paneldCacheChannel)
        assertEquals(stable.companion, result.companionCachePolicy)
        assertTrue(result.complete)
    }

    @Test fun samePolicyPaneldFailurePreservesOnlyPaneldWhileCompanionResolves() {
        val result = reconcile(
            previous = listOf(paneldUpdate, companionUpdate),
            paneldResolution = UpdateChecker.Resolution.Failed,
            companionResolution = UpdateChecker.Resolution.Resolved(null),
        )

        assertEquals(listOf(paneldUpdate), result.available)
        assertFalse(result.complete)
    }

    @Test fun samePolicyCompanionFailurePreservesOnlyCompanionWhilePaneldResolves() {
        val result = reconcile(
            previous = listOf(paneldUpdate, companionUpdate),
            paneldResolution = UpdateChecker.Resolution.Resolved(null),
            companionResolution = UpdateChecker.Resolution.Failed,
        )

        assertEquals(listOf(companionUpdate), result.available)
        assertFalse(result.complete)
    }

    @Test fun paneldChannelChangeCannotPreserveAnOldChannelResult() {
        val result = UpdateChecker.reconcileCache(
            previous = listOf(paneldUpdate, companionUpdate),
            requested = stable.copy(paneldChannel = "prerelease"),
            paneldCachedChannel = "stable",
            companionCachedPolicy = stable.companion,
            paneldResolution = UpdateChecker.Resolution.Failed,
            companionResolution = UpdateChecker.Resolution.Resolved(companionUpdate),
        )

        assertEquals(listOf(companionUpdate), result.available)
        assertEquals("stable", result.paneldCacheChannel)
        assertFalse(result.complete)
    }

    @Test fun companionChannelOrCapChangeCannotPreserveAnOldPolicyResult() {
        listOf(
            UpdateChecker.CompanionPolicy("prerelease", stable.companion.maxVersion),
            UpdateChecker.CompanionPolicy("stable", "2026.4.3"),
        ).forEach { changedPolicy ->
            val result = UpdateChecker.reconcileCache(
                previous = listOf(paneldUpdate, companionUpdate),
                requested = stable.copy(companion = changedPolicy),
                paneldCachedChannel = "stable",
                companionCachedPolicy = stable.companion,
                paneldResolution = UpdateChecker.Resolution.Resolved(paneldUpdate),
                companionResolution = UpdateChecker.Resolution.Failed,
            )

            assertEquals(listOf(paneldUpdate), result.available)
            assertEquals(stable.companion, result.companionCachePolicy)
            assertFalse(result.complete)
        }
    }

    @Test fun absentCompanionAuthoritativelyClearsItsEntryAndCompletes() {
        val result = reconcile(
            previous = listOf(paneldUpdate, companionUpdate),
            paneldResolution = UpdateChecker.Resolution.Resolved(paneldUpdate),
            companionResolution = UpdateChecker.Resolution.Resolved(null),
        )

        assertEquals(listOf(paneldUpdate), result.available)
        assertEquals(stable.companion, result.companionCachePolicy)
        assertTrue(result.complete)
    }

    private fun reconcile(
        previous: List<UpdateChecker.UpdateInfo>,
        paneldResolution: UpdateChecker.Resolution,
        companionResolution: UpdateChecker.Resolution,
    ) = UpdateChecker.reconcileCache(
        previous = previous,
        requested = stable,
        paneldCachedChannel = "stable",
        companionCachedPolicy = stable.companion,
        paneldResolution = paneldResolution,
        companionResolution = companionResolution,
    )
}
