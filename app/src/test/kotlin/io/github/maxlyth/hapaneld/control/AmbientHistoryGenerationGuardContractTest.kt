package io.github.maxlyth.hapaneld.control

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the per-path generation-guard behaviour of [AmbientHistoryRuntime] so the reload/flush
 * cache-refresh dedup stays behaviour-preserving. The runtime is SQLite/Context-backed with no
 * Robolectric in the unit gate, so — matching the repo idiom (AutoBrightnessComputeBudgetContractTest)
 * — these are source-contract invariants, not live behaviour tests.
 */
class AmbientHistoryGenerationGuardContractTest {
    private val source = source("control/AmbientHistoryRuntime.kt")

    @Test fun `snapshot-based reload guard gates the cache write`() {
        // The reload / flush-reloadAfter refresh must only commit to `cached` when the generation
        // and the context/source snapshot captured at task start are still current.
        assertTrue(
            "reload/flush cache write must be gated on the start-of-task generation + identity snapshot",
            source.contains(
                "if (run == generation.get() && contextSnapshot == contextId && sourceSnapshot == sourceId) cached = it",
            ),
        )
        assertTrue(
            "refresh must read the retention window with the captured snapshots",
            source.contains("store.ambientHistory(contextSnapshot, sourceSnapshot, since)"),
        )
        assertTrue(
            "since must be the retention lower bound",
            source.contains("wallClockMs() / AMBIENT_MINUTE_MS - AMBIENT_RETENTION_MINUTES"),
        )
    }

    @Test fun `reload and flush keep their distinct failure log messages`() {
        assertTrue("reload path failure message preserved", source.contains("ambient history load failed"))
        assertTrue("flush-reloadAfter path failure message preserved", source.contains("ambient history refresh failed"))
    }

    @Test fun `seed keeps its distinct expected-identity guard and completion callback`() {
        // seed validates against the externally-supplied expected identity (not a start-of-closure
        // snapshot), writes via seedAmbientHistory, and fires onComplete — it must NOT be folded
        // into the snapshot-based reload helper.
        assertTrue(
            "seed guards on the caller-supplied expected identity",
            source.contains("contextId == expectedContextId && sourceId == expectedSourceId"),
        )
        assertTrue("seed persists imported history", source.contains("store.seedAmbientHistory(samples, wallClockMs())"))
        assertTrue("seed fires its completion callback under the guard", source.contains("runCatching(onComplete)"))
    }

    private fun source(relative: String): String {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(working, "app/src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
            File(working, "src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
        ).first(File::isFile).readText()
    }
}
