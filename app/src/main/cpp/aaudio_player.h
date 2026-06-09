#pragma once
#include <aaudio/AAudio.h>
#include "granular_engine.h"
#include "audio_buffer.h"

class AAudioPlayer {
public:
    bool open(int32_t sampleRate = 48000, int32_t channelCount = 2);
    bool start();
    void stop();
    void close();

    GranularEngine& engine() { return engine_; }
    AudioBuffer&    buffer() { return audioBuffer_; }

    bool isOpen() const { return stream_ != nullptr; }

private:
    AAudioStream*  stream_      = nullptr;
    GranularEngine engine_;
    AudioBuffer    audioBuffer_;
    int32_t        sampleRate_  = 48000;

    static aaudio_data_callback_result_t dataCallback(
        AAudioStream* stream,
        void*         userData,
        void*         audioData,
        int32_t       numFrames);

    static void errorCallback(
        AAudioStream* stream,
        void*         userData,
        aaudio_result_t error);
};
