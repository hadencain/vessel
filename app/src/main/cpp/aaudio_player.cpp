#include "aaudio_player.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "VesselAAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

bool AAudioPlayer::open(int32_t sampleRate, int32_t channelCount) {
    sampleRate_ = sampleRate;
    engine_.setSampleRate(sampleRate);

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        LOGE("createStreamBuilder failed: %s", AAudio_convertResultToText(result));
        return false;
    }

    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, channelCount);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setDataCallback(builder, dataCallback, this);
    AAudioStreamBuilder_setErrorCallback(builder, errorCallback, this);

    result = AAudioStreamBuilder_openStream(builder, &stream_);
    if (result != AAUDIO_OK) {
        LOGI("Exclusive mode unavailable (%s), falling back to shared",
             AAudio_convertResultToText(result));
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        result = AAudioStreamBuilder_openStream(builder, &stream_);
    }
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK) {
        LOGE("openStream failed: %s", AAudio_convertResultToText(result));
        stream_ = nullptr;
        return false;
    }

    LOGI("AAudio stream opened: rate=%d channels=%d", sampleRate, channelCount);
    return true;
}

bool AAudioPlayer::start() {
    if (!stream_) return false;
    aaudio_result_t r = AAudioStream_requestStart(stream_);
    if (r != AAUDIO_OK) {
        LOGE("requestStart failed: %s", AAudio_convertResultToText(r));
        return false;
    }
    LOGI("AAudio stream started");
    return true;
}

void AAudioPlayer::stop() {
    if (!stream_) return;
    AAudioStream_requestStop(stream_);
}

void AAudioPlayer::close() {
    if (!stream_) return;
    AAudioStream_close(stream_);
    stream_ = nullptr;
    engine_.reset();
}

aaudio_data_callback_result_t AAudioPlayer::dataCallback(
        AAudioStream* /*stream*/,
        void*    userData,
        void*    audioData,
        int32_t  numFrames) {

    // CRITICAL: NO allocation, NO locking, NO system calls, NO logging
    auto* player = static_cast<AAudioPlayer*>(userData);
    auto* out    = static_cast<float*>(audioData);

    // Engine zeros the buffer and fills grains
    player->engine_.process(out, numFrames, player->audioBuffer_);

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AAudioPlayer::errorCallback(
        AAudioStream* /*stream*/,
        void*         userData,
        aaudio_result_t error) {
    LOGE("AAudio error: %s — attempting restart", AAudio_convertResultToText(error));
    auto* player = static_cast<AAudioPlayer*>(userData);
    // Restart on audio device change (e.g., headphone plug/unplug)
    if (error == AAUDIO_ERROR_DISCONNECTED) {
        player->close();
        if (player->open()) player->start();
    }
}
