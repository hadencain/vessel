#include "granular_engine.h"
#include <cstring>
#include <cmath>

GranularEngine::GranularEngine() : rng_(std::random_device{}()) {}

void GranularEngine::setGrainDensity(float v)      { grainsPerSec_.store(v); }
void GranularEngine::setGrainDuration(float ms)    { grainDurationMs_.store(ms); }
void GranularEngine::setPositionScatter(float v)   { positionScatter_.store(v); }
void GranularEngine::setPitchRandomRange(float s)  { pitchRandomSemitones_.store(s); }
void GranularEngine::setEnvelopeShape(EnvelopeShape s) { envelopeShape_.store((int)s); }
void GranularEngine::setMasterPosition(float v)    { masterPosition_.store(v); }
void GranularEngine::setContactMagnitude(float v)  { contactMagnitude_.store(v); }
void GranularEngine::setIdleMode(bool b)           { idleMode_.store(b); }
void GranularEngine::setSampleRate(int r)          { sampleRate_.store(r); }

void GranularEngine::reset() {
    for (auto& g : grainPool_) g = Grain{};
    samplesSinceLastGrain_ = 0.0;
    nextGrainIdx_ = 0;
}

Grain* GranularEngine::stealGrain() {
    // Round-robin — steal oldest active grain if pool is exhausted
    Grain* g = &grainPool_[nextGrainIdx_];
    nextGrainIdx_ = (nextGrainIdx_ + 1) % kMaxGrains;
    return g;
}

float GranularEngine::semitoneToRatio(float semitones) const {
    return std::pow(2.0f, semitones / 12.0f);
}

void GranularEngine::triggerGrain(const AudioBuffer& buf) {
    if (!buf.isReady()) return;

    int    sr      = sampleRate_.load();
    float  contact = contactMagnitude_.load();
    float  scatter = positionScatter_.load() + contact * 0.3f;
    float  pitchR  = pitchRandomSemitones_.load();
    float  durMs   = grainDurationMs_.load();
    float  master  = masterPosition_.load();
    int    envIdx  = envelopeShape_.load();

    int64_t bufFrames = buf.numFrames();
    if (bufFrames == 0) return;

    // Choose start position: master ± scatter/2, wrapped within buffer
    float halfScatter = scatter * 0.5f;
    float posNorm = master + (uniform01_(rng_) - 0.5f) * halfScatter * 2.0f;
    posNorm = posNorm - std::floor(posNorm);  // wrap [0, 1)
    double startFrame = posNorm * (double)bufFrames;

    // Pitch randomization
    float semitones = (uniform01_(rng_) * 2.0f - 1.0f) * pitchR;
    float pitchRatio = semitoneToRatio(semitones);

    GrainParams p;
    p.position    = startFrame;
    p.durationSec = durMs / 1000.0f;
    p.pitchRatio  = pitchRatio;
    p.amplitude   = 0.3f;  // grains sum additively; keep individual level modest
    p.envelope    = (EnvelopeShape)envIdx;

    Grain* g = stealGrain();
    g->activate(p, sr);
}

void GranularEngine::process(float* out, int32_t numFrames, const AudioBuffer& buf) {
    // Zero output — grains accumulate additively
    std::memset(out, 0, numFrames * 2 * sizeof(float));

    if (!buf.isReady()) return;

    float contact = contactMagnitude_.load();
    bool  idle    = idleMode_.load();

    // No sound if idle mode off and no hand contact
    if (!idle && contact < 0.01f) return;

    int   sr          = sampleRate_.load();
    float density     = grainsPerSec_.load() + contact * 12.0f;
    float samplesPerGrain = (float)sr / density;

    for (int32_t i = 0; i < numFrames; i++) {
        samplesSinceLastGrain_ += 1.0;
        if (samplesSinceLastGrain_ >= samplesPerGrain) {
            samplesSinceLastGrain_ -= samplesPerGrain;
            triggerGrain(buf);
        }

        float L = 0.0f, R = 0.0f;
        for (auto& g : grainPool_) {
            if (g.isActive()) {
                g.render(buf, L, R);
            }
        }

        out[i * 2]     = L;
        out[i * 2 + 1] = R;
    }
}
