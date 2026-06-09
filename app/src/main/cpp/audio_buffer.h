#pragma once
#include <vector>
#include <atomic>
#include <cstdint>
#include <cmath>

// Holds decoded PCM float32 audio from the imported video.
// Written once from the JNI decode thread; read-only from the AAudio callback thread.
// Thread safety: ready_ acts as a release/acquire fence — load completes before
// the audio callback ever reads data (guaranteed by JNI sequencing in GranularBridge).
class AudioBuffer {
public:
    static constexpr int kDefaultSampleRate = 48000;

    void loadPcm(const float* data, int32_t numFrames, int numChannels);

    // Hermite cubic interpolation — safe to call from AAudio callback after isReady()
    float interpolateHermite(double position, int channel) const;

    int64_t numFrames()  const { return numFrames_.load(std::memory_order_acquire); }
    int     numChannels() const { return numChannels_.load(std::memory_order_relaxed); }
    bool    isReady()    const { return ready_.load(std::memory_order_acquire); }

    void clear();

private:
    std::vector<float>    data_;        // interleaved: [L0,R0,L1,R1,...]
    std::atomic<int64_t>  numFrames_{0};
    std::atomic<int>      numChannels_{2};
    std::atomic<bool>     ready_{false};

    float getSample(int64_t frame, int channel) const;
};
