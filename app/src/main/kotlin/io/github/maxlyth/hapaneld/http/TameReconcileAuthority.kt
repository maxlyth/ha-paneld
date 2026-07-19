package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.TameReconcileResult
import io.github.maxlyth.hapaneld.util.KeyedLatestDispatcher

/**
 * The single vendor-taming execution owner. Queue entries are only wake-up signals: every pass reads
 * the current durable desired value, so reordered/coalesced requests can never replay a stale delta.
 */
internal class TameReconcileAuthority(
    readDesired: () -> Set<String>,
    reconcile: (Set<String>) -> TameReconcileResult,
    private val stopping: () -> Boolean,
    private val retryDelayMs: Long = 5_000L,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val onBacklogChanged: (Int) -> Unit = {},
) {
    private val dispatcher = KeyedLatestDispatcher<String, Unit>(
        threadName = "ha-paneld-tame",
        maxPendingKeys = 1,
    ) consume@{ _, _ ->
        reportBacklog()
        try {
            var failureRetries = 0
            while (!stopping()) {
                val desired = readDesired()
                val result = reconcile(desired)
                // A concurrent commit supersedes this pass. Re-read immediately rather than acting on a
                // submitted generation/delta; a queued signal remains as a harmless final consistency pass.
                if (readDesired() != desired) {
                    failureRetries = 0
                    continue
                }
                if (!result.retryableFailure) return@consume
                // Do not turn a permanently failing privileged command into months of polling. Durable desired
                // state plus ownership markers retain the mismatch for startup/the next config wake-up.
                if (failureRetries++ >= MAX_FAILURE_RETRIES) return@consume
                try {
                    sleep(retryDelayMs)
                } catch (_: InterruptedException) {
                    return@consume
                }
            }
        } finally {
            reportBacklog()
        }
    }

    fun request(): KeyedLatestDispatcher.Admission = dispatcher.submit(RECONCILE_KEY, Unit).also {
        reportBacklog()
    }

    fun closeAndJoin(timeoutMs: Long): Boolean = dispatcher.closeAndJoin(timeoutMs).also {
        reportBacklog()
    }

    private fun reportBacklog() {
        runCatching { onBacklogChanged(dispatcher.pendingCount()) }
    }

    private companion object {
        const val RECONCILE_KEY = "desired"
        const val MAX_FAILURE_RETRIES = 3
    }
}
