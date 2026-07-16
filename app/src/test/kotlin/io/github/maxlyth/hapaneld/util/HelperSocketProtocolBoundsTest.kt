package io.github.maxlyth.hapaneld.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HelperSocketProtocolBoundsTest {
    @Test fun `binary helper reply enforces its streaming limit`() {
        val command = ByteArrayOutputStream()
        assertThrows(ByteLimitExceeded::class.java) {
            HelperSocketProtocol.sendBytes(
                command = "SCREENCAP",
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                output = command,
                shutdownOutput = {},
                maxBytes = 3,
            )
        }
        assertArrayEquals("SCREENCAP\n".toByteArray(), command.toByteArray())
    }
}
