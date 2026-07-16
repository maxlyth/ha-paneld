package io.github.maxlyth.hapaneld.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootLocalSocketTest {
    @Test fun `only a root peer may serve privileged helper protocols`() {
        assertTrue(trustedRootSocketPeer(0))
        assertFalse(trustedRootSocketPeer(1000))
        assertFalse(trustedRootSocketPeer(2000))
        assertFalse(trustedRootSocketPeer(10_123))
    }

    @Test fun `every production helper socket authenticates peer credentials before protocol IO`() {
        val rootSocket = source("util/RootLocalSocket.kt")
        assertTrue(rootSocket.indexOf("socket.connect(") < rootSocket.indexOf("socket.peerCredentials.uid"))
        assertTrue(rootSocket.indexOf("socket.peerCredentials.uid") < rootSocket.indexOf("return socket"))

        val helper = source("util/HelperClient.kt")
        val evdev = source("input/EvdevButtonClient.kt")
        assertTrue(helper.contains("openRootAbstractSocket(SOCK)"))
        assertTrue(evdev.contains("openRootAbstractSocket(SOCK)"))
    }

    private fun source(relative: String): String = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
    ).first { it.isFile }.readText()
}
