package com.vessel

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "RecordingManager"

class RecordingManager(private val context: Context) {

    var isRecording = false
        private set

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var outputPath: String? = null

    fun onProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)
        startRecording()
    }

    private fun startRecording() {
        val projection = mediaProjection ?: return

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context as Activity).windowManager.defaultDisplay.getRealMetrics(metrics)
        val width   = metrics.widthPixels
        val height  = metrics.heightPixels
        val density = metrics.densityDpi

        val stamp    = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "VESSEL_$stamp.mp4"
        val cacheFile = File(context.cacheDir, fileName)
        outputPath = cacheFile.absolutePath

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(6_000_000)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128_000)
            setOutputFile(outputPath)
            prepare()
        }
        mediaRecorder = recorder

        virtualDisplay = projection.createVirtualDisplay(
            "VesselCapture",
            width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.surface, null, null
        )

        recorder.start()
        isRecording = true
        Log.i(TAG, "Recording started: $outputPath")
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        try { mediaRecorder?.stop() } catch (e: Exception) { Log.e(TAG, "stop() error", e) }
        mediaRecorder?.release()
        mediaRecorder = null

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.stop()
        mediaProjection = null

        outputPath?.let { saveToMediaStore(it) }
        outputPath = null
    }

    private fun saveToMediaStore(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) return

        val stamp  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VESSEL_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VESSEL")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { Log.e(TAG, "MediaStore insert returned null"); return }

        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "Saved to Gallery: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to MediaStore", e)
            resolver.delete(uri, null, null)
        } finally {
            file.delete()
        }
    }

    fun release() {
        if (isRecording) stopRecording()
        mediaProjection?.stop()
        mediaProjection = null
    }
}
