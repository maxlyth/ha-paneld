package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbControllerPolicyTest {
    @Test fun `network adb transitions stop at the first failed privileged step`() {
        assertEquals(
            "setprop persist.adb.tcp.port 5555 && " +
                "setprop service.adb.tcp.port 5555 && " +
                "setprop ctl.restart adbd",
            networkAdbEnableCommand(),
        )
        assertEquals(
            "setprop persist.adb.tcp.port \"\" && " +
                "setprop service.adb.tcp.port \"\" && " +
                "setprop ctl.restart adbd",
            networkAdbDisableCommand(),
        )
        assertFalse(networkAdbEnableCommand().contains(';'))
        assertFalse(networkAdbDisableCommand().contains(';'))
    }

    @Test fun `owned teardown failure retains ownership for retry`() {
        var cleared = false

        assertFalse(disableOwnedNetworkAdb(owned = true, teardown = { false }) { cleared = true })

        assertFalse(cleared)
    }

    @Test fun `owned teardown clears ownership only after success`() {
        var cleared = false

        assertTrue(disableOwnedNetworkAdb(owned = true, teardown = { true }) { cleared = true })

        assertTrue(cleared)
    }
}
