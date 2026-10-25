package com.pixelcallkeeper

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

object Helper {
    @JvmStatic
    fun main(args: Array<String>) {
        val seconds = args.firstOrNull()?.toIntOrNull() ?: 6
        try { Looper.prepareMainLooper() } catch (_: Throwable) {}
        val ctx = fakeContext()
        println("Helper v2 shuru. Har source ke liye $seconds second. Call chalti rakhein aur baat karte rahein.")
        val sources = listOf(
            1 to "MIC", 4 to "VOICE_CALL", 3 to "VOICE_DOWNLINK", 2 to "VOICE_UPLINK",
            7 to "VOICE_COMMUNICATION", 6 to "VOICE_RECOGNITION", 8 to "REMOTE_SUBMIX"
        )
        for ((src, name) in sources) test(ctx, src, name, seconds)
        println("Khatam.")
        System.exit(0)
    }

    private fun setField(obj: Any, cls: Class<*>, name: String, value: Any?): Boolean {
        return try {
            val f = cls.getDeclaredField(name)
            f.isAccessible = true
            f.set(obj, value)
            true
        } catch (e: Throwable) {
            println("field $name set nahi hua: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun fakeContext(): Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getMethod("systemMain").invoke(null)!!
            val sys = atClass.getMethod("getSystemContext").invoke(thread) as Context
            val implClass = Class.forName("android.app.ContextImpl")
            val shell = "com.android.shell"
            setField(sys, implClass, "mOpPackageName", shell)
            setField(sys, implClass, "mBasePackageName", shell)
            try {
                val bClass = Class.forName("android.content.AttributionSource\$Builder")
                val b = bClass.getConstructor(Int::class.javaPrimitiveType).newInstance(2000)
                bClass.getMethod("setPackageName", String::class.java).invoke(b, shell)
                val src = bClass.getMethod("build").invoke(b)
                setField(sys, implClass, "mAttributionSource", src)
            } catch (e: Throwable) {
                println("AttributionSource: $e")
            }
            val app = Application()
            setField(app, ContextWrapper::class.java, "mBase", sys)
            setField(thread, atClass, "mInitialApplication", app)
            sys
        } catch (e: Throwable) {
            println("Context nahi bana: $e")
            null
        }
    }

    private fun make(ctx: Context?, src: Int, rate: Int, buf: Int): AudioRecord {
        val fmt = AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val b = AudioRecord.Builder()
            .setAudioSource(src)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(buf)
        if (ctx != null) {
            try {
                AudioRecord.Builder::class.java.getMethod("setContext", Context::class.java).invoke(b, ctx)
            } catch (_: Throwable) {}
        }
        return b.build()
    }

    private fun test(ctx: Context?, src: Int, name: String, seconds: Int) {
        val rate = 16000
        var rec: AudioRecord? = null
        try {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = make(ctx, src, rate, maxOf(min, rate))
            rec = r
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                println("$name: START NAHI HUA (init fail)")
                return
            }
            r.startRecording()
            val out = ByteArrayOutputStream()
            val data = ByteArray(4096)
            var peak = 0
            var err = 0
            val end = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < end) {
                val n = r.read(data, 0, data.size)
                if (n <= 0) { if (n < 0) err = n; Thread.sleep(20); continue }
                out.write(data, 0, n)
                var i = 0
                while (i + 1 < n) {
                    val s = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
                    if (abs(s) > peak) peak = abs(s)
                    i += 2
                }
            }
            r.stop()
            val pcm = out.toByteArray()
            val f = File("/sdcard/Download/pck_$name.wav")
            writeWav(f, pcm, rate)
            println("$name: peak=$peak bytes=${pcm.size} readError=$err -> ${f.name}")
        } catch (e: Throwable) {
            println("$name: ERROR $e ${e.cause ?: ""}")
        } finally {
            try { rec?.release() } catch (_: Throwable) {}
        }
    }

    private fun writeWav(f: File, pcm: ByteArray, rate: Int) {
        FileOutputStream(f).use { o ->
            fun i32(v: Int) { o.write(v and 255); o.write((v shr 8) and 255); o.write((v shr 16) and 255); o.write((v shr 24) and 255) }
            fun i16(v: Int) { o.write(v and 255); o.write((v shr 8) and 255) }
            o.write("RIFF".toByteArray()); i32(36 + pcm.size); o.write("WAVE".toByteArray())
            o.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
            o.write("data".toByteArray()); i32(pcm.size); o.write(pcm)
        }
    }
}
