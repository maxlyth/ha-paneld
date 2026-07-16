package io.github.maxlyth.hapaneld.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

/**
 * Owns the concrete resource set governed by [RuntimeLifecycleCoordinator].
 *
 * The lower-level coordinator serializes state transitions, while this owner keeps the resource value and generation together. Background producers receive an [Observation] containing both, so they cannot read a generation from one runtime and then act on a replacement resource reached through a mutable field. Reconfiguration stops the captured value, builds and publishes one replacement, and starts that exact value before the transition becomes RUNNING.
 */
internal class ServiceRuntimeOwner<T : Any>(
    initial: T,
    threadName: String,
    onError: (operation: String, error: Throwable) -> Unit = { _, _ -> },
    onRecoverySaturated: () -> Unit = {},
) {
    data class Observation<T : Any>(val generation: Long, val value: T)

    private val lifecycle = RuntimeLifecycleCoordinator(threadName, onError, onRecoverySaturated)
    @Volatile private var current = initial

    fun current(): T = current

    fun snapshot(): RuntimeLifecycleCoordinator.Snapshot = lifecycle.snapshot()

    fun observe(): Observation<T>? {
        val generation = lifecycle.currentGeneration() ?: return null
        val value = current
        return Observation(generation, value).takeIf { isCurrent(it) }
    }

    fun isCurrent(observation: Observation<T>): Boolean =
        current === observation.value && lifecycle.isCurrent(observation.generation)

    fun start(block: (T) -> Unit): Future<Boolean> = lifecycle.start { block(current) }

    fun reconfigure(
        stop: (T) -> Unit,
        build: (previous: T) -> T,
        start: (T) -> Unit,
        complete: (T) -> Unit = {},
    ): Future<Boolean> = lifecycle.reconfigure {
        val previous = current
        stop(previous)
        val replacement = build(previous)
        current = replacement
        start(replacement)
        complete(replacement)
    }

    fun reconnect(observation: Observation<T>, block: (T) -> Unit): Future<Boolean> {
        if (current !== observation.value) return CompletableFuture.completedFuture(false)
        return lifecycle.reconnect(observation.generation) { block(observation.value) }
    }

    fun shutdown(timeoutMs: Long, block: (T) -> Unit): Boolean =
        lifecycle.shutdown(timeoutMs) { block(current) }
}
