#include "audio_buffer.h"
#include <algorithm>
#include <cstring>

void AudioBuffer::loadPcm(const float* data, int32_t numFrames, int numChannels) {
    ready_.store(false, std::memory_order_release);

    numChannels_.store(numChannels, std::memory_order_relaxed);
    int64_t totalSamples = (int64_t)numFrames * numChannels;

    data_.resize(totalSamples);
    std::copy(data, data + totalSamples, data_.begin());

    numFrames_.store(numFrames, std::memory_order_release);
    ready_.store(true, std::memory_order_release);
}

void AudioBuffer::clear() {
    ready_.store(false, std::memory_order_release);
    data_.clear();
    numFrames_.store(0, std::memory_order_release);
}

float AudioBuffer::getSample(int64_t frame, int channel) const {
    int64_t n = numFrames_.load(std::memory_order_relaxed);
    if (n == 0) return 0.0f;
    // Wrap with modulo for circular access
    frame = ((frame % n) + n) % n;
    channel = std::clamp(channel, 0, numChannels_ - 1);
    return data_[(size_t)(frame * numChannels_ + channel)];
}

// Hermite cubic interpolation (ported from granulator sibling's GrainBuffer).
// position is a fractional frame index. Wraps at buffer boundaries.
float AudioBuffer::interpolateHermite(double position, int channel) const {
    int64_t n = numFrames_.load(std::memory_order_relaxed);
    if (n == 0) return 0.0f;

    int64_t i  = (int64_t)position;
    float   frac = (float)(position - (double)i);

    float y0 = getSample(i - 1, channel);
    float y1 = getSample(i,     channel);
    float y2 = getSample(i + 1, channel);
    float y3 = getSample(i + 2, channel);

    // Catmull-Rom / Hermite coefficients
    float c0 = y1;
    float c1 = 0.5f * (y2 - y0);
    float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
    float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);

    return ((c3 * frac + c2) * frac + c1) * frac + c0;
}
