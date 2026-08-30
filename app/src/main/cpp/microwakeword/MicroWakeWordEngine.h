// Derived from the Home Assistant Companion App for Android (Apache-2.0); see THIRD_PARTY.md.
// Divergence: this engine is a scorer only. It returns the model's per-inference probability and
// leaves the sliding-window, cutoff, warm-up and cooldown policy to the Kotlin WakeWordDetector so
// that policy is testable on the JVM against a fake scorer.
#ifndef HAPANELD_MWW_MICRO_WAKE_WORD_ENGINE_H
#define HAPANELD_MWW_MICRO_WAKE_WORD_ENGINE_H

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "MicroFrontendWrapper.h"
#include "tensorflow/lite/micro/micro_interpreter.h"
#include "tensorflow/lite/micro/micro_mutable_op_resolver.h"

/**
 * Feature extraction + TFLite Micro streaming inference for one micro-wake-word model.
 *
 * Mirrors ESPHome's streaming_model.cpp: MicroResourceVariables carry the streaming state, the
 * stride is read from the model input tensor, int8-quantized features are written straight into
 * the input tensor and the uint8 output probability is returned as-is.
 */
class MicroWakeWordEngine {
public:
    /** Result of feeding audio: the last inference's probability (0..255), or -1 if none ran. */
    static constexpr int NO_INFERENCE = -1;

    /**
     * @param modelData         TFLite flatbuffer (copied; the caller may free it afterwards)
     * @param modelSize         Size of the model data in bytes
     * @param sampleRate        Audio sample rate (16000 for the published models)
     * @param featureStepSizeMs Feature step size in milliseconds (from the model JSON)
     * @param tensorArenaBytes  Interpreter arena size; values below the built-in minimum are raised
     */
    MicroWakeWordEngine(
        const uint8_t* modelData,
        size_t modelSize,
        int sampleRate,
        int featureStepSizeMs,
        size_t tensorArenaBytes
    );

    MicroWakeWordEngine(const MicroWakeWordEngine&) = delete;
    MicroWakeWordEngine& operator=(const MicroWakeWordEngine&) = delete;
    MicroWakeWordEngine(MicroWakeWordEngine&&) = delete;
    MicroWakeWordEngine& operator=(MicroWakeWordEngine&&) = delete;

    [[nodiscard]] bool isInitialized() const { return initialized_; }

    /** Model input stride in feature frames (inferences run once per `stride` frames). */
    [[nodiscard]] int stride() const { return stride_; }

    /**
     * Feed PCM16 samples. Every completed feature frame is accumulated into the input tensor and
     * an inference runs once the stride is full.
     *
     * @return the probability (0..255) of the last inference triggered by this call, or
     *         NO_INFERENCE when the call completed no inference.
     */
    [[nodiscard]] int processAudio(const int16_t* samples, size_t numSamples);

    /** Reset frontend, stride accumulation and the model's streaming variables. */
    void reset();

private:
    // Lower bound for the interpreter arena. The model JSON's tensor_arena_size is the ESP32
    // figure; MicroAllocator's own bookkeeping on Android needs headroom on top of it.
    static constexpr size_t MIN_TENSOR_ARENA_SIZE = 64 * 1024;

    // Variable arena for streaming state. MicroAllocator::Create also places a
    // GreedyMemoryPlanner here, hence more than ESPHome's 1024.
    static constexpr size_t VARIABLE_ARENA_SIZE = 4096;

    // Max registered ops and max resource-variable slots, matching ESPHome.
    static constexpr int MAX_OPS = 20;
    static constexpr int MAX_RESOURCE_VARIABLES = 20;

    [[nodiscard]] bool loadModel();
    [[nodiscard]] bool registerOps();
    [[nodiscard]] int processFeatureFrame(const int8_t* features);

    MicroFrontendWrapper frontend_;
    std::vector<float> featureScratch_;

    float inputScale_ = 1.0f;
    int inputZeroPoint_ = 0;

    std::unique_ptr<uint8_t[]> modelCopy_;
    size_t modelSize_ = 0;
    std::vector<uint8_t> tensorArena_;
    std::vector<uint8_t> varArena_;
    tflite::MicroMutableOpResolver<MAX_OPS> opResolver_;
    tflite::MicroResourceVariables* resourceVariables_ = nullptr;
    std::unique_ptr<tflite::MicroInterpreter> interpreter_;

    int stride_ = 1;
    int currentStrideStep_ = 0;

    bool initialized_ = false;
};

#endif // HAPANELD_MWW_MICRO_WAKE_WORD_ENGINE_H
