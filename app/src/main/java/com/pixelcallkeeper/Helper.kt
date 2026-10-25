package com.pixelcallkeeper

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Looper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.Socket

object Helper {
    private const val PORT = 38355
    @Volatile private var recording = false
    private var recThread: Thread? = null

    @JvmStatic
    fun main(args: Array<String>) {
        try { Looper.prepareMainLooper() } catch (_: Throwable) {}
        val ctx = fakeContext()
        println("Helper v3 chalu. App se connection ka intezar...")
        while (true) {
            try {
                Socket("127.0.0.1", PORT).use { s -> serve(ctx, s) }
            } catch (_: Throwable) {
            }
            recording = false
            try { Thread.sleep(2000) } catch (_: Throwable) {}
        }
    }

    private fun serve(ctx: Context?, s: Socket) {
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        val inp = BufferedReader(InputStreamReader(BufferedInputStream(s.getInputStream())))
        println("App se connect ho gaya")
        while (true) {
            val line = inp.readLine() ?: break
            if (line.startsWith("S") && !recording) {
                val src = line.substring(1).toIntOrNull() ?: 4
                recording = true
                val t = Thread { capture(ctx, src, out) }
                recThread = t
                t.start()
            } else if (line.startsWith("X")) {
                recording = false
                try { recThread?.join(3000) } catch (_: Throwable) {}
            }
        }
        recording = false
    }

    private fun capture(ctx: Context?, src: Int, out: DataOutputStream) {
        var rec: AudioRecord? = null
        try {
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = make(ctx, src, rate, maxOf(min, rate))
            rec = r
            if (r.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("init fail")
            r.startRecording()
            val data = ByteArray(3200)
            while (recording) {
                val n = r.read(data, 0, data.size)
                if (n > 0) {
                    synchronized(out) { out.writeShort(n); out.write(data, 0, n); out.flush() }
                } else if (n < 0) break
            }
            r.stop()
        } catch (e: Throwable) {
            println("capture error: $e")
        } finally {
            try { rec?.release() } catch (_: Throwable) {}
            recording = false
            try { synchronized(out) { out.writeShort(0); out.flush() } } catch (_: Throwable) {}
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
}
