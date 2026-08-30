// Derived from the Home Assistant Companion App for Android (Apache-2.0); see THIRD_PARTY.md.
#ifndef HAPANELD_MWW_MICRO_FRONTEND_WRAPPER_H
#define HAPANELD_MWW_MICRO_FRONTEND_WRAPPER_H

#include <cstddef>
#include <cstdint>
#include <vector>

extern "C" {
#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"
}

// Number of mel filterbank features per frame (matches ESPHome PREPROCESSOR_FEATURE_SIZE)
constexpr size_t PREPROCESSOR_FEATURE_SIZE = 40;

/**
 * C++ wrapper for the TFLite Micro Frontend audio feature extractor, configured exactly like the
 * ESPHome micro_wake_word component so the published models see the features they were trained on.
 */
class MicroFrontendWrapper {
public:
    MicroFrontendWrapper(int sampleRate, size_t stepSizeMs);
    ~MicroFrontendWrapper();

    MicroFrontendWrapper(const MicroFrontendWrapper&) = delete;
    MicroFrontendWrapper& operator=(const MicroFrontendWrapper&) = delete;
    MicroFrontendWrapper(MicroFrontendWrapper&&) = delete;
    MicroFrontendWrapper& operator=(MicroFrontendWrapper&&) = delete;

    [[nodiscard]] bool isInitialized() const { return initialized_; }

    /**
     * Feed PCM16 samples; every completed window step yields one frame of
     * PREPROCESSOR_FEATURE_SIZE floats appended to [out]. Returns the number of frames produced.
     */
    size_t processSamples(const int16_t* samples, size_t numSamples, std::vector<float>& out);

    /** Reset noise estimates, PCAN state and the sample buffer. */
    void reset();

private:
    struct FrontendState state_{};
    int sampleRate_;
    size_t stepSizeMs_;
    bool initialized_ = false;
};

#endif // HAPANELD_MWW_MICRO_FRONTEND_WRAPPER_H
