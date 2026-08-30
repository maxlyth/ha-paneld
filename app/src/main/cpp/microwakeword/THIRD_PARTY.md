# Third-party code in the wake-word engine

`libhapaneld_mww.so` scores the bundled micro-wake-word models on the panel without any Java TFLite or LiteRT runtime. It is built from this directory only; nothing is fetched at build time, because the release build runs from a git archive without network access. Every upstream is pinned to an exact commit in [`tools/wakeword/vendor-upstream.sh`](../../../../../tools/wakeword/vendor-upstream.sh), which verifies the tarball SHA-256 before copying and re-derives the vendored subset by compiling the engine against the full upstream trees (`derive`), so the subset below is exactly the set of files the compiler reads for the host toolchain plus the `arm64-v8a`, `armeabi-v7a` and `x86_64` Android targets. `vendor-upstream.sh check` proves the committed tree still matches the pins. Only the subset is committed: run `vendor-upstream.sh apply` after a pin change rather than editing `third_party/` by hand. The copies are otherwise unmodified apart from line-ending and trailing-whitespace normalisation (CRLF to LF, trailing blanks and blank lines at end of file dropped), which the script applies identically in `apply` and `check`.

All components are licensed under the Apache License 2.0 unless stated; each licence text is copied verbatim beside its subset.

## Engine sources (this directory)

`MicroWakeWordEngine.{h,cpp}`, `MicroFrontendWrapper.{h,cpp}`, `MicroWakeWord_jni.cpp` and `Logging.h` derive from the `microwakeword` module of the Home Assistant Companion App for Android, `home-assistant/android` at commit `219565436870ef9fb09608ff771e0bc631c9a4d2` (`microwakeword/src/main/cpp/`), Apache-2.0. Divergences: the engine is a pure scorer that returns each inference's probability (the sliding window, warm-up and cooldown live in Kotlin so they are unit-testable), the interpreter arena is sized from the model manifest with a fixed floor, `reset()` also resets the interpreter's streaming variables, the JNI class and log tags are ha-paneld's, and the frontend writes into a caller-owned scratch vector instead of allocating per frame. The feature-extraction constants match the ESPHome `micro_wake_word` component so the published models see the features they were trained on.

## Vendored subsets (`third_party/`)

| Subset | Upstream | Commit | Tarball SHA-256 | Licence |
| --- | --- | --- | --- | --- |
| `tflite-micro/` | https://github.com/tensorflow/tflite-micro | `2747abd5c82a95fb1624106a946fc671c31f16e8` | `6ac0c8ef35f267cf3baf05dc094d4fd158864d4cc830d490b752994e58add8c8` | Apache-2.0 (`tflite-micro/LICENSE`) |
| `flatbuffers/` | https://github.com/google/flatbuffers (v23.5.26) | `0100f6a5779831fa7a651e4b67ef389a8752bd9b` | `85db3520acc4010b21984e2fb5ead3ec0d2c48df8009b614cb73562a82846554` | Apache-2.0 (`flatbuffers/LICENSE`) |
| `gemmlowp/` | https://github.com/google/gemmlowp | `fda83bdc38b118cc6b56753bd540caa49e570745` | `0f990732a0d541be514dfc5c1c45969626e9f349faa851baec01b9a9dcb4ae4b` | Apache-2.0 (`gemmlowp/LICENSE`) |
| `ruy/` | https://github.com/google/ruy | `54774a7a2cf85963777289193629d4bd42de4a59` | `91993e7eb2aa56e62e9f4abc1158b2b46333574aa81f1a7431b06df80a42b7fa` | Apache-2.0 (`ruy/LICENSE`) |
| `kissfft/` | https://github.com/mborgerding/kissfft (131.2.0) | `7bce4153c6bc8aba2db0e889e576f9d00505cbe1` | `7ad1124648a46977b16ddde03bf243bcd52fc452516c57016584ab4b4f2baadc` | BSD-3-Clause (`kissfft/COPYING`, `kissfft/LICENSES/BSD-3-Clause`) |

What each subset contains:

- `tflite-micro/`: the micro frontend (`tensorflow/lite/experimental/microfrontend/lib/`), the micro interpreter, allocators, memory planners and resource variables (`tensorflow/lite/micro/`), the reference kernels the streaming wake-word models use (`conv`, `depthwise_conv`, `fully_connected`, `pooling`, `reshape`, `softmax`, `logistic`, `add`, `mul`, `quantize`, `dequantize`, `activations`, `pad`, `concatenation`, `strided_slice`, `pack`, `unpack`, `split_v`, `reduce`, `call_once`, `var_handle`, `read_variable`, `assign_variable`), the core C API, flatbuffer conversions and the generated schema header. No optimised kernel backends (CMSIS-NN, Xtensa) are vendored; the reference kernels are what `TF_LITE_STATIC_MEMORY` builds use.
- `flatbuffers/`: the header-only reader under `include/flatbuffers/` that the schema header and verifier need.
- `gemmlowp/`: the fixed-point headers under `fixedpoint/` and `internal/detect_platform.h`.
- `ruy/`: `ruy/profiler/instrumentation.h` only (the interpreter's profiler hook; no ruy kernels).
- `kissfft/`: `kiss_fft.{h,c}`, `_kiss_fft_guts.h`, `kiss_fft_log.h` and `tools/kiss_fftr.{h,c}`, placed under `tools/` because the micro frontend includes them from there.

The pins for flatbuffers, gemmlowp and ruy are the versions tflite-micro's own `third_party/` configuration expects at the pinned commit; bump all four together.

## Bundled models (`app/src/main/assets/wakeword/`)

`okay_nabu`, `hey_jarvis`, `hey_mycroft` and `alexa` (`.tflite` plus the `.json` manifest each) come from https://github.com/esphome/micro-wake-word-models at commit `05b65922cc433c9df13e98e32a7fe520758c837e` (`models/v2/`), tarball SHA-256 `71176f2e11e81237bbe5ca32351fc09ac54a58ae3e34aa0f3b658da583de51b7`, Apache-2.0; the licence is copied to `assets/wakeword/LICENSE.txt`. The manifests carry the author credit for each model.
