package io.github.maxlyth.hapaneld

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Optional Home Assistant narration for the native proximity journey. */
internal class ProximityWizardNarrator(
    private val speak: suspend (String, String, (Long) -> Unit) -> Unit,
    private val stopPlayback: (Long) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var current: Job? = null
    private var semanticPrompt: String? = null
    private var generation = 0L
    private var playbackOwner: Pair<Long, Long>? = null
    private var closed = false

    /** Repeated render polls are silent; a new instruction immediately supersedes obsolete speech. */
    fun narrate(prompt: String, text: String, localeTag: String): Boolean = synchronized(lock) {
        if (closed || prompt.isBlank() || text.isBlank() || localeTag.isBlank() || prompt == semanticPrompt) return false
        semanticPrompt = prompt
        generation++
        val mine = generation
        stopCurrentLocked()
        current?.cancel()
        current = scope.launch {
            try {
                speak(text, localeTag) { audioGeneration ->
                    synchronized(lock) {
                        if (closed || mine != generation) runCatching { stopPlayback(audioGeneration) }
                        else playbackOwner = mine to audioGeneration
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Narration is guidance. Home Assistant or playback failure never gates calibration.
            } finally {
                synchronized(lock) {
                    if (playbackOwner?.first == mine) playbackOwner = null
                }
            }
        }
        true
    }

    /** Leaving the Activity silences it and lets a later visible presentation speak afresh. */
    fun stop() = synchronized(lock) {
        semanticPrompt = null
        generation++
        stopCurrentLocked()
        current?.cancel()
        current = null
    }

    private fun stopCurrentLocked() {
        playbackOwner?.second?.let { audioGeneration -> runCatching { stopPlayback(audioGeneration) } }
        playbackOwner = null
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            semanticPrompt = null
            generation++
            stopCurrentLocked()
            current?.cancel()
            current = null
            scope.cancel()
        }
    }
}
