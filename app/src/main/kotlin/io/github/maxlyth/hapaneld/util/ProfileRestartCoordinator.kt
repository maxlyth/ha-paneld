package io.github.maxlyth.hapaneld.util

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-flight service restart requested after an HTTP profile activation response has been queued.
 *
 * The service owner supplies a scheduler and controlled process-restart callback. ha-paneld's foreground
 * service is START_STICKY, so process death lets Android recreate it without attempting a background
 * startForegroundService call that Android 12+ may reject. Process death also makes overlapping hardware
 * owners impossible.
 */
class ProfileRestartCoordinator(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val restartProcess: () -> Unit,
    private val responseGraceMs: Long = RESPONSE_GRACE_MS,
) {
    private val requested = AtomicBoolean(false)

    /** Returns false when an activation has already scheduled this process's one restart. */
    fun request(): Boolean {
        if (!requested.compareAndSet(false, true)) return false
        schedule(responseGraceMs, restartProcess)
        return true
    }

    companion object {
        const val RESPONSE_GRACE_MS = 350L
    }
}
