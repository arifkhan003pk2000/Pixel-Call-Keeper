package com.pixelcallkeeper

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

// WAV (16 kHz, mono, 16-bit) ko chhoti m4a (AAC) file mein badalta hai.
object AacEncoder {

    private fun fill(inp: InputStream, buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val k = inp.read(buf, off, len - off)
            if (k < 0) break
            off += k
        }
        return off - (off % 2)
    }

    fun encode(wav: File, out: File, rate: Int = 16000, bitrate: Int = 48000): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxStarted = false
        var input: FileInputStream? = null
        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = c
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val inp = FileInputStream(wav)
            input = inp
            inp.skip(44)
            val buf = ByteArray(4096)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var inputDone = false
            var outputDone = false
            var samples = 0L
            var idle = 0
            while (!outputDone) {
                if (!inputDone) {
                    val idx = c.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        val ib = c.getInputBuffer(idx)!!
                        ib.clear()
                        val want = minOf(buf.size, ib.remaining())
                        val n = fill(inp, buf, want)
                        val pts = samples * 1_000_000L / rate
                        if (n <= 0) {
                            c.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            ib.put(buf, 0, n)
                            c.queueInputBuffer(idx, 0, n, pts, 0)
                            samples += n / 2
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = m.addTrack(c.outputFormat)
                    m.start()
                    muxStarted = true
                } else if (oi >= 0) {
                    idle = 0
                    val ob = c.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxStarted) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        m.writeSampleData(track, ob, info)
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else {
                    idle++
                    if (inputDone && idle > 600) break
                }
            }
            return muxStarted && outputDone
        } catch (e: Exception) {
            return false
        } finally {
            try { input?.close() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { if (muxStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }
}
