#pragma once
#include <cstdint>

class AudioBuffer;

enum class EnvelopeShape : uint8_t {
    Hann      = 0,
    Gaussian  = 1,
    Trapezoid = 2
};

struct GrainParams {
    double        position;       // start frame in AudioBuffer
    float         durationSec;    // grain duration in seconds
    float         pitchRatio;     // playback rate (1.0 = original pitch)
    float         amplitude;      // 0.0–1.0
    EnvelopeShape envelope;
};

class Grain {
public:
    void  activate(const GrainParams& p, int outputSampleRate);
    bool  isActive() const { return active_; }

    // Renders one output sample pair and advances the read position.
    // Returns false when the grain has finished (caller should deactivate).
    bool render(const AudioBuffer& buf, float& outL, float& outR);

private:
    bool          active_          = false;
    double        readPos_         = 0.0;
    double        playbackRate_    = 1.0;
    int32_t       totalSamples_    = 0;
    int32_t       samplesRendered_ = 0;
    float         amplitude_       = 1.0f;
    EnvelopeShape envelope_        = EnvelopeShape::Hann;

    float applyEnvelope(int32_t idx, int32_t total) const;
};
