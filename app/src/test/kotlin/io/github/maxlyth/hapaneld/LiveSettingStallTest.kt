package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A value this panel has no way to apply must stay saved, stay journalled and keep being retried, while
 * the Configure page stops promising it is about to apply.
 *
 * The defect this closes: a root-backed apply on a panel whose root path is absent fails on every boot
 * forever, so "Saved settings waiting to apply: silence_boot_chime" stands permanently over a setting
 * nobody touched. Two earlier fixes were rejected for dropping the intent (a strike count that removed
 * the journal entry) and for inferring permanence from a root probe. Neither may come back: the entry
 * is never removed here, and only an applier that actually ran may report unavailability.
 */
class LiveSettingStallTest {
    private class FakeJournal : LiveSettingAuthority.Journal {
        val values = linkedMapOf<String, LiveSettingAuthority.Pending>()
        override fun load(): Map<String, LiveSettingAuthority.Pending> = values.toMap()
        override fun put(key: String, value: LiveSettingAuthority.Pending): Boolean {
            values[key] = value
            return true
        }
        override fun remove(key: String): Boolean {
            values.remove(key)
            return true
        }
    }

    private fun authority(journal: FakeJournal, boot: String?) =
        LiveSettingAuthority(setOf(KEY), journal) { boot }

    private fun LiveSettingAuthority.replayWith(result: LiveSettingApplyResult) {
        replay { _, _, _ -> result }
    }

    @Test fun `intent survives every boot that cannot apply it`() {
        // The rejected first design removed the journal entry after three failed replays. A later root
        // repair must still apply the value, so no number of unavailable boots may drop it.
        val journal = FakeJournal()
        var authority = authority(journal, "boot-0")
        authority.applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }

        repeat(20) { boot ->
            authority = authority(journal, "boot-$boot")
            authority.replayWith(LiveSettingApplyResult.UNAVAILABLE)
            assertEquals(
                "boot $boot dropped durable intent",
                mapOf(KEY to "true"),
                authority.pendingSnapshot(),
            )
        }
        assertTrue(journal.values.containsKey(KEY))
    }

    @Test fun `retries within one boot are one observation`() {
        // Same-process retries consumed strikes in the rejected second design. One boot is one reading
        // of one machine state however many times the applier runs in it.
        val journal = FakeJournal()
        val authority = authority(journal, "boot-a")
        authority.applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
        repeat(10) { authority.replayWith(LiveSettingApplyResult.UNAVAILABLE) }

        assertEquals(
            "one boot can never be enough evidence to stall",
            emptySet<String>(),
            authority.pendingStalledSnapshot(),
        )
        assertEquals(1, journal.values.getValue(KEY).unavailableBoots.size)
    }

    @Test fun `a second distinct boot stalls the entry without discarding it`() {
        val journal = FakeJournal()
        authority(journal, "boot-a").apply {
            applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
            assertEquals(emptySet<String>(), pendingStalledSnapshot())
        }

        // A reboot: a new process loads the same journal and replays it.
        val next = authority(journal, "boot-b")
        assertEquals(mapOf(KEY to "true"), next.pendingSnapshot())
        next.replayWith(LiveSettingApplyResult.UNAVAILABLE)

        assertEquals(setOf(KEY), next.pendingStalledSnapshot())
        assertEquals(
            "a stalled value is still durable desired state",
            mapOf(KEY to "true"),
            next.pendingSnapshot(),
        )
    }

    @Test fun `a repaired panel applies the stalled value and clears it`() {
        val journal = FakeJournal()
        authority(journal, "boot-a")
            .applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
        authority(journal, "boot-b").replayWith(LiveSettingApplyResult.UNAVAILABLE)
        assertEquals(setOf(KEY), authority(journal, "boot-b").pendingStalledSnapshot())

        // Root restored, or the permission finally granted: the retry that never stopped now succeeds.
        val repaired = authority(journal, "boot-c")
        repaired.replayWith(LiveSettingApplyResult.APPLIED)

        assertTrue(repaired.pendingSnapshot().isEmpty())
        assertTrue(repaired.pendingStalledSnapshot().isEmpty())
        assertFalse(journal.values.containsKey(KEY))
    }

    @Test fun `transient failure never stalls however many boots it spans`() {
        // Permanence is a claim only the applier may make. A draining bridge, a helper that has not
        // started yet and a denied root command are all FAILED, and must keep saying "waiting to apply"
        // forever rather than quietly becoming "this panel cannot".
        val journal = FakeJournal()
        var authority = authority(journal, "boot-0")
        authority.applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.FAILED }

        repeat(10) { boot ->
            authority = authority(journal, "boot-$boot")
            authority.replayWith(LiveSettingApplyResult.FAILED)
        }

        assertEquals(mapOf(KEY to "true"), authority.pendingSnapshot())
        assertEquals(emptySet<String>(), authority.pendingStalledSnapshot())
    }

    @Test fun `a transient failure after an unavailable boot neither adds nor removes evidence`() {
        val journal = FakeJournal()
        authority(journal, "boot-a")
            .applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }

        val mixed = authority(journal, "boot-b")
        mixed.replayWith(LiveSettingApplyResult.FAILED)
        assertEquals(
            "a failure is not an observation of absence",
            1,
            journal.values.getValue(KEY).unavailableBoots.size,
        )
        assertEquals(emptySet<String>(), mixed.pendingStalledSnapshot())

        mixed.replayWith(LiveSettingApplyResult.UNAVAILABLE)
        assertEquals(setOf(KEY), mixed.pendingStalledSnapshot())
    }

    @Test fun `restating the same desired value keeps the evidence already gathered`() {
        // The Configure form posts durable desired state back on every unrelated save, so a reset here
        // would flip a stalled entry to "waiting to apply" on each save and re-stall it two boots later.
        val journal = FakeJournal()
        authority(journal, "boot-a")
            .applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
        val stalled = authority(journal, "boot-b")
        stalled.replayWith(LiveSettingApplyResult.UNAVAILABLE)
        assertEquals(setOf(KEY), stalled.pendingStalledSnapshot())

        stalled.applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }

        assertEquals(
            "restating an intent says nothing new about the hardware",
            setOf(KEY),
            stalled.pendingStalledSnapshot(),
        )
    }

    @Test fun `a genuinely different desired value starts with no evidence`() {
        val journal = FakeJournal()
        authority(journal, "boot-a")
            .applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
        val stalled = authority(journal, "boot-b")
        stalled.replayWith(LiveSettingApplyResult.UNAVAILABLE)
        assertEquals(setOf(KEY), stalled.pendingStalledSnapshot())

        stalled.applyOrQueueOutcome(KEY, "false", "true") { _, _, _ -> LiveSettingApplyResult.FAILED }

        assertEquals(
            "new intent is judged on its own attempts",
            emptySet<String>(),
            stalled.pendingStalledSnapshot(),
        )
        assertEquals(mapOf(KEY to "false"), stalled.pendingSnapshot())
    }

    @Test fun `a stalled OFF is still reported as the desired value`() {
        // Retiring a pending OFF was rejected because it strands divergent hardware, config and MQTT
        // state with nothing left to say so. The entry stays, and stays visible as desired state.
        val journal = FakeJournal()
        authority(journal, "boot-a")
            .applyOrQueueOutcome(KEY, "false", "true") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
        val stalled = authority(journal, "boot-b")
        stalled.replayWith(LiveSettingApplyResult.UNAVAILABLE)

        assertEquals(setOf(KEY), stalled.pendingStalledSnapshot())
        assertEquals(mapOf(KEY to "false"), stalled.pendingSnapshot())
        assertEquals(mapOf(KEY to "true"), stalled.pendingPreviousSnapshot())
    }

    @Test fun `an unreadable boot identity records nothing rather than guessing`() {
        val journal = FakeJournal()
        var authority = authority(journal, null)
        authority.applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
        repeat(10) {
            authority = authority(journal, null)
            authority.replayWith(LiveSettingApplyResult.UNAVAILABLE)
        }

        assertEquals(
            "an observation that cannot be attributed to a boot is not evidence",
            emptySet<String>(),
            authority.pendingStalledSnapshot(),
        )
        assertEquals(mapOf(KEY to "true"), authority.pendingSnapshot())
    }

    @Test fun `an unavailable apply is pending exactly like any other unapplied value`() {
        val authority = LiveSettingAuthority(setOf(KEY), FakeJournal()) { "boot-a" }
        assertEquals(
            LiveSettingRequestOutcome.FAILED_PENDING,
            authority.applyOrQueueOutcome(KEY, "true", "false") { _, _, _ ->
                LiveSettingApplyResult.UNAVAILABLE
            },
        )
    }

    @Test fun `nothing but an apply request can create a journal entry`() {
        // The original report's other hypothesis was that a changed default mints desired-not-applied
        // state on its own. Construction reads the journal and never writes it, so a default that flips
        // in code cannot put a key here — only a request to apply a value can.
        val journal = FakeJournal()
        val authority = authority(journal, "boot-a")

        assertTrue(authority.pendingSnapshot().isEmpty())
        assertTrue(authority.pendingStalledSnapshot().isEmpty())
        assertTrue(journal.values.isEmpty())

        authority.replayWith(LiveSettingApplyResult.UNAVAILABLE)
        assertTrue("replay over an empty journal writes nothing", journal.values.isEmpty())
    }

    @Test fun `observations survive the journal round trip`() {
        val journal = FakeJournal()
        authority(journal, "boot-a")
            .applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }

        val reloaded = journal.load().getValue(KEY)
        assertEquals(setOf("boot-a"), reloaded.unavailableBoots)
        assertFalse(reloaded.stalled)
        assertTrue(reloaded.copy(unavailableBoots = setOf("boot-a", "boot-b")).stalled)
    }

    private companion object {
        const val KEY = "silence_boot_chime"
    }
}
