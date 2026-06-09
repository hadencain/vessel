#pragma once
#include "grain.h"
#include "audio_buffer.h"
#include <atomic>
#include <random>
#include <array>
#include <cstdint>

class GranularEngine {
public:
    static constexpr int kMaxGrains = 64;

    GranularEngine();

    // Thread-safe setters — called from Kotlin/JNI on the main thread
    void setGrainDensity(float grainsPerSec);
    void setGrainDuration(float ms);
    void setPositionScatter(float fraction);   // 0–1
    void setPitchRandomRange(float semitones);
    void setEnvelopeShape(EnvelopeShape shape);
    void setMasterPosition(float fraction);    // 0–1 within AudioBuffer
    void setContactMagnitude(float mag);       // 0–1 from hand interaction
    void setIdleMode(bool idle);
    void setSampleRate(int rate);

    // Called from AAudio callback — must be real-time safe (no alloc, no lock, no log)
    void process(float* outputBuffer, int32_t numFrames, const AudioBuffer& buf);

    void reset();

private:
    std::array<Grain, kMaxGrains> grainPool_;
    int nextGrainIdx_ = 0;

    // Parameters — atomics for cross-thread safety
    std::atomic<float> grainsPerSec_{4.0f};
    std::atomic<float> grainDurationMs_{120.0f};
    std::atomic<float> positionScatter_{0.2f};
    std::atomic<float> pitchRandomSemitones_{0.0f};
    std::atomic<int>   envelopeShape_{0};
    std::atomic<float> masterPosition_{0.0f};
    std::atomic<float> contactMagnitude_{0.0f};
    std::atomic<bool>  idleMode_{true};
    std::atomic<int>   sampleRate_{48000};

    // Grain scheduler state — audio thread only, no atomics needed
    double samplesSinceLastGrain_ = 0.0;

    std::mt19937                          rng_;
    std::uniform_real_distribution<float> uniform01_{0.0f, 1.0f};

    Grain* stealGrain();
    void   triggerGrain(const AudioBuffer& buf);
    float  semitoneToRatio(float semitones) const;
};
