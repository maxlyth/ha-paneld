package io.github.maxlyth.hapaneld.audio

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneAvailabilityTest {

    @After
    fun restoreDefault() {
        MicrophoneAvailability.reset()
    }

    @Test
    fun anUnwiredPanelReportsTheMicrophoneIdle() {
        MicrophoneAvailability.reset()
        assertTrue("nothing can be holding a microphone nothing leases", MicrophoneAvailability.isIdle())
    }

    @Test
    fun anObservedSourceIsIdleOnlyWhileNoLeaseIsHeld() {
        val source = FakeMicrophoneSource()
        MicrophoneAvailability.observe(source)
        assertTrue("no lease has been taken", MicrophoneAvailability.isIdle())

        val lease = source.lease(MicPurpose.ASSIST, consumer = object : PcmConsumer {
            override fun onFrame(frame: PcmFrame) = Unit
        })
        assertFalse("a held lease makes the microphone busy", MicrophoneAvailability.isIdle())

        lease.close()
        assertTrue("releasing the last lease frees the microphone", MicrophoneAvailability.isIdle())
    }

    @Test
    fun aPausedLeaseStillHoldsTheMicrophone() {
        val source = FakeMicrophoneSource()
        MicrophoneAvailability.observe(source)
        val lease = source.lease(MicPurpose.WAKE_WORD, consumer = object : PcmConsumer {
            override fun onFrame(frame: PcmFrame) = Unit
        })
        lease.pause()
        assertFalse("a paused holder has not given the microphone back", MicrophoneAvailability.isIdle())
        lease.close()
    }
}
