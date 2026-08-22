package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.http.SelfUpdateChannelMutation
import io.github.maxlyth.hapaneld.http.selfUpdateChannelMutation
import io.github.maxlyth.hapaneld.http.restoreChangesUpdateChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.test.runTest
import io.github.maxlyth.hapaneld.http.SelfUpdateChannelInstallResult

class SelfUpdateChannelAdmissionTest {
    @Test
    fun `active channel switch never persists before asynchronous candidate admission`() {
        val events = mutableListOf<String>()

        stageSelfUpdateChannelChange(
            current = "prerelease",
            requested = "stable",
            selfUpdateEnabled = true,
            requestAdmittedInstall = { requested, previous ->
                events += "prepare:$previous->$requested"
                true
            },
            persist = { events += "persist:$it" },
            publishCurrent = { events += "publish" },
        )

        assertEquals(listOf("prepare:prerelease->stable"), events)
    }

    @Test
    fun `busy active switch republishes old truth without persistence`() {
        val events = mutableListOf<String>()

        stageSelfUpdateChannelChange(
            current = "stable",
            requested = "prerelease",
            selfUpdateEnabled = true,
            requestAdmittedInstall = { _, _ -> false },
            persist = { events += "persist:$it" },
            publishCurrent = { events += "publish:stable" },
        )

        assertEquals(listOf("publish:stable"), events)
    }

    @Test
    fun `disabled self updater permits ordinary channel preference change`() {
        val events = mutableListOf<String>()

        stageSelfUpdateChannelChange(
            current = "stable",
            requested = "prerelease",
            selfUpdateEnabled = false,
            requestAdmittedInstall = { _, _ -> error("no candidate exists while disabled") },
            persist = { events += "persist:$it" },
            publishCurrent = { events += "publish" },
        )

        assertEquals(listOf("persist:prerelease", "publish"), events)
    }

    @Test
    fun `channel spelling is normalized before admission`() {
        assertEquals("prerelease", normalizeSelfUpdateChannel("  Pre-release  "))
        assertEquals("stable", normalizeSelfUpdateChannel("anything-else"))
    }

    @Test
    fun `multi-setting save preflights the effective enabled channel before any commit`() {
        assertEquals(
            SelfUpdateChannelMutation("stable", force = true),
            selfUpdateChannelMutation(
                currentChannel = "prerelease",
                currentSelfUpdate = false,
                requestedValues = mapOf("self_update" to "true", "update_channel" to "stable"),
            ),
        )
        assertEquals(
            null,
            selfUpdateChannelMutation(
                currentChannel = "stable",
                currentSelfUpdate = true,
                requestedValues = mapOf("self_update" to "false", "update_channel" to "prerelease"),
            ),
        )
    }

    @Test
    fun `restore cannot bypass channel guard by disabling updater and therefore needs no channel rollback`() {
        var durableChannel = "stable"
        var configCommitted = false
        val restore = mapOf("self_update" to "false", "update_channel" to "prerelease")

        if (!restoreChangesUpdateChannel(durableChannel, restore)) {
            durableChannel = restore.getValue("update_channel")
            configCommitted = true
        }

        assertEquals(false, configCommitted)
        assertEquals("stable", durableChannel)
        assertEquals(
            false,
            restoreChangesUpdateChannel(durableChannel, mapOf("update_channel" to "stable")),
        )
    }

    @Test
    fun `consume time direct to recovery refusal rolls back only its committed channel`() {
        assertEquals(
            "stable",
            failedSelfUpdateChannelRollback(
                currentChannel = "prerelease",
                failedChannel = "prerelease",
                previousChannel = "stable",
            ),
        )
        assertEquals(
            null,
            failedSelfUpdateChannelRollback(
                currentChannel = "nightly",
                failedChannel = "prerelease",
                previousChannel = "stable",
            ),
        )
    }

    @Test
    fun `thrown committed install rolls channel back before propagating`() {
        val events = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            runTest {
                installCommittedSelfUpdateChannel(
                    install = {
                        events += "install"
                        error("consume-time observation failed")
                    },
                    rollback = { events += "rollback" },
                )
            }
        }

        assertEquals(listOf("install", "rollback"), events)
    }

    @Test
    fun `typed install success is the only exit that retains committed channel`() = runTest {
        val events = mutableListOf<String>()

        val result = installCommittedSelfUpdateChannel(
            install = { SelfUpdateChannelInstallResult("installed", installed = true) },
            rollback = { events += "rollback" },
        )

        assertEquals(true, result.installed)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `canceled before start discards candidate and rolls back before ticket release`() {
        val events = mutableListOf<String>()

        cleanupCanceledCommittedSelfUpdateChannel(
            cause = kotlinx.coroutines.CancellationException("scope stopped"),
            discardPrepared = { events += "discard" },
            rollback = { events += "rollback" },
            finishProgress = { events += "finish-ticket" },
        )

        assertEquals(listOf("discard", "rollback", "finish-ticket"), events)
    }

    @Test
    fun `canceled before start releases promoted ticket even when rollback throws`() {
        val events = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            cleanupCanceledCommittedSelfUpdateChannel(
                cause = kotlinx.coroutines.CancellationException("scope stopped"),
                discardPrepared = { events += "discard" },
                rollback = {
                    events += "rollback"
                    error("rollback storage unavailable")
                },
                finishProgress = { events += "finish-ticket" },
            )
        }

        assertEquals(listOf("discard", "rollback", "finish-ticket"), events)
    }
}
