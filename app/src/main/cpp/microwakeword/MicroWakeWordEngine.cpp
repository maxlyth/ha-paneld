// Derived from the Home Assistant Companion App for Android (Apache-2.0); see THIRD_PARTY.md.
#include "MicroWakeWordEngine.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "Logging.h"
#include "flatbuffers/flatbuffers.h"
#include "tensorflow/lite/micro/micro_interpreter.h"
#include "tensorflow/lite/micro/micro_mutable_op_resolver.h"
#include "tensorflow/lite/micro/micro_resource_variable.h"
#include "tensorflow/lite/schema/schema_generated.h"

static constexpr char LOG_TAG[] = "ha-paneld/mww-engine";

MicroWakeWordEngine::MicroWakeWordEngine(
    const uint8_t* modelData,
    size_t modelSize,
    int sampleRate,
    int featureStepSizeMs,
    size_t tensorArenaBytes
)
    : frontend_(sampleRate, static_cast<size_t>(std::max(1, featureStepSizeMs)))
    , tensorArena_(std::max(tensorArenaBytes, MIN_TENSOR_ARENA_SIZE), 0)
    , varArena_(VARIABLE_ARENA_SIZE, 0)
{
    if (featureStepSizeMs <= 0) {
        LOGE(LOG_TAG, "Invalid featureStepSizeMs: %d (must be > 0)", featureStepSizeMs);
        return;
    }
    if (modelData == nullptr || modelSize == 0) {
        LOGE(LOG_TAG, "Empty model");
        return;
    }
    if (!frontend_.isInitialized()) {
        LOGE(LOG_TAG, "Frontend initialization failed");
        return;
    }

    modelSize_ = modelSize;
    modelCopy_ = std::make_unique<uint8_t[]>(modelSize);
    std::memcpy(modelCopy_.get(), modelData, modelSize);

    if (!loadModel()) {
        return;
    }

    initialized_ = true;
}

bool MicroWakeWordEngine::registerOps() {
    // Operators matching ESPHome's streaming_model register_streaming_ops_
    if (opResolver_.AddCallOnce() != kTfLiteOk) return false;
    if (opResolver_.AddVarHandle() != kTfLiteOk) return false;
    if (opResolver_.AddReshape() != kTfLiteOk) return false;
    if (opResolver_.AddReadVariable() != kTfLiteOk) return false;
    if (opResolver_.AddStridedSlice() != kTfLiteOk) return false;
    if (opResolver_.AddConcatenation() != kTfLiteOk) return false;
    if (opResolver_.AddAssignVariable() != kTfLiteOk) return false;
    if (opResolver_.AddConv2D() != kTfLiteOk) return false;
    if (opResolver_.AddMul() != kTfLiteOk) return false;
    if (opResolver_.AddAdd() != kTfLiteOk) return false;
    if (opResolver_.AddMean() != kTfLiteOk) return false;
    if (opResolver_.AddFullyConnected() != kTfLiteOk) return false;
    if (opResolver_.AddLogistic() != kTfLiteOk) return false;
    if (opResolver_.AddQuantize() != kTfLiteOk) return false;
    if (opResolver_.AddDepthwiseConv2D() != kTfLiteOk) return false;
    if (opResolver_.AddAveragePool2D() != kTfLiteOk) return false;
    if (opResolver_.AddMaxPool2D() != kTfLiteOk) return false;
    if (opResolver_.AddPad() != kTfLiteOk) return false;
    if (opResolver_.AddPack() != kTfLiteOk) return false;
    if (opResolver_.AddSplitV() != kTfLiteOk) return false;
    return true;
}

bool MicroWakeWordEngine::loadModel() {
    if (!registerOps()) {
        LOGE(LOG_TAG, "Failed to register TFLite operators");
        return false;
    }

    // Both MicroAllocator and MicroResourceVariables are placement-allocated into varArena_,
    // so they need no explicit deallocation.
    auto* microAllocator = tflite::MicroAllocator::Create(varArena_.data(), varArena_.size());
    if (microAllocator == nullptr) {
        LOGE(LOG_TAG, "Could not create MicroAllocator for variable arena");
        return false;
    }
    resourceVariables_ = tflite::MicroResourceVariables::Create(microAllocator, MAX_RESOURCE_VARIABLES);
    if (resourceVariables_ == nullptr) {
        LOGE(LOG_TAG, "Could not create MicroResourceVariables");
        return false;
    }

    flatbuffers::Verifier verifier(modelCopy_.get(), modelSize_);
    if (!tflite::VerifyModelBuffer(verifier)) {
        LOGE(LOG_TAG, "Invalid TFLite model flatbuffer");
        return false;
    }

    const tflite::Model* model = tflite::GetModel(modelCopy_.get());
    if (model == nullptr) {
        LOGE(LOG_TAG, "Failed to parse TFLite model");
        return false;
    }

    auto interpreter = std::make_unique<tflite::MicroInterpreter>(
        model, opResolver_, tensorArena_.data(), tensorArena_.size(), resourceVariables_
    );

    if (interpreter->AllocateTensors() != kTfLiteOk) {
        LOGE(LOG_TAG, "Failed to allocate tensors (arena %zu bytes)", tensorArena_.size());
        return false;
    }

    TfLiteTensor* input = interpreter->input(0);
    if (input == nullptr) {
        LOGE(LOG_TAG, "Model input tensor is null");
        return false;
    }
    if (input->dims->size != 3) {
        LOGE(LOG_TAG, "Model input tensor has wrong rank (expected 3, got %d)", input->dims->size);
        return false;
    }
    if (input->dims->data[0] != 1 || input->dims->data[2] != static_cast<int>(PREPROCESSOR_FEATURE_SIZE)) {
        LOGE(LOG_TAG, "Model input tensor has unexpected dimensions (expected [1, stride, %zu], got [%d, %d, %d])",
             PREPROCESSOR_FEATURE_SIZE, input->dims->data[0], input->dims->data[1], input->dims->data[2]);
        return false;
    }
    if (input->type != kTfLiteInt8) {
        LOGE(LOG_TAG, "Model input tensor is not int8");
        return false;
    }

    stride_ = std::max(1, input->dims->data[1]);

    if (input->quantization.type == kTfLiteAffineQuantization) {
        auto* params = static_cast<TfLiteAffineQuantization*>(input->quantization.params);
        if (params != nullptr && params->scale != nullptr && params->zero_point != nullptr) {
            inputScale_ = params->scale->data[0];
            inputZeroPoint_ = params->zero_point->data[0];
        }
    }

    TfLiteTensor* output = interpreter->output(0);
    if (output == nullptr) {
        LOGE(LOG_TAG, "Model output tensor is null");
        return false;
    }
    if (output->dims->size != 2) {
        LOGE(LOG_TAG, "Model output tensor has wrong rank (expected 2, got %d)", output->dims->size);
        return false;
    }
    if (output->dims->data[0] != 1 || output->dims->data[1] != 1) {
        LOGE(LOG_TAG, "Model output tensor has unexpected dimensions (expected [1, 1], got [%d, %d])",
             output->dims->data[0], output->dims->data[1]);
        return false;
    }
    if (output->type != kTfLiteUInt8) {
        LOGE(LOG_TAG, "Model output tensor is not uint8");
        return false;
    }

    interpreter_ = std::move(interpreter);

    LOGD(LOG_TAG, "Engine initialized: stride=%d, inputScale=%.6f, inputZeroPoint=%d, arena=%zu bytes (used %zu)",
         stride_, inputScale_, inputZeroPoint_, tensorArena_.size(), interpreter_->arena_used_bytes());

    return true;
}

int MicroWakeWordEngine::processAudio(const int16_t* samples, size_t numSamples) {
    if (!initialized_) return NO_INFERENCE;

    featureScratch_.clear();
    const size_t frames = frontend_.processSamples(samples, numSamples, featureScratch_);

    int last = NO_INFERENCE;
    for (size_t frame = 0; frame < frames; ++frame) {
        const float* values = featureScratch_.data() + frame * PREPROCESSOR_FEATURE_SIZE;
        int8_t quantizedFeatures[PREPROCESSOR_FEATURE_SIZE]{};
        for (size_t index = 0; index < PREPROCESSOR_FEATURE_SIZE; index++) {
            float quantized = (values[index] / inputScale_) + static_cast<float>(inputZeroPoint_);
            int rounded = static_cast<int>(std::round(quantized));
            quantizedFeatures[index] = static_cast<int8_t>(std::max(-128, std::min(127, rounded)));
        }
        const int probability = processFeatureFrame(quantizedFeatures);
        if (probability != NO_INFERENCE) {
            last = probability;
        }
    }
    return last;
}

int MicroWakeWordEngine::processFeatureFrame(const int8_t* features) {
    TfLiteTensor* input = interpreter_->input(0);
    if (input == nullptr) return NO_INFERENCE;

    // Place features at the current stride position in the input tensor
    // (matches ESPHome's stride-based accumulation)
    currentStrideStep_ = currentStrideStep_ % stride_;
    std::memcpy(
        tflite::GetTensorData<int8_t>(input) + PREPROCESSOR_FEATURE_SIZE * currentStrideStep_,
        features, PREPROCESSOR_FEATURE_SIZE
    );
    ++currentStrideStep_;

    if (currentStrideStep_ < stride_) {
        return NO_INFERENCE;
    }

    if (interpreter_->Invoke() != kTfLiteOk) {
        LOGE(LOG_TAG, "TFLite inference failed");
        return NO_INFERENCE;
    }

    TfLiteTensor* output = interpreter_->output(0);
    if (output == nullptr) {
        LOGE(LOG_TAG, "Model output tensor is null after inference");
        return NO_INFERENCE;
    }
    return static_cast<int>(output->data.uint8[0]);
}

void MicroWakeWordEngine::reset() {
    currentStrideStep_ = 0;
    frontend_.reset();
    if (interpreter_ != nullptr) {
        // Clears the input tensor plus the model's streaming variables, so a new stream does not
        // inherit the previous one's context.
        interpreter_->Reset();
    }
}
