package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSettingAuthorityTest {
    private class FakeJournal : LiveSettingAuthority.Journal {
        val values = linkedMapOf<String, LiveSettingAuthority.Pending>()
        var writesSucceed = true
        var removesSucceed = true
        override fun load(): Map<String, LiveSettingAuthority.Pending> = values.toMap()
        override fun put(key: String, value: LiveSettingAuthority.Pending): Boolean = writesSucceed.also {
            if (it) values[key] = value
        }
        override fun remove(key: String): Boolean = removesSucceed.also {
            if (it) values.remove(key)
        }
    }

    @Test fun recognisedSettingAppliesImmediately() {
        val authority = LiveSettingAuthority(setOf("brightness_bias"))
        val applied = mutableListOf<Pair<String, String>>()

        assertTrue(authority.applyOrQueue("brightness_bias", "12") { key, value ->
            applied += key to value
            LiveSettingApplyResult.APPLIED
        })

        assertEquals(listOf("brightness_bias" to "12"), applied)
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun unknownSettingIsRejectedWithoutCallingRuntime() {
        val authority = LiveSettingAuthority(setOf("brightness_bias"))

        assertFalse(authority.applyOrQueue("unknown", "12") { _, _ ->
            error("unsupported setting reached runtime")
        })
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun drainingRuntimeQueuesOnlyLatestValueAndReplacementReplaysIt() {
        val authority = LiveSettingAuthority(setOf("brightness_bias"))

        assertTrue(authority.applyOrQueue("brightness_bias", "10") { _, _ -> LiveSettingApplyResult.DEFERRED })
        assertTrue(authority.applyOrQueue("brightness_bias", "20") { _, _ -> LiveSettingApplyResult.DEFERRED })
        assertEquals(mapOf("brightness_bias" to "20"), authority.pendingSnapshot())

        val replayed = mutableListOf<Pair<String, String>>()
        authority.replay { key, value ->
            replayed += key to value
            LiveSettingApplyResult.APPLIED
        }

        assertEquals(listOf("brightness_bias" to "20"), replayed)
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun failedOrThrowingReplayRemainsPendingForNextReplacement() {
        val authority = LiveSettingAuthority(setOf("brightness_bias", "navbar_mode"))
        authority.applyOrQueue("brightness_bias", "5") { _, _ -> LiveSettingApplyResult.DEFERRED }
        authority.applyOrQueue("navbar_mode", "Swipe") { _, _ -> throw IllegalStateException("draining") }

        authority.replay { key, _ ->
            if (key == "brightness_bias") LiveSettingApplyResult.APPLIED else LiveSettingApplyResult.FAILED
        }

        assertEquals(mapOf("navbar_mode" to "Swipe"), authority.pendingSnapshot())
    }

    @Test fun queuedValueSurvivesAuthorityRecreationAndClearsOnlyAfterReplay() {
        val journal = FakeJournal()
        val first = LiveSettingAuthority(setOf("brightness_bias"), journal)
        assertTrue(first.applyOrQueue("brightness_bias", "35") { _, _ -> LiveSettingApplyResult.DEFERRED })
        assertEquals(mapOf("brightness_bias" to LiveSettingAuthority.Pending("35", null)), journal.values)

        val replacement = LiveSettingAuthority(setOf("brightness_bias"), journal)
        val replayed = mutableListOf<Pair<String, String>>()
        replacement.replay { key, value -> replayed += key to value; LiveSettingApplyResult.APPLIED }

        assertEquals(listOf("brightness_bias" to "35"), replayed)
        assertTrue(journal.values.isEmpty())
        assertTrue(replacement.pendingSnapshot().isEmpty())
    }

    @Test fun failedJournalWriteRefusesAcknowledgementAndDoesNotApply() {
        val journal = FakeJournal().apply { writesSucceed = false }
        val authority = LiveSettingAuthority(setOf("brightness_bias"), journal)
        var called = false

        assertFalse(authority.applyOrQueue("brightness_bias", "7") { _, _ ->
            called = true
            LiveSettingApplyResult.APPLIED
        })
        assertFalse(called)
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun admittedApplyFailureIsNotAcknowledgedButRemainsDurableForReplay() {
        val journal = FakeJournal()
        val authority = LiveSettingAuthority(setOf("brightness_bias"), journal)

        assertFalse(authority.applyOrQueue("brightness_bias", "18") { _, _ ->
            LiveSettingApplyResult.FAILED
        })

        assertEquals(mapOf("brightness_bias" to LiveSettingAuthority.Pending("18", null)), journal.values)
        assertEquals(mapOf("brightness_bias" to "18"), authority.pendingSnapshot())
    }

    @Test fun `failed navbar actuation retains desired mode and prior for retry`() {
        val journal = FakeJournal()
        val authority = LiveSettingAuthority(setOf("navbar_mode"), journal)

        assertFalse(authority.applyOrQueue("navbar_mode", "Always on", "Off") { _, _, _ ->
            LiveSettingApplyResult.FAILED
        })

        assertEquals(
            mapOf("navbar_mode" to LiveSettingAuthority.Pending("Always on", "Off")),
            journal.values,
        )
        assertEquals(mapOf("navbar_mode" to "Always on"), authority.pendingSnapshot())
        assertEquals(mapOf("navbar_mode" to "Off"), authority.pendingPreviousSnapshot())
    }

    @Test fun deferredApplyIsAcknowledgedAndLatestValueRemainsQueued() {
        val authority = LiveSettingAuthority(setOf("brightness_bias"))

        assertTrue(authority.applyOrQueue("brightness_bias", "10") { _, _ -> LiveSettingApplyResult.DEFERRED })
        assertTrue(authority.applyOrQueue("brightness_bias", "20") { _, _ -> LiveSettingApplyResult.DEFERRED })

        assertEquals(mapOf("brightness_bias" to "20"), authority.pendingSnapshot())
    }

    @Test fun deferredLatestValueRetainsOriginalPriorAcrossAuthorityRecreation() {
        val journal = FakeJournal()
        val first = LiveSettingAuthority(setOf("update_channel"), journal)

        assertTrue(first.applyOrQueue("update_channel", "prerelease", "stable") { _, _, _ ->
            LiveSettingApplyResult.DEFERRED
        })
        assertTrue(first.applyOrQueue("update_channel", "stable", "prerelease") { _, _, _ ->
            LiveSettingApplyResult.DEFERRED
        })
        assertEquals(mapOf("update_channel" to "stable"), first.pendingSnapshot())
        assertEquals(mapOf("update_channel" to "stable"), first.pendingPreviousSnapshot())

        val replacement = LiveSettingAuthority(setOf("update_channel"), journal)
        var replayed: Triple<String, String, String?>? = null
        replacement.replay { key, value, previous ->
            replayed = Triple(key, value, previous)
            LiveSettingApplyResult.APPLIED
        }

        assertEquals(Triple("update_channel", "stable", "stable"), replayed)
        assertTrue(replacement.pendingSnapshot().isEmpty())
    }

    @Test fun deferredChannelReplayAfterRestartReceivesPrecommitProvenance() {
        val journal = FakeJournal()
        val first = LiveSettingAuthority(setOf("update_channel"), journal)
        assertTrue(first.applyOrQueue("update_channel", "prerelease", "stable") { _, _, _ ->
            LiveSettingApplyResult.DEFERRED
        })

        val replacement = LiveSettingAuthority(setOf("update_channel"), journal)
        var replayPrevious: String? = null
        replacement.replay { _, _, previous ->
            replayPrevious = previous
            LiveSettingApplyResult.APPLIED
        }

        assertEquals("stable", replayPrevious)
    }
}

class DurableLiveSettingApplyTest {
    @Test fun `desired value is durable before handler and handler receives previous value`() {
        var durable = "stable"
        val events = mutableListOf<String>()

        val result = durableLiveSettingApply(
            previousValue = durable,
            transient = false,
            persist = {
                durable = "prerelease"
                events += "persist:$durable"
                true
            },
            apply = { previous ->
                events += "apply:$previous:$durable"
                LiveSettingApplyResult.APPLIED
            },
        )

        assertEquals(LiveSettingApplyResult.APPLIED, result)
        assertEquals(listOf("persist:prerelease", "apply:stable:prerelease"), events)
    }

    @Test fun `deferred desired value remains durable for immediate readback and replay`() {
        listOf(LiveSettingApplyResult.DEFERRED, LiveSettingApplyResult.FAILED).forEach { execution ->
            var persisted = false
            assertEquals(
                execution,
                durableLiveSettingApply("old", false, persist = { persisted = true; true }, apply = { execution }),
            )
            assertTrue(persisted)
        }
    }

    @Test fun `persist failure refuses dispatch and transient work skips persistence and prior hint`() {
        var applied = false
        assertEquals(
            LiveSettingApplyResult.FAILED,
            durableLiveSettingApply("old", false, persist = { false }, apply = { applied = true; LiveSettingApplyResult.APPLIED }),
        )
        assertFalse(applied)
        var persisted = false
        var previous: String? = "unexpected"
        assertEquals(
            LiveSettingApplyResult.APPLIED,
            durableLiveSettingApply(
                "old", true,
                persist = { persisted = true; false },
                apply = { previous = it; LiveSettingApplyResult.APPLIED },
            ),
        )
        assertFalse(persisted)
        assertEquals(null, previous)
    }

    @Test fun `actuation-owned persistence is not performed before the handler`() {
        val events = mutableListOf<String>()

        assertEquals(
            LiveSettingApplyResult.APPLIED,
            durableLiveSettingApply(
                previousValue = "Off",
                transient = false,
                actuationOwnsPersistence = true,
                persist = { events += "pre-persist"; true },
                apply = {
                    events += "actuate:$it"
                    LiveSettingApplyResult.APPLIED
                },
            ),
        )

        assertEquals(listOf("actuate:Off"), events)
    }
}
