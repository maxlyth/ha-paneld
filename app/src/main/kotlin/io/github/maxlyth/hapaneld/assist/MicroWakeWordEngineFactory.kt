package io.github.maxlyth.hapaneld.assist

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.assist.wakeword.WakeWordDetector
import io.github.maxlyth.hapaneld.audio.PcmFrame

/**
 * Arms the bundled microWakeWord models as the coordinator's listener.
 *
 * Returns null whenever no listener can run here — the native engine did not load for this ABI, no
 * bundled model matched the requested ids, or nothing was requested. That is not a failure: the
 * coordinator then runs press-to-speak and holds no microphone while idle, which is exactly what a
 * panel without a usable engine should do.
 */
internal class MicroWakeWordEngineFactory(private val context: Context) : WakeWordEngineFactory {
    override fun create(modelIds: List<String>, onActivation: (WakeWordActivation) -> Unit): WakeWordEngine? {
        val models = WakeWordDetector.loadBundled(context, modelIds, VoiceSettings.MAX_ACTIVE_WAKE_WORDS)
        if (models.isNullOrEmpty()) {
            Log.i(TAG, "no wake-word model could be loaded; the voice assistant stays press-to-speak")
            return null
        }
        val detector = WakeWordDetector(
            models = models,
            listener = { hit -> onActivation(WakeWordActivation(hit.modelId, hit.phrase)) },
            maxActive = VoiceSettings.MAX_ACTIVE_WAKE_WORDS,
        )
        Log.i(TAG, "wake-word listener armed for ${models.size} model(s)")
        return object : WakeWordEngine {
            override fun onFrame(frame: PcmFrame) = detector.onFrame(frame)
            override fun onDropped(count: Int) = detector.onDropped(count)
            override fun close() = detector.close()
        }
    }

    private companion object {
        const val TAG = "hapaneld"
    }
}
