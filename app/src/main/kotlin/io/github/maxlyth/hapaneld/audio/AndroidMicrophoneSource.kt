package io.github.maxlyth.hapaneld.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "ha-paneld/mic"

/**
 * The panel's one microphone, shared through [MicrophoneFanOut].
 *
 * This class is only the `AudioRecord` half: it opens the recorder on the first lease, reads
 * 10 ms frames on a dedicated capture thread and releases it when the last lease goes. Everything
 * about leases, queues, fan-out and state lives in the platform-free core, which is why the
 * behaviour that matters is unit-testable without an emulator.
 *
 * [audioSource] is injectable so a setting can move the panel between `VOICE_RECOGNITION` (the
 * default: no AEC/AGC processing the wake-word detector would rather not have), `MIC` (raw) and
 * `VOICE_COMMUNICATION` (echo-cancelled, for a panel that plays TTS through its own speaker while
 * listening). Nothing else about the format is configurable — the contract's 16 kHz mono 16-bit
 * stream is what every consumer is written against.
 */
class AndroidMicrophoneSource(
    context: Context,
    audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
) : MicrophoneSource, MicrophoneSourceLifecycle {

    private val fanOut = MicrophoneFanOut(
        device = AudioRecordCaptureDevice(context.applicationContext, audioSource),
        logger = { message, error -> if (error != null) Log.w(TAG, message, error) else Log.i(TAG, message) },
    )

    override val state: StateFlow<MicState> get() = fanOut.state

    override fun lease(
        purpose: MicPurpose,
        priority: Int,
        consumer: PcmConsumer,
        queueFrames: Int,
    ): MicLease = fanOut.lease(purpose, priority, consumer, queueFrames)

    override fun shutdown(timeoutMs: Long): Boolean = fanOut.shutdown(timeoutMs)

    override fun close() = fanOut.close()
}

/**
 * One `AudioRecord`, opened and released by the capture thread that reads it.
 *
 * `stop` is the only entry point another thread uses, and it exists because `AudioRecord.read`
 * blocks: releasing the recorder underneath a blocked read is undefined, whereas stopping it makes
 * that read return so the owning thread can release it itself.
 */
internal class AudioRecordCaptureDevice(
    private val context: Context,
    private val audioSource: Int,
) : PcmCaptureDevice {

    @Volatile private var record: AudioRecord? = null

    /** Set by [stop]/[close] so a `read` that is already in flight reports a clean end, not a fault. */
    @Volatile private var stopping = false

    @SuppressLint("MissingPermission") // checked immediately below; a refusal is reported as a failed open
    override fun open(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO is not granted; microphone capture cannot start")
            return false
        }
        val minBytes = AudioRecord.getMinBufferSize(
            MicrophoneSource.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) {
            Log.w(TAG, "AudioRecord rejected the canonical 16 kHz mono format (getMinBufferSize=$minBytes)")
            return false
        }
        // At least twice the driver minimum, and never less than 80 ms, so a capture thread that is
        // briefly descheduled does not overrun the ring and lose audio the consumers never hear about.
        val bufferBytes = maxOf(minBytes * 2, MicrophoneSource.SAMPLES_PER_FRAME * BYTES_PER_SAMPLE * 8)
        stopping = false
        val opened = try {
            AudioRecord(
                audioSource,
                MicrophoneSource.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord could not be constructed", t)
            return false
        }
        if (opened.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord did not initialise (state=${opened.state})")
            runCatching { opened.release() }
            return false
        }
        try {
            opened.startRecording()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord could not start recording", t)
            runCatching { opened.release() }
            return false
        }
        if (opened.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(TAG, "AudioRecord did not enter the recording state")
            runCatching { opened.stop() }
            runCatching { opened.release() }
            return false
        }
        record = opened
        Log.i(TAG, "microphone open: source=$audioSource buffer=${bufferBytes}B")
        return true
    }

    override fun read(into: ShortArray, offsetSamples: Int, maxSamples: Int): Int {
        if (stopping) return 0
        val active = record ?: return 0
        val read = try {
            active.read(into, offsetSamples, maxSamples)
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord read threw", t)
            return -1
        }
        // A stop racing this read surfaces as an error code from a recorder that is no longer
        // recording. That is an ordinary end of capture, not a fault to latch.
        if (stopping) return 0
        return read
    }

    override fun stop() {
        stopping = true
        runCatching { record?.stop() }
            .onFailure { Log.w(TAG, "AudioRecord could not be stopped", it) }
    }

    override fun close() {
        stopping = true
        val closing = record ?: return
        record = null
        runCatching { closing.stop() }
        runCatching { closing.release() }
        Log.i(TAG, "microphone released")
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
    }
}
