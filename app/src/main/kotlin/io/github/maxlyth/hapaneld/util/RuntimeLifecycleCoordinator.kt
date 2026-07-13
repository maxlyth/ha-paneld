package io.github.maxlyth.hapaneld.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single owner for a service runtime's start/reconfigure/reconnect/stop transitions.
 *
 * Every accepted transition runs on one dedicated lane, so blocking work cannot starve behind unrelated
 * Dispatchers.IO callers and two lifecycle mutations cannot overlap. [generation] changes before each
 * start/reconfigure; reconnect requests must carry the generation they observed and are discarded when
 * they finally reach the lane if that runtime has since been replaced. An accepted reconnect runs on a
 * recovery worker so a wedged dependency cannot block later lifecycle admission. Its action must capture
 * the terminal runtime instance for [expectedGeneration], never read a mutable current-runtime field.
 *
 * [shutdown] closes admission synchronously, then queues final teardown behind any transition already in
 * flight. Work that was queued but had not started is discarded. The caller may bound how long it waits,
 * but teardown remains queued and owns the lane even if that wait expires.
 */
class RuntimeLifecycleCoordinator(
    threadName: String,
    private val onError: (operation: String, error: Throwable) -> Unit = { _, _ -> },
) {
    enum class State { NEW, STARTING, RUNNING, RECONFIGURING, FAILED, STOPPING, STOPPED }

    data class Snapshot(val state: State, val generation: Long)

    private val lock = Any()
    private val lane: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, threadName).apply { isDaemon = true }
    }
    private val recoverySequence = AtomicInteger()
    private val recovery: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "$threadName-reconnect-${recoverySequence.incrementAndGet()}").apply { isDaemon = true }
    }

    @Volatile private var view = Snapshot(State.NEW, 0L)
    private var stopping = false
    private var shutdownFuture: Future<Boolean>? = null

    fun snapshot(): Snapshot = view

    /** The generation reconnect producers may target, or null while no coherent runtime is active. */
    fun currentGeneration(): Long? = view.takeIf { it.state == State.RUNNING }?.generation

    /** Whether [generation] still names the coherent running runtime observed by background work. */
    fun isCurrent(generation: Long): Boolean = view.let { it.state == State.RUNNING && it.generation == generation }

    /** Queue the one initial start. Duplicate starts are rejected when they reach the lane. */
    fun start(block: () -> Unit): Future<Boolean> = submit("start") {
        transition(State.STARTING, setOf(State.NEW), block)
    }

    /** Queue a full stop/build/start replacement. Requests made during STARTING wait behind it. */
    fun reconfigure(block: () -> Unit): Future<Boolean> = submit("reconfigure") {
        transition(State.RECONFIGURING, setOf(State.RUNNING, State.FAILED), block)
    }

    /** Queue reconnect work only for the still-current runtime generation. */
    fun reconnect(expectedGeneration: Long, block: () -> Unit): Future<Boolean> {
        val result = CompletableFuture<Boolean>()
        synchronized(lock) {
            if (stopping) return CompletableFuture.completedFuture(false)
            lane.execute {
                val accepted = synchronized(lock) {
                    !stopping && view.state == State.RUNNING && view.generation == expectedGeneration
                }
                if (!accepted) {
                    result.complete(false)
                } else {
                    recovery.execute {
                        try {
                            block()
                            result.complete(true)
                        } catch (error: Throwable) {
                            onError("reconnect", error)
                            result.complete(false)
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Close admission and run final teardown after the active transition. Returns false on timeout or a
     * teardown exception; a timeout does not cancel teardown because doing so could strand resources.
     */
    fun shutdown(timeoutMs: Long, block: () -> Unit): Boolean {
        val future = synchronized(lock) {
            shutdownFuture?.let { return@synchronized it }
            stopping = true
            view = Snapshot(State.STOPPING, view.generation)
            lane.submit<Boolean> {
                var success = true
                try {
                    block()
                } catch (error: Throwable) {
                    success = false
                    onError("shutdown", error)
                } finally {
                    recovery.shutdown()
                    synchronized(lock) { view = Snapshot(State.STOPPED, view.generation) }
                }
                success
            }.also {
                shutdownFuture = it
                lane.shutdown()
            }
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            false
        } catch (error: Throwable) {
            onError("shutdown-wait", error)
            false
        }
    }

    private fun submit(operation: String, block: () -> Boolean): Future<Boolean> = synchronized(lock) {
        if (stopping) return@synchronized CompletableFuture.completedFuture(false)
        lane.submit<Boolean> {
            try {
                block()
            } catch (error: Throwable) {
                onError(operation, error)
                false
            }
        }
    }

    private fun transition(target: State, allowed: Set<State>, block: () -> Unit): Boolean {
        synchronized(lock) {
            if (stopping || view.state !in allowed) return false
            view = Snapshot(target, view.generation + 1L)
        }
        var success = true
        try {
            block()
        } catch (error: Throwable) {
            success = false
            onError(target.name.lowercase(), error)
        } finally {
            synchronized(lock) {
                if (!stopping) view = Snapshot(if (success) State.RUNNING else State.FAILED, view.generation)
            }
        }
        return success
    }
}
