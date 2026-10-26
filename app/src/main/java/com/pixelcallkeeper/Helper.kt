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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.abs

object Helper {
    private const val PORT = 38355
    @Volatile private var recording = false
    private var recThread: Thread? = null
    private var lock: ServerSocket? = null

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            lock = ServerSocket(38356, 1, InetAddress.getByName("127.0.0.1"))
        } catch (_: Throwable) {
            println("Dusra helper pehle se chal raha hai. Ye band ho raha hai.")
            System.exit(0)
        }
        try { Looper.prepareMainLooper() } catch (_: Throwable) {}
        val ctx = fakeContext()
        println("Helper v6 chalu. App se connection ka intezar...")
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

    private fun readFull(r: AudioRecord, buf: ByteArray, n: Int): Int {
        var off = 0
        while (off < n && recording) {
            val k = r.read(buf, off, n - off)
            if (k < 0) return -1
            if (k == 0) { Thread.sleep(5); continue }
            off += k
        }
        return off
    }

    private fun tryStart(ctx: Context?, src: Int, rate: Int, buf: Int, name: String): AudioRecord? {
        return try {
            val r = make(ctx, src, rate, buf)
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                println("$name: init fail"); r.release(); null
            } else {
                r.startRecording(); println("$name: chalu"); r
            }
        } catch (e: Throwable) {
            println("$name: error $e"); null
        }
    }

    private fun sampleAt(b: ByteArray, i: Int): Int =
        ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()

    private fun captureVoip(ctx: Context?, out: DataOutputStream, useMic: Boolean) {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(min, rate * 2)
        var sub: AudioRecord? = null
        var mic: AudioRecord? = null
        try {
            sub = tryStart(ctx, 8, rate, bufSize, "REMOTE_SUBMIX")
            mic = if (useMic) tryStart(ctx, 1, rate, bufSize, "MIC") else null
            if (sub == null && mic == null) throw IllegalStateException("koi source start nahi hua")
            val n = 1600
            val a = ByteArray(n)
            val b = ByteArray(n)
            val mix = ByteArray(n)
            var peakS = 0
            var peakM = 0
            var last = System.currentTimeMillis()
            while (recording) {
                java.util.Arrays.fill(a, 0)
                java.util.Arrays.fill(b, 0)
                val s0 = sub
                if (s0 != null) { val k = readFull(s0, a, n); if (k < 0) { try { s0.release() } catch (_: Throwable) {}; sub = null } }
                val m0 = mic
                if (m0 != null) { val k = readFull(m0, b, n); if (k < 0) { try { m0.release() } catch (_: Throwable) {}; mic = null } }
                if (!recording) break
                var i = 0
                while (i + 1 < n) {
                    val x = sampleAt(a, i)
                    val y = sampleAt(b, i)
                    if (abs(x) > peakS) peakS = abs(x)
                    if (abs(y) > peakM) peakM = abs(y)
                    val m = (x + y).coerceIn(-32768, 32767)
                    mix[i] = (m and 0xFF).toByte()
                    mix[i + 1] = ((m shr 8) and 0xFF).toByte()
                    i += 2
                }
                synchronized(out) { out.writeShort(n); out.write(mix, 0, n); out.flush() }
                val now = System.currentTimeMillis()
                if (now - last >= 2000) {
                    println("VoIP peak: submix=$peakS mic=$peakM")
                    peakS = 0; peakM = 0; last = now
                }
                if (sub == null && mic == null) break
            }
        } catch (e: Throwable) {
            println("voip capture error: $e")
        } finally {
            try { sub?.stop() } catch (_: Throwable) {}
            try { mic?.stop() } catch (_: Throwable) {}
            try { sub?.release() } catch (_: Throwable) {}
            try { mic?.release() } catch (_: Throwable) {}
            recording = false
            try { synchronized(out) { out.writeShort(0); out.flush() } } catch (_: Throwable) {}
        }
    }

    private fun capture(ctx: Context?, src: Int, out: DataOutputStream) {
        if (src == 7) { captureVoip(ctx, out, true); return }
        if (src == 9) { captureVoip(ctx, out, false); return }
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
