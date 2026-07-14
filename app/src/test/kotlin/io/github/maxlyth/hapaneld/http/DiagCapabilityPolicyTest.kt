package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.fakeProfile
import io.github.maxlyth.hapaneld.device.EvdevButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagCapabilityPolicyTest {
    @Test fun diagnosticButtonRequestUsesTheInjectedProfile() {
        val profile = fakeProfile(
            evdevButtons = listOf(
                EvdevButton("/dev/input/event7", 116, grab = true, eventType = "power"),
                EvdevButton("/dev/input/event3", 14, grab = false, eventType = "mute", sw = true),
            ),
        )
        assertEquals(
            "/dev/input/event7:KEY/116:grab,/dev/input/event3:SW/14:watch",
            DiagReader.evdevRequestDescription(profile),
        )
    }

    @Test fun profileWithoutEvdevButtonsHasNoRequestedStream() {
        assertNull(DiagReader.evdevRequestDescription(fakeProfile()))
    }
}
