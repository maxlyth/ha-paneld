package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchSoundStatePolicyTest {
    @Test fun enablePersistsExactPriorStateBeforeRaisingStreamAndDisableRestoresIt() {
        val events = mutableListOf<String>()
        val prior = TouchSoundState(effectsSetting = null, systemStream = 0)
        val store = FakeStore(events)
        val hardware = FakeHardware(prior, events)
        val policy = TouchSoundStatePolicy(store, hardware)

        assertTrue(policy.enable())
        assertTrue(policy.disable())

        assertEquals(listOf("capture", "save:$prior", "enable", "restore:$prior", "disable-state"), events)
        assertEquals(prior, hardware.restored)
        assertFalse(policy.isEnabled(true))
        assertNull(store.prior)
    }

    @Test fun persistenceFailurePreventsAnyAudioMutation() {
        val events = mutableListOf<String>()
        val store = FakeStore(events, saveSucceeds = false)
        val hardware = FakeHardware(TouchSoundState(1, 4), events)

        assertFalse(TouchSoundStatePolicy(store, hardware).enable())
        assertEquals(listOf("capture", "save:TouchSoundState(effectsSetting=1, systemStream=4)"), events)
    }

    @Test fun legacyDisableTurnsEffectsOffWithoutChangingOrInventingVolume() {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val hardware = FakeHardware(TouchSoundState(1, 9), events)

        assertTrue(TouchSoundStatePolicy(store, hardware).disable())

        assertEquals(listOf("disable-conservative", "disable-state"), events)
        assertNull(hardware.restored)
    }

    private class FakeStore(
        private val events: MutableList<String>,
        private val saveSucceeds: Boolean = true,
    ) : TouchSoundStateStore {
        var enabled: Boolean? = null
        var prior: TouchSoundState? = null
        override fun active() = enabled
        override fun prior() = prior
        override fun saveEnabled(prior: TouchSoundState): Boolean {
            events += "save:$prior"
            if (saveSucceeds) {
                enabled = true
                this.prior = prior
            }
            return saveSucceeds
        }
        override fun saveDisabledAndClearPrior(): Boolean {
            events += "disable-state"
            enabled = false
            prior = null
            return true
        }
    }

    private class FakeHardware(
        private val captured: TouchSoundState,
        private val events: MutableList<String>,
    ) : TouchSoundHardware {
        var restored: TouchSoundState? = null
        override fun capture(): TouchSoundState {
            events += "capture"
            return captured
        }
        override fun enable(): Boolean {
            events += "enable"
            return true
        }
        override fun restore(state: TouchSoundState): Boolean {
            events += "restore:$state"
            restored = state
            return true
        }
        override fun disableConservatively(): Boolean {
            events += "disable-conservative"
            return true
        }
    }
}
