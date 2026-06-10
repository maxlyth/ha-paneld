package io.github.maxlyth.hapaneld.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs submitted blocks on [scope] but **never lets two overlap** — each acquires a mutex first, so a block
 * always completes before the next begins. Used for `reconfigure()`, which stops + rebuilds + restarts the
 * MQTT/mDNS stack: two overlapping runs (e.g. a quick resubmit of the config form) would interleave
 * stop/build/start and leave the bridge stopped or half-built. The no-interleave property is unit-tested in
 * `SerializerTest` so the config-change race can't regress.
 */
class Serializer(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    fun launch(block: suspend () -> Unit): Job = scope.launch { mutex.withLock { block() } }
}
