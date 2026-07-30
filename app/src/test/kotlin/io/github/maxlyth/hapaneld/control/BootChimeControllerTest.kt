package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootChimeControllerTest {
    private val prior = BootChimeState(
        ringSpeakerSetting = 2,
        ringSetting = null,
        notificationSetting = 7,
        ringStream = 3,
        notificationStream = 6,
    )

    @Test fun enablingCapturesDurablyBeforeSilencingAndDisableRestoresExactly() {
        var configured = false
        val events = mutableListOf<String>()
        val store = FakeBootStore(events)
        val hardware = FakeBootHardware(prior, events)
        val controller = BootChimeController(
            configured = { configured },
            setConfigured = { configured = it; events += "configured:$it" },
            stateStore = store,
            hardware = hardware,
        )

        assertTrue(controller.set(true))
        assertTrue(controller.set(false))

        assertEquals(
            listOf("capture", "save", "configured:true", "silence", "restore:$prior", "clear", "configured:false"),
            events,
        )
        assertFalse(configured)
        assertEquals(prior, hardware.restored)
        assertNull(store.state)
    }

    @Test fun failedSnapshotPersistenceRefusesToChangeConfigurationOrAudio() {
        var configured = false
        val events = mutableListOf<String>()
        val store = FakeBootStore(events, saveSucceeds = false)
        val hardware = FakeBootHardware(prior, events)
        val controller = BootChimeController({ configured }, { configured = it }, store, hardware)

        assertFalse(controller.set(true))

        assertEquals(listOf("capture", "save"), events)
        assertFalse(configured)
        assertFalse(hardware.silenced)
    }

    @Test fun persistedSilenceCompletesOnTheLifecycleOwnerBeforeReturning() {
        var configured = true
        val events = mutableListOf<String>()
        val controller = BootChimeController(
            { configured },
            { configured = it },
            FakeBootStore(events),
            FakeBootHardware(prior, events),
        )

        controller.applyPersisted()

        assertEquals(listOf("capture", "save", "silence"), events)
    }

    @Test fun legacyDisableNeverInventsALouderRestore() {
        var configured = true
        val events = mutableListOf<String>()
        val hardware = FakeBootHardware(prior, events)
        val controller = BootChimeController(
            { configured },
            { configured = it; events += "configured:$it" },
            FakeBootStore(events),
            hardware,
        )

        controller.set(false)

        assertEquals(listOf("configured:false"), events)
        assertFalse(configured)
        assertNull(hardware.restored)
    }

    @Test fun exactRestoreCommandPreservesUnsetAndIndependentStreamValues() {
        val command = restoreShellCommand(prior)
        assertTrue(command.contains("settings put system volume_ring_speaker 2"))
        assertTrue(command.contains("settings delete system volume_ring"))
        assertTrue(command.contains("settings put system volume_notification 7"))
        assertTrue(command.contains("volume --stream 2 --set 3"))
        assertTrue(command.contains("volume --stream 5 --set 6"))
        assertFalse(command.contains("--set 10"))
        assertEquals(4, " && ".toRegex().findAll(command).count())
        assertFalse(command.contains(';'))
    }

    @Test fun silenceCommandCannotMaskAnEarlierPrivilegedWriteFailure() {
        assertEquals(
            "settings put system volume_ring_speaker 0 && " +
                "settings put system volume_ring 0 && " +
                "settings put system volume_notification 0 && " +
                "cmd media_session volume --stream 2 --set 0 && " +
                "cmd media_session volume --stream 5 --set 0",
            silenceShellCommand(0),
        )
    }

    @Test fun directTransitionAttemptsEveryDurableSettingAndLiveStream() {
        val attempted = mutableListOf<String>()

        assertFalse(applyBootChimeDirect(
            state = prior,
            writeSetting = { key, value ->
                attempted += "setting:$key=${value ?: "-"}"
                false
            },
            writeStream = { stream, value ->
                attempted += "stream:$stream=$value"
                if (stream == 2) error("ring stream denied")
                true
            },
        ))

        assertEquals(
            listOf(
                "setting:volume_ring_speaker=2",
                "setting:volume_ring=-",
                "setting:volume_notification=7",
                "stream:2=3",
                "stream:5=6",
            ),
            attempted,
        )
    }

    @Test fun helperSilenceSucceedsOnSandboxedPanelWithoutSu() {
        val direct = FakeBootChimeDirect(prior, applySucceeds = false)
        val daemon = FakeDaemon(mapOf("BOOTCHIME SILENCE" to "OK"))
        val root = FakeRootShell(available = false, runResult = false)
        val hardware = AndroidBootChimeHardware(direct, root, daemon)

        assertTrue(hardware.silence())

        assertEquals(listOf(BootChimeState(0, 0, 0, 0, 0)), direct.applied)
        assertEquals(listOf("BOOTCHIME SILENCE"), daemon.sent)
        assertTrue(root.ran.isEmpty())
    }

    @Test fun helperFailureFallsThroughToExistingSuCommand() {
        val direct = FakeBootChimeDirect(prior, applySucceeds = false)
        val daemon = FakeDaemon(mapOf("BOOTCHIME SILENCE" to "PARTIAL"))
        val root = FakeRootShell(runResult = true)
        val hardware = AndroidBootChimeHardware(direct, root, daemon)

        assertTrue(hardware.silence())

        assertEquals(listOf("BOOTCHIME SILENCE"), daemon.sent)
        assertEquals(listOf(silenceShellCommand(0)), root.ran)
    }

    @Test fun helperRestoreEncodesExactSnapshotIncludingUnsetValues() {
        val command = "BOOTCHIME RESTORE 2 - 7 3 6"
        val direct = FakeBootChimeDirect(prior, applySucceeds = false)
        val daemon = FakeDaemon(mapOf(command to "OK"))
        val root = FakeRootShell(runResult = false)
        val hardware = AndroidBootChimeHardware(direct, root, daemon)

        assertTrue(hardware.restore(prior))

        assertEquals(command, restoreHelperCommand(prior))
        assertEquals(listOf(command), daemon.sent)
        assertTrue(root.ran.isEmpty())
    }

    @Test fun directSuccessAvoidsHelperAndSu() {
        val direct = FakeBootChimeDirect(prior, applySucceeds = true)
        val daemon = FakeDaemon(mapOf("BOOTCHIME SILENCE" to "OK"))
        val root = FakeRootShell(runResult = true)
        val hardware = AndroidBootChimeHardware(direct, root, daemon)

        assertTrue(hardware.silence())

        assertTrue(daemon.sent.isEmpty())
        assertTrue(root.ran.isEmpty())
    }

    @Test fun allRouteFailureRemainsFalseAndPendingAtControllerLevel() {
        var configured = false
        val events = mutableListOf<String>()
        val store = FakeBootStore(events)
        val direct = FakeBootChimeDirect(prior, applySucceeds = false)
        val daemon = FakeDaemon(mapOf("BOOTCHIME SILENCE" to "ERR"))
        val root = FakeRootShell(available = false, runResult = false)
        val controller = BootChimeController(
            configured = { configured },
            setConfigured = { configured = it },
            stateStore = store,
            hardware = AndroidBootChimeHardware(direct, root, daemon),
        )

        assertFalse(controller.set(true))

        assertTrue(configured)
        assertEquals(prior, store.state)
        assertEquals(listOf("BOOTCHIME SILENCE"), daemon.sent)
        assertEquals(listOf(silenceShellCommand(0)), root.ran)
    }

    @Test fun failedExactRestoreRetainsConfigurationAndSnapshotForRetry() {
        var configured = true
        val events = mutableListOf<String>()
        val store = FakeBootStore(events).also { it.state = prior }
        val hardware = FakeBootHardware(prior, events, restoreSucceeds = false)
        val controller = BootChimeController(
            { configured },
            { configured = it; events += "configured:$it" },
            store,
            hardware,
        )

        assertFalse(controller.set(false))

        assertEquals(listOf("restore:$prior"), events)
        assertTrue(configured)
        assertEquals(prior, store.state)
    }

    private class FakeBootStore(
        private val events: MutableList<String>,
        private val saveSucceeds: Boolean = true,
    ) : BootChimeStateStore {
        var state: BootChimeState? = null
        override fun load() = state
        override fun save(state: BootChimeState): Boolean {
            events += "save"
            if (saveSucceeds) this.state = state
            return saveSucceeds
        }
        override fun clear(): Boolean {
            events += "clear"
            state = null
            return true
        }
    }

    private class FakeBootHardware(
        private val captured: BootChimeState,
        private val events: MutableList<String>,
        private val restoreSucceeds: Boolean = true,
    ) : BootChimeHardware {
        var silenced = false
        var restored: BootChimeState? = null
        override fun capture(): BootChimeState {
            events += "capture"
            return captured
        }
        override fun silence(): Boolean {
            events += "silence"
            silenced = true
            return true
        }
        override fun restore(state: BootChimeState): Boolean {
            events += "restore:$state"
            restored = state
            return restoreSucceeds
        }
    }

    private class FakeBootChimeDirect(
        private val captured: BootChimeState,
        private val applySucceeds: Boolean,
    ) : BootChimeDirectAccess {
        val applied = mutableListOf<BootChimeState>()
        override fun capture(): BootChimeState = captured
        override fun apply(state: BootChimeState): Boolean {
            applied += state
            return applySucceeds
        }
    }
}
