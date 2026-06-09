package com.vessel

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.atomic.AtomicBoolean

class MediaPipeHandTracker(private val context: Context) {

    private val handlerThread = HandlerThread("mp-hands").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    // True while the HandlerThread is processing a frame — used to drop frames
    // when inference can't keep up with the camera feed rate.
    private val busy = AtomicBoolean(false)

    var onLandmarksResult: ((HandLandmarkerResult) -> Unit)? = null

    private val landmarker: HandLandmarker? by lazy {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .setDelegate(Delegate.CPU)
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, _ -> onLandmarksResult?.invoke(result) }
                .build()
            HandLandmarker.createFromOptions(context, options).also { landmarkerInitialized = true }
        } catch (e: Exception) {
            android.util.Log.e("MediaPipeHandTracker", "Failed to init HandLandmarker", e)
            null
        }
    }

    // Called from GL thread: copies YUV planes and dispatches to HandlerThread.
    // Drops the frame if the previous one is still being processed.
    fun submitFrame(arImage: Image, timestampUs: Long) {
        if (!busy.compareAndSet(false, true)) return  // drop frame — HandlerThread still busy

        val yPlane  = arImage.planes[0]
        val uvPlane = arImage.planes[1]
        val yBytes      = yPlane.buffer.copyToByteArray()
        val uvBytes     = uvPlane.buffer.copyToByteArray()
        val yRowStride  = yPlane.rowStride
        val uvRowStride = uvPlane.rowStride
        val uvPixStride = uvPlane.pixelStride
        val w = arImage.width
        val h = arImage.height

        handler.post {
            try {
                val lm = landmarker ?: return@post
                if (timestampUs <= lastHandlerTimestampUs) return@post
                lastHandlerTimestampUs = timestampUs
                val bmp = yuvToBitmap(yBytes, uvBytes, w, h, yRowStride, uvRowStride, uvPixStride)
                val mpImage = BitmapImageBuilder(bmp).build()
                try {
                    lm.detectAsync(mpImage, timestampUs)
                } catch (e: Exception) {
                    android.util.Log.w("MediaPipeHandTracker", "detectAsync skipped: ${e.message}")
                }
            } finally {
                busy.set(false)
            }
        }
    }

    // Only accessed from HandlerThread — no sync needed
    private var lastHandlerTimestampUs = 0L

    @Volatile private var landmarkerInitialized = false

    fun close() {
        handlerThread.quitSafely()
        if (landmarkerInitialized) landmarker?.close()
    }

    // Software YUV_420_888 → ARGB_8888 using actual plane strides from the Image.
    // The UV buffer on Android is sized (h/2-1)*uvRowStride + (w/2-1)*uvPixStride + 1,
    // so the V byte for the last UV pair is clamped rather than read past the buffer end.
    private fun yuvToBitmap(
        y: ByteArray, uv: ByteArray,
        w: Int, h: Int,
        yRowStride: Int, uvRowStride: Int, uvPixStride: Int
    ): Bitmap {
        val argb      = IntArray(w * h)
        val uvLastIdx = uv.size - 1
        for (row in 0 until h) {
            for (col in 0 until w) {
                val yVal = y[row * yRowStride + col].toInt() and 0xFF
                val uIdx = (row / 2) * uvRowStride + (col / 2) * uvPixStride
                val vIdx = (uIdx + 1).coerceAtMost(uvLastIdx)
                val u    = (uv[uIdx].toInt() and 0xFF) - 128
                val v    = (uv[vIdx].toInt() and 0xFF) - 128
                val r    = (yVal + 1.402f  * v).toInt().coerceIn(0, 255)
                val g    = (yVal - 0.344f  * u - 0.714f * v).toInt().coerceIn(0, 255)
                val b    = (yVal + 1.772f  * u).toInt().coerceIn(0, 255)
                argb[row * w + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun java.nio.ByteBuffer.copyToByteArray(): ByteArray {
        val bytes = ByteArray(remaining())
        get(bytes)
        rewind()
        return bytes
    }
}
