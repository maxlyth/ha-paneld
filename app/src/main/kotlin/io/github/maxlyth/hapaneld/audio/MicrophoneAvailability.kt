package io.github.maxlyth.hapaneld.audio

/**
 * The one question the WebView permission gate asks about the panel's microphone.
 *
 * The panel has a single microphone and Android grants a single capture client, so the dashboard
 * page and ha-paneld's own features cannot both record. Letting the page open a recorder while a
 * lease is running takes the microphone away from one of them, at Android's discretion and with no
 * warning to either — so the page is allowed to capture only while the shared source is idle.
 *
 * This is a seam, not a policy. It answers "idle" until something points it at a real
 * [MicrophoneSource], which keeps the gate inert for a build where nothing leases the microphone
 * yet, and it deliberately holds a predicate rather than a source so the gate has no dependency on
 * how, or whether, capture is wired up.
 */
object MicrophoneAvailability {

    @Volatile
    var isIdle: () -> Boolean = { true }
        private set

    /** Answer from a live source: idle exactly while no lease is held. */
    fun observe(source: MicrophoneSource) {
        isIdle = { source.state.value is MicState.Closed }
    }

    /** Answer from an arbitrary predicate. */
    fun answerWith(predicate: () -> Boolean) {
        isIdle = predicate
    }

    /** Restore the default for a process that no longer has a source. */
    fun reset() {
        isIdle = { true }
    }
}
