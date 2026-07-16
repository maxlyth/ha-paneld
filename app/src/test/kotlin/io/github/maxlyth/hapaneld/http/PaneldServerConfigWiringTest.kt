package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.MqttBridge
import org.junit.Assert.assertEquals
import org.junit.Test

class PaneldServerConfigWiringTest {
    @Test fun httpRoutesEveryApplicableLiveSettingThroughTheSharedDispatcher() {
        assertEquals(
            MqttBridge.APPLY_SETTING_KEYS,
            PaneldServer.HTTP_LIVE_KEYS.toSet(),
        )
    }
}
