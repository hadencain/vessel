#include <jni.h>
#include "aaudio_player.h"

// Singleton owned by the native layer for the lifetime of the process.
// Kotlin holds no raw pointer — all access is through these JNI entry points.
static AAudioPlayer gPlayer;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_vessel_GranularBridge_nativeInit(JNIEnv*, jobject) {
    if (gPlayer.isOpen()) gPlayer.close();
    return gPlayer.open() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_vessel_GranularBridge_nativeLoadPcm(
        JNIEnv* env, jobject,
        jfloatArray data, jint numFrames, jint numChannels) {
    jfloat* pcm = env->GetFloatArrayElements(data, nullptr);
    gPlayer.buffer().loadPcm(pcm, numFrames, numChannels);
    env->ReleaseFloatArrayElements(data, pcm, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_vessel_GranularBridge_nativeStart(JNIEnv*, jobject) {
    gPlayer.start();
}

JNIEXPORT void JNICALL
Java_com_vessel_GranularBridge_nativeStop(JNIEnv*, jobject) {
    gPlayer.stop();
}

JNIEXPORT void JNICALL
Java_com_vessel_GranularBridge_nativeRelease(JNIEnv*, jobject) {
    gPlayer.close();
}

JNIEXPORT void JNICALL
Java_com_vessel_GranularBridge_nativeSetGrainDensity(JNIEnv*, jobject, jfloat v) {
    gPlayer.engine().setGrainDensity(v);
}

JNIEXPORT void JNICALL
Java_com_vessel_GranularBridge_nativeSetGrainDuration(JNIEnv*, jobject, jfloat ms) {
    gPlayer.engine().setGrainDuration(ms);
}

JNIEXPORT void JNICALL
Java_com_vessel_GranularBridge_nativeSetPositionScatter(JNIEnv*, jobject, jfloat v) {
    gPlayer.engine().setPositionScatter(v);
}

JNIEXPORT void JNICALL
Java_com_vessel_GranularBridge_nativeSetPitchRandom(JNIEnv*, jobject, jfloat semitones) {
    gPlayer.engine().setPitchRandomRange(semitones);
}

JNIEXPORT void JNICALL
Java_com_vessel_GranularBridge_nativeSetEnvelopeShape(JNIEnv*, jobject, jint shape) {
    gPlayer.engine().setEnvelopeShape(static_cast<EnvelopeShape>(shape));
}

JNIEXPORT void JNICALL
Java_com_vessel_GranularBridge_nativeSetMasterPosition(JNIEnv*, jobject, jfloat fraction) {
    gPlayer.engine().setMasterPosition(fraction);
}

JNIEXPORT void JNICALL
Java_com_vessel_GranularBridge_nativeSetContactMagnitude(JNIEnv*, jobject, jfloat mag) {
    gPlayer.engine().setContactMagnitude(mag);
}

JNIEXPORT void JNICALL
Java_com_vessel_GranularBridge_nativeSetIdleMode(JNIEnv*, jobject, jboolean idle) {
    gPlayer.engine().setIdleMode(idle == JNI_TRUE);
}

} // extern "C"
