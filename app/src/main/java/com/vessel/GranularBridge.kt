package com.vessel

object GranularBridge {
    init { System.loadLibrary("vessel_native") }

    external fun nativeInit(): Boolean
    external fun nativeLoadPcm(data: FloatArray, numFrames: Int, numChannels: Int)
    external fun nativeStart()
    external fun nativeStop()
    external fun nativeRelease()

    external fun nativeSetGrainDensity(grainsPerSec: Float)
    external fun nativeSetGrainDuration(ms: Float)
    external fun nativeSetPositionScatter(fraction: Float)
    external fun nativeSetPitchRandom(semitones: Float)
    external fun nativeSetEnvelopeShape(shape: Int)  // 0=Hann, 1=Gaussian, 2=Trapezoid
    external fun nativeSetMasterPosition(fraction: Float)
    external fun nativeSetContactMagnitude(magnitude: Float)
    external fun nativeSetIdleMode(idle: Boolean)
}
