package com.muffuq.os.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Debug-only: encodes a few seconds of a test tone to AAC-ELD with the platform encoder, so tools/dev can send real
 * ELD frames to the AirPlay receiver (desktop ffmpeg builds can't encode ELD). Writes [u16 length][frame]… to
 * files/eld.bin and logs the encoder's AudioSpecificConfig.
 */
class EldTestEncoder : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        thread {
            try {
                encode(File(context.getExternalFilesDir(null), "eld.bin"))
            } catch (e: Exception) {
                Log.e(TAG, "ELD encode failed", e)
            } finally {
                result.finish()
            }
        }
    }

    private fun encode(target: File) {
        val rate = 44100
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        Log.i(TAG, "encoder ${codec.name}")

        val totalFrames = rate * 6
        var produced = 0
        var frames = 0
        val info = MediaCodec.BufferInfo()
        DataOutputStream(target.outputStream().buffered()).use { out ->
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val count = minOf(buffer.capacity() / 4, totalFrames - produced)
                        for (i in 0 until count) {
                            val t = (produced + i).toDouble() / rate
                            // 440 Hz with a slow 1 Hz tremolo, easy to recognise by ear.
                            val s = (sin(2 * PI * 440 * t) * (0.25 + 0.15 * sin(2 * PI * t)) * Short.MAX_VALUE).toInt().toShort()
                            buffer.putShort(s)
                            buffer.putShort(s)
                        }
                        produced += count
                        inputDone = produced >= totalFrames
                        codec.queueInputBuffer(index, 0, count * 4, produced * 1_000_000L / rate,
                            if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val out_ = codec.dequeueOutputBuffer(info, 10_000)
                if (out_ == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val csd = codec.outputFormat.getByteBuffer("csd-0")
                    val bytes = ByteArray(csd!!.remaining()).also { csd.get(it) }
                    Log.i(TAG, "csd-0 ${bytes.joinToString("") { "%02x".format(it) }}")
                } else if (out_ >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val frame = ByteArray(info.size)
                        codec.getOutputBuffer(out_)!!.apply { position(info.offset); get(frame) }
                        out.writeShort(frame.size)
                        out.write(frame)
                        frames++
                    }
                    codec.releaseOutputBuffer(out_, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        codec.stop()
        codec.release()
        Log.i(TAG, "wrote $frames ELD frames to $target")
    }

    private companion object {
        const val TAG = "MuffuqAirPlay"
    }
}
