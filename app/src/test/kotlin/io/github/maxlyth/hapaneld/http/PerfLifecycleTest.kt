package io.github.maxlyth.hapaneld.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class PerfLifecycleTest {
    @Test fun startIsIdempotentAndStopIsATerminalGenerationBoundary() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            PerfReader.stop()
            PerfReader.start(scope)
            val first = PerfReader.lifecycleGeneration()
            assertTrue(first > 0)

            PerfReader.start(scope)
            assertEquals("a second start must not create another sampler", first, PerfReader.lifecycleGeneration())

            PerfReader.stop()
            assertEquals(0L, PerfReader.lifecycleGeneration())
            assertTrue("\"cpu\":null" in PerfReader.json())
            assertTrue("\"hist\":{\"cpu\":[],\"ram\":[],\"gpu\":[]}" in PerfReader.json())

            PerfReader.start(scope)
            assertTrue("replacement must have a new generation", PerfReader.lifecycleGeneration() > first)
        } finally {
            PerfReader.stop()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
