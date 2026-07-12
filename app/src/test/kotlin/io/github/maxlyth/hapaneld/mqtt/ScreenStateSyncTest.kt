package io.github.maxlyth.hapaneld.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenStateSyncTest {
    @Test fun reconnectWhileDarkPublishesOff() {
        assertEquals(ScreenStateSync.Action.OFF, ScreenStateSync.onReconnect(physicallyDark = true))
    }

    @Test fun reconnectWhileLitPublishesOn() {
        assertEquals(ScreenStateSync.Action.ON, ScreenStateSync.onReconnect(physicallyDark = false))
    }

    @Test fun heartbeatPublishesOffAfterPhysicalDarkTransition() {
        assertEquals(
            ScreenStateSync.Action.OFF,
            ScreenStateSync.onHeartbeat(physicallyDark = true, lastBrightness = 120),
        )
    }

    @Test fun heartbeatPublishesOnAfterPhysicalWake() {
        assertEquals(
            ScreenStateSync.Action.ON,
            ScreenStateSync.onHeartbeat(physicallyDark = false, lastBrightness = -1),
        )
    }

    @Test fun heartbeatDoesNothingWhenPhysicalAndPublishedStatesAgree() {
        assertEquals(
            ScreenStateSync.Action.NONE,
            ScreenStateSync.onHeartbeat(physicallyDark = false, lastBrightness = 120),
        )
        assertEquals(
            ScreenStateSync.Action.NONE,
            ScreenStateSync.onHeartbeat(physicallyDark = true, lastBrightness = -1),
        )
    }
}
