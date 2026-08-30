// Host self-test for the wake-word scorer. Not part of the Android build.
//
// Usage: mww_selftest <model.tflite> <step_ms> <arena_bytes> <pcm16le.raw|-> [chunk_samples]
// Feeds the PCM in chunks (default 160 samples = 10 ms at 16 kHz), prints one line per inference
// "<chunk_index> <probability>" and a final summary "frames=<n> inferences=<n> stride=<n> max=<p>".
// "-" as the PCM path scores 3 s of silence. Exit status is nonzero when the engine fails to
// initialise or reset breaks subsequent inference.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "MicroWakeWordEngine.h"

static std::vector<uint8_t> readFile(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    return std::vector<uint8_t>(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
}

int main(int argc, char** argv) {
    if (argc < 5) {
        std::fprintf(stderr, "usage: %s <model.tflite> <step_ms> <arena_bytes> <pcm16le.raw|-> [chunk_samples]\n", argv[0]);
        return 2;
    }
    const std::vector<uint8_t> model = readFile(argv[1]);
    if (model.empty()) {
        std::fprintf(stderr, "cannot read model %s\n", argv[1]);
        return 2;
    }
    const int stepMs = std::atoi(argv[2]);
    const size_t arena = static_cast<size_t>(std::atol(argv[3]));
    const size_t chunk = argc > 5 ? static_cast<size_t>(std::atol(argv[5])) : 160;

    std::vector<int16_t> pcm;
    if (std::string(argv[4]) == "-") {
        pcm.assign(16000 * 3, 0);
    } else {
        const std::vector<uint8_t> raw = readFile(argv[4]);
        pcm.resize(raw.size() / 2);
        for (size_t i = 0; i < pcm.size(); ++i) {
            pcm[i] = static_cast<int16_t>(raw[2 * i] | (raw[2 * i + 1] << 8));
        }
    }

    MicroWakeWordEngine engine(model.data(), model.size(), 16000, stepMs, arena);
    if (!engine.isInitialized()) {
        std::fprintf(stderr, "engine failed to initialise\n");
        return 1;
    }

    size_t chunks = 0;
    size_t inferences = 0;
    int max = 0;
    for (size_t offset = 0; offset + chunk <= pcm.size(); offset += chunk, ++chunks) {
        const int p = engine.processAudio(pcm.data() + offset, chunk);
        if (p != MicroWakeWordEngine::NO_INFERENCE) {
            ++inferences;
            if (p > max) max = p;
            std::printf("%zu %d\n", chunks, p);
        }
    }

    // A reset must leave the engine scoring again: feed one stride worth of frames and require
    // an inference to come back.
    engine.reset();
    size_t afterReset = 0;
    std::vector<int16_t> silence(chunk, 0);
    // Feed enough chunks to complete the 30 ms window plus one stride.
    const size_t warm = 3 + static_cast<size_t>(engine.stride());
    for (size_t i = 0; i < warm * 2; ++i) {
        if (engine.processAudio(silence.data(), silence.size()) != MicroWakeWordEngine::NO_INFERENCE) ++afterReset;
    }
    std::printf("frames=%zu inferences=%zu stride=%d max=%d after_reset_inferences=%zu\n",
                chunks, inferences, engine.stride(), max, afterReset);
    return afterReset > 0 ? 0 : 1;
}
