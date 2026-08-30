# libhapaneld_mww.so — micro-wake-word scorer (TFLite Micro Frontend + TFLite Micro interpreter).
# Loaded by assist/wakeword/NativeMicroWakeWord.kt via System.loadLibrary("hapaneld_mww").
#
# Every third-party source under third_party/ is a vendored, pinned subset (see THIRD_PARTY.md and
# tools/wakeword/vendor-upstream.sh). Nothing is fetched at build time: the sealed release build
# runs from a git archive without network access.

set(MWW_THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/third_party")
set(TFLM_ROOT "${MWW_THIRD_PARTY}/tflite-micro")
set(TFLITE_FRONTEND "${TFLM_ROOT}/tensorflow/lite/experimental/microfrontend/lib")
set(TFLM "${TFLM_ROOT}/tensorflow/lite/micro")
set(TFL "${TFLM_ROOT}/tensorflow/lite")
set(KISSFFT_ROOT "${MWW_THIRD_PARTY}/kissfft")

# Size-oriented flags scoped to the wake-word targets only (libhapaneld_led.so is untouched).
set(MWW_COMPILE_OPTIONS -O3 -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden)

# ------------------------------------------------------------------------------
# TFLite Micro Frontend (audio features) — C, fixed-point 16-bit to match model training
# ------------------------------------------------------------------------------
add_library(mww_frontend STATIC
    ${TFLITE_FRONTEND}/fft.cc
    ${TFLITE_FRONTEND}/fft_util.cc
    ${TFLITE_FRONTEND}/kiss_fft_int16.cc
    ${TFLITE_FRONTEND}/filterbank.c
    ${TFLITE_FRONTEND}/filterbank_util.c
    ${TFLITE_FRONTEND}/frontend.c
    ${TFLITE_FRONTEND}/frontend_util.c
    ${TFLITE_FRONTEND}/log_lut.c
    ${TFLITE_FRONTEND}/log_scale.c
    ${TFLITE_FRONTEND}/log_scale_util.c
    ${TFLITE_FRONTEND}/noise_reduction.c
    ${TFLITE_FRONTEND}/noise_reduction_util.c
    ${TFLITE_FRONTEND}/pcan_gain_control.c
    ${TFLITE_FRONTEND}/pcan_gain_control_util.c
    ${TFLITE_FRONTEND}/window.c
    ${TFLITE_FRONTEND}/window_util.c
)
target_include_directories(mww_frontend PUBLIC ${TFLM_ROOT} ${KISSFFT_ROOT})
target_compile_definitions(mww_frontend PUBLIC FIXED_POINT=16)
target_compile_features(mww_frontend PUBLIC cxx_std_17)
target_compile_options(mww_frontend PRIVATE ${MWW_COMPILE_OPTIONS}
    -Wno-unused-parameter -Wno-sign-compare
    -ffast-math  # third-party float paths only; the feature pipeline itself is FIXED_POINT=16
)
set_target_properties(mww_frontend PROPERTIES POSITION_INDEPENDENT_CODE ON)

# ------------------------------------------------------------------------------
# TFLite Micro interpreter + the kernels the streaming wake-word models use
# ------------------------------------------------------------------------------
add_library(mww_interpreter STATIC
    ${TFLM}/micro_interpreter.cc
    ${TFLM}/micro_allocator.cc
    ${TFLM}/micro_context.cc
    ${TFLM}/micro_op_resolver.cc
    ${TFLM}/micro_utils.cc
    ${TFLM}/micro_log.cc
    ${TFLM}/flatbuffer_utils.cc
    ${TFLM}/memory_helpers.cc
    ${TFLM}/micro_allocation_info.cc
    ${TFLM}/micro_interpreter_context.cc
    ${TFLM}/micro_interpreter_graph.cc
    ${TFLM}/micro_resource_variable.cc
    ${TFLM}/micro_profiler.cc
    ${TFLM}/micro_time.cc
    ${TFLM}/system_setup.cc
    ${TFLM}/fake_micro_context.cc
    ${TFLM}/debug_log.cc
    ${TFLM}/arena_allocator/single_arena_buffer_allocator.cc
    ${TFLM}/arena_allocator/non_persistent_arena_buffer_allocator.cc
    ${TFLM}/arena_allocator/persistent_arena_buffer_allocator.cc
    ${TFLM}/memory_planner/greedy_memory_planner.cc
    ${TFLM}/memory_planner/linear_memory_planner.cc
    ${TFLM}/memory_planner/non_persistent_buffer_planner_shim.cc
    ${TFLM}/tflite_bridge/micro_error_reporter.cc
    ${TFLM}/tflite_bridge/flatbuffer_conversions_bridge.cc
    ${TFLM_ROOT}/tensorflow/compiler/mlir/lite/core/api/error_reporter.cc
    ${TFL}/core/c/common.cc
    ${TFL}/core/api/flatbuffer_conversions.cc
    ${TFL}/core/api/tensor_utils.cc
    ${TFL}/kernels/kernel_util.cc
    ${TFL}/kernels/internal/common.cc
    ${TFL}/kernels/internal/quantization_util.cc
    ${TFL}/kernels/internal/portable_tensor_utils.cc
    ${TFL}/kernels/internal/tensor_ctypes.cc
    ${TFL}/kernels/internal/runtime_shape.cc
    ${TFL}/kernels/internal/reference/portable_tensor_utils.cc
    ${TFLM_ROOT}/tensorflow/compiler/mlir/lite/schema/schema_utils.cc
    ${TFLM}/kernels/conv.cc
    ${TFLM}/kernels/conv_common.cc
    ${TFLM}/kernels/depthwise_conv.cc
    ${TFLM}/kernels/depthwise_conv_common.cc
    ${TFLM}/kernels/fully_connected.cc
    ${TFLM}/kernels/fully_connected_common.cc
    ${TFLM}/kernels/pooling.cc
    ${TFLM}/kernels/pooling_common.cc
    ${TFLM}/kernels/reshape.cc
    ${TFLM}/kernels/reshape_common.cc
    ${TFLM}/kernels/softmax.cc
    ${TFLM}/kernels/softmax_common.cc
    ${TFLM}/kernels/logistic.cc
    ${TFLM}/kernels/logistic_common.cc
    ${TFLM}/kernels/add.cc
    ${TFLM}/kernels/add_common.cc
    ${TFLM}/kernels/mul.cc
    ${TFLM}/kernels/mul_common.cc
    ${TFLM}/kernels/quantize.cc
    ${TFLM}/kernels/quantize_common.cc
    ${TFLM}/kernels/dequantize.cc
    ${TFLM}/kernels/dequantize_common.cc
    ${TFLM}/kernels/activations.cc
    ${TFLM}/kernels/activations_common.cc
    ${TFLM}/kernels/pad.cc
    ${TFLM}/kernels/pad_common.cc
    ${TFLM}/kernels/concatenation.cc
    ${TFLM}/kernels/strided_slice.cc
    ${TFLM}/kernels/strided_slice_common.cc
    ${TFLM}/kernels/pack.cc
    ${TFLM}/kernels/unpack.cc
    ${TFLM}/kernels/kernel_util.cc
    ${TFLM}/kernels/micro_tensor_utils.cc
    ${TFLM}/kernels/call_once.cc
    ${TFLM}/kernels/var_handle.cc
    ${TFLM}/kernels/read_variable.cc
    ${TFLM}/kernels/assign_variable.cc
    ${TFLM}/kernels/split_v.cc
    ${TFLM}/kernels/reduce.cc
    ${TFLM}/kernels/reduce_common.cc
)
target_include_directories(mww_interpreter PUBLIC
    ${TFLM_ROOT}
    ${MWW_THIRD_PARTY}/flatbuffers/include
    ${MWW_THIRD_PARTY}/gemmlowp
    ${MWW_THIRD_PARTY}/ruy
)
target_compile_definitions(mww_interpreter PUBLIC TF_LITE_STATIC_MEMORY TF_LITE_DISABLE_X86_NEON)
target_compile_features(mww_interpreter PUBLIC cxx_std_17)
target_compile_options(mww_interpreter PRIVATE ${MWW_COMPILE_OPTIONS}
    -Wno-unused-parameter -Wno-sign-compare -Wno-missing-field-initializers
    -Wno-unused-variable -Wno-unused-function
    -ffast-math
)
set_target_properties(mww_interpreter PROPERTIES POSITION_INDEPENDENT_CODE ON)

# ------------------------------------------------------------------------------
# Engine (scorer) — shared by the JNI library and the host self-test
# ------------------------------------------------------------------------------
add_library(mww_engine STATIC MicroWakeWordEngine.cpp MicroFrontendWrapper.cpp)
target_include_directories(mww_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(mww_engine PUBLIC mww_frontend mww_interpreter)
target_compile_features(mww_engine PUBLIC cxx_std_17)
target_compile_options(mww_engine PRIVATE ${MWW_COMPILE_OPTIONS} -Wall -Wextra)
set_target_properties(mww_engine PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
    add_library(hapaneld_mww SHARED MicroWakeWord_jni.cpp)
    target_link_libraries(hapaneld_mww PRIVATE mww_engine log)
    target_compile_features(hapaneld_mww PRIVATE cxx_std_17)
    target_compile_options(hapaneld_mww PRIVATE ${MWW_COMPILE_OPTIONS} -Wall -Wextra)
    # --gc-sections drops the interpreter/kernels the model ops never reach; the 16 KB max page
    # size keeps the library loadable on 16 KB-page arm64 devices (Android 15+ requirement).
    # --exclude-libs keeps the static libc++ out of the export table, so JNI_OnLoad is the only
    # exported symbol.
    target_link_options(hapaneld_mww PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
else()
    # Host self-test: scores a raw PCM16 file and prints one probability per inference.
    add_executable(mww_selftest selftest/mww_selftest.cpp)
    target_link_libraries(mww_selftest PRIVATE mww_engine)
    target_compile_features(mww_selftest PRIVATE cxx_std_17)
endif()
