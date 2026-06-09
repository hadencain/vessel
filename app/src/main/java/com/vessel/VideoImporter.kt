package com.vessel

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

private const val TAG = "VideoImporter"
private const val MAX_FRAMES = 5 * 60 * 48000  // 5 minutes at 48kHz

class VideoImporter(private val context: Context) {

    data class DecodeResult(
        val pcmFloat: FloatArray,
        val numFrames: Int,
        val numChannels: Int,
        val sampleRate: Int
    )

    class NoAudioTrackException : Exception("No audio track found in video")

    suspend fun importVideo(uri: Uri): DecodeResult = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var audioTrackIdx = -1
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrackIdx = i
                audioFormat = fmt
                break
            }
        }

        if (audioFormat == null) {
            extractor.release()
            throw NoAudioTrackException()
        }

        extractor.selectTrack(audioTrackIdx)

        val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
        val srcSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val numChannels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val decoder = MediaCodec.createDecoderByType(mime)
        // Request float32 PCM output
        audioFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        decoder.configure(audioFormat, null, null, 0)
        decoder.start()

        val chunks = mutableListOf<FloatArray>()
        var totalFrames = 0L
        val timeoutUs = 5000L
        var sawEOS = false

        try {
            while (!sawEOS) {
                val inputIdx = decoder.dequeueInputBuffer(timeoutUs)
                if (inputIdx >= 0) {
                    val inputBuf = decoder.getInputBuffer(inputIdx)!!
                    val sampleSize = extractor.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawEOS = true
                    } else {
                        decoder.queueInputBuffer(inputIdx, 0, sampleSize,
                            extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                val info = MediaCodec.BufferInfo()
                var outputIdx = decoder.dequeueOutputBuffer(info, timeoutUs)
                while (outputIdx >= 0) {
                    if (info.size > 0) {
                        val outBuf = decoder.getOutputBuffer(outputIdx)!!
                        val floatBuf = outBuf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                        val samples = FloatArray(floatBuf.remaining())
                        floatBuf.get(samples)
                        chunks.add(samples)
                        totalFrames += samples.size / numChannels
                    }
                    decoder.releaseOutputBuffer(outputIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    outputIdx = decoder.dequeueOutputBuffer(info, 0)
                }
            }
        } finally {
            decoder.stop()
            decoder.release()
        }

        extractor.release()

        // Concatenate all chunks
        var combined = FloatArray((totalFrames * numChannels).toInt())
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(combined, offset)
            offset += chunk.size
        }

        // Resample to 48kHz if needed (linear interpolation)
        if (srcSampleRate != 48000) {
            Log.i(TAG, "Resampling $srcSampleRate → 48000")
            combined = resampleLinear(combined, numChannels, srcSampleRate, 48000)
        }

        var numOutputFrames = combined.size / numChannels

        // Cap at 5 minutes to prevent OOM
        if (numOutputFrames > MAX_FRAMES) {
            Log.w(TAG, "Audio truncated to 5 minutes (was ${numOutputFrames / 48000}s)")
            numOutputFrames = MAX_FRAMES
            combined = combined.copyOf(numOutputFrames * numChannels)
        }

        Log.i(TAG, "Decoded ${numOutputFrames / 48000}s of audio, $numChannels ch, 48kHz")
        DecodeResult(combined, numOutputFrames, numChannels, 48000)
    }

    private fun resampleLinear(src: FloatArray, channels: Int, srcRate: Int, dstRate: Int): FloatArray {
        val srcFrames = src.size / channels
        val dstFrames = (srcFrames.toLong() * dstRate / srcRate).toInt()
        val dst = FloatArray(dstFrames * channels)
        val ratio = srcFrames.toDouble() / dstFrames.toDouble()

        for (di in 0 until dstFrames) {
            val srcPos = di * ratio
            val lo = srcPos.toInt().coerceIn(0, srcFrames - 1)
            val hi = (lo + 1).coerceIn(0, srcFrames - 1)
            val frac = (srcPos - lo).toFloat()
            for (ch in 0 until channels) {
                dst[di * channels + ch] =
                    src[lo * channels + ch] * (1f - frac) + src[hi * channels + ch] * frac
            }
        }
        return dst
    }
}
