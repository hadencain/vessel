#include "grain.h"
#include "audio_buffer.h"
#include <cmath>

static constexpr float kPi = 3.14159265358979323846f;

void Grain::activate(const GrainParams& p, int outputSampleRate) {
    readPos_         = p.position;
    playbackRate_    = p.pitchRatio;
    totalSamples_    = (int32_t)(p.durationSec * outputSampleRate);
    if (totalSamples_ < 1) totalSamples_ = 1;
    samplesRendered_ = 0;
    amplitude_       = p.amplitude;
    envelope_        = p.envelope;
    active_          = true;
}

bool Grain::render(const AudioBuffer& buf, float& outL, float& outR) {
    if (!active_) { outL = outR = 0.0f; return false; }

    float env = applyEnvelope(samplesRendered_, totalSamples_);

    int nc = buf.numChannels();
    if (nc >= 2) {
        outL += amplitude_ * env * buf.interpolateHermite(readPos_, 0);
        outR += amplitude_ * env * buf.interpolateHermite(readPos_, 1);
    } else {
        float mono = amplitude_ * env * buf.interpolateHermite(readPos_, 0);
        outL += mono;
        outR += mono;
    }

    readPos_      += playbackRate_;
    samplesRendered_++;

    if (samplesRendered_ >= totalSamples_) {
        active_ = false;
        return false;
    }
    return true;
}

float Grain::applyEnvelope(int32_t idx, int32_t total) const {
    if (total <= 1) return 1.0f;
    float phase = (float)idx / (float)(total - 1);  // 0.0 → 1.0

    switch (envelope_) {
        case EnvelopeShape::Hann:
            return 0.5f * (1.0f - std::cos(2.0f * kPi * phase));

        case EnvelopeShape::Gaussian: {
            float x = (phase - 0.5f) * 4.0f;  // map to ~±2σ
            return std::exp(-0.5f * x * x);
        }

        case EnvelopeShape::Trapezoid: {
            // 10% attack, 80% sustain, 10% release
            if (phase < 0.1f)       return phase / 0.1f;
            else if (phase > 0.9f)  return (1.0f - phase) / 0.1f;
            else                    return 1.0f;
        }
    }
    return 1.0f;
}
