package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CdpRelayStateTest {
    @Test fun acceptedLaunchIsNotRunningWhenChildProbeFails() {
        val state = RelayProcessState { false }
        assertFalse(state.start { true })
        assertFalse(state.running())
    }

    @Test fun processDeathInvalidatesAnEarlierSuccessfulStart() {
        var alive = true
        val state = RelayProcessState { alive }
        assertTrue(state.start { true })
        assertTrue(state.running())
        alive = false
        assertFalse(state.running())
    }

    @Test fun stopClearsStateEvenWhenTerminationReportsFailure() {
        val state = RelayProcessState { true }
        assertTrue(state.start { true })
        assertFalse(state.stop { false })
        assertFalse(state.running())
    }

    @Test fun `relay binary is atomically staged below a root-only directory`() {
        val command = CdpRelay.startCommand(
            "/data/user/0/io.github.maxlyth.hapaneld/files/cdprelay",
            "webview_devtools_remote_123",
        )
        assertTrue(command.contains("rm -rf /data/local/.hapaneld-cdp"))
        assertTrue(command.contains("mkdir -m 700 /data/local/.hapaneld-cdp"))
        assertTrue(command.contains("chown 0:0 /data/local/.hapaneld-cdp"))
        assertTrue(command.contains("chown 0:0 /data/local/.hapaneld-cdp/cdprelay.new"))
        assertTrue(command.contains("mv -f /data/local/.hapaneld-cdp/cdprelay.new /data/local/.hapaneld-cdp/cdprelay"))
        assertFalse(command.contains("cp /data/user/0/io.github.maxlyth.hapaneld/files/cdprelay /data/local/tmp/cdprelay"))
    }

    @Test fun `socket selection ignores another apps earlier WebView`() {
        val sockets = """
            000: 00000002 00000000 00010000 0001 01 1 @webview_devtools_remote_111
            000: 00000002 00000000 00010000 0001 01 2 @webview_devtools_remote_222
        """.trimIndent()
        assertEquals("webview_devtools_remote_222", CdpRelay.selectDevToolsSocket(sockets, setOf(222)))
    }

    @Test fun `socket selection fails closed when renderer owns multiple endpoints`() {
        val sockets = "@webview_devtools_remote_222\n@webview_devtools_remote_333"
        assertNull(CdpRelay.selectDevToolsSocket(sockets, setOf(222, 333)))
    }

    @Test fun `socket selection requires a renderer pid match`() {
        assertNull(CdpRelay.selectDevToolsSocket("@webview_devtools_remote_111", setOf(222)))
        assertNull(CdpRelay.selectDevToolsSocket("@webview_devtools_remote_111", emptySet()))
    }
}
