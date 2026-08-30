package io.github.maxlyth.hapaneld.assist.wakeword

import android.content.Context
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer

/**
 * JNI binding to `libhapaneld_mww.so` (`app/src/main/cpp/microwakeword/`): the TFLite Micro
 * frontend plus streaming interpreter that scores one micro-wake-word model. The library is
 * bundled for both supported ARM ABIs; [available] is false when it cannot be loaded, and callers then
 * report wake word detection as unavailable instead of failing.
 *
 * The native methods are registered from `JNI_OnLoad` against this class, so its name and
 * package are part of the binary contract.
 */
class NativeMicroWakeWord private constructor(
    private var handle: Long,
    /** Model input stride in feature frames: one inference per this many 10 ms steps. */
    val stride: Int,
) : WakeWordScorer {

    override fun score(samples: ShortArray, count: Int): Int {
        val h = handle
        if (h == 0L) return WakeWordScorer.NO_INFERENCE
        return nativeProcessAudio(h, samples, count)
    }

    override fun reset() {
        val h = handle
        if (h != 0L) nativeReset(h)
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeDestroy(h)
        }
    }

    companion object {
        private const val TAG = "ha-paneld/wake-word-ndk"
        private const val SAMPLE_RATE_HZ = 16_000

        /** True once the native library loaded. Loading is attempted on first use, never at class init. */
        val available: Boolean by lazy {
            try {
                System.loadLibrary("hapaneld_mww")
                true
            } catch (e: Throwable) {
                Log.i(TAG, "libhapaneld_mww not loadable on this ABI — wake word detection unavailable", e)
                false
            }
        }

        /**
         * Create a scorer for [config] from a direct [model] buffer, or null when the library is
         * unavailable or the model is rejected (logged natively).
         */
        fun create(model: ByteBuffer, config: MicroWakeWordModelConfig): NativeMicroWakeWord? {
            if (!available) return null
            if (!model.isDirect) {
                Log.w(TAG, "model buffer for ${config.id} is not direct")
                return null
            }
            val handle = nativeCreate(model, SAMPLE_RATE_HZ, config.featureStepSizeMs, config.tensorArenaSize)
            if (handle == 0L) {
                Log.w(TAG, "native engine rejected model ${config.id}")
                return null
            }
            return NativeMicroWakeWord(handle, nativeStride(handle))
        }

        /** Load a bundled model by id. Null when unavailable; throws only on an asset read failure. */
        @Throws(IOException::class)
        fun load(context: Context, id: String): NativeMicroWakeWord? {
            if (!available) return null
            val config = MicroWakeWordModelConfig.fromAssets(context, id)
            return create(MicroWakeWordModelConfig.readModel(context, config), config)
        }

        @JvmStatic
        private external fun nativeCreate(model: ByteBuffer, sampleRate: Int, featureStepSizeMs: Int, tensorArenaBytes: Int): Long

        @JvmStatic
        private external fun nativeProcessAudio(handle: Long, samples: ShortArray, count: Int): Int

        @JvmStatic
        private external fun nativeStride(handle: Long): Int

        @JvmStatic
        private external fun nativeReset(handle: Long)

        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }
}
