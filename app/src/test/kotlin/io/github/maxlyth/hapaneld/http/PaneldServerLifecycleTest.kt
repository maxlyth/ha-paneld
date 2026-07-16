package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PaneldServerLifecycleTest {
    @Test
    fun startFailureStopsPartialEngineClosesIngressAndPropagatesOriginalFailure() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("bind failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            startOwnedHttpServer(
                start = { events += "start"; throw failure },
                stop = { events += "stop"; error("cleanup failed") },
                closeIngress = { events += "close-ingress" },
            )
        }

        assertSame(failure, thrown)
        assertEquals(listOf("start", "stop", "close-ingress"), events)
    }

    @Test
    fun successfulStartKeepsEngineAndIngressOwnedByCaller() {
        val events = mutableListOf<String>()

        startOwnedHttpServer(
            start = { events += "start" },
            stop = { events += "stop" },
            closeIngress = { events += "close-ingress" },
        )

        assertEquals(listOf("start"), events)
    }
}
