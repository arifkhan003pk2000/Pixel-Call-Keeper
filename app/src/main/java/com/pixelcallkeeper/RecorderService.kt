package com.pixelcallkeeper

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class RecorderService : Service() {

    companion object {
        const val PORT = 38355
        @Volatile var serviceUp = false
        @Volatile var helperConnected = false
        @Volatile var recording = false
        @Volatile var startedAt = 0L
        @Volatile var lastError: String? = null
        @Volatile var info: String? = null
        @Volatile var auto = true
        @Volatile var boostLevel = 3
        @Volatile private var instance: RecorderService? = null

        fun dir(ctx: Context): File =
            File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "PixelCallKeeper").apply { mkdirs() }

        fun cleanName(s: String): String =
            s.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().ifEmpty { "Unknown" }

        fun setAuto(ctx: Context, v: Boolean) {
            auto = v
            ctx.getSharedPreferences("pck", Context.MODE_PRIVATE).edit().putBoolean("auto", v).apply()
        }

        val boostNames = listOf("Band", "Halka", "Medium", "Zyada")

        fun cycleBoost(ctx: Context) {
            boostLevel = (boostLevel + 1) % 4
            ctx.getSharedPreferences("pck", Context.MODE_PRIVATE).edit().putInt("boostLevel", boostLevel).apply()
        }

        fun toggleManual(source: Int = 4) { instance?.manualToggle(source) }
        fun stopAll() { instance?.stopSelf() }
    }

    private val sender = Executors.newSingleThreadExecutor()
    private val fileLock = Any()
    private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private var fos: FileOutputStream? = null
    private var curFile: File? = null
    private var curSource = 4
    private var curStart = 0L
    private var pcmBytes = 0L
    @Volatile private var manual = false
    @Volatile private var localRun = false
    private var localThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = "pck2"
        val nm = getSystemService(NotificationManager::class.java)
        try { nm.deleteNotificationChannel("pck") } catch (_: Exception) {}
        val chan = NotificationChannel(ch, "PixelCall Keeper", NotificationManager.IMPORTANCE_MIN)
        chan.lockscreenVisibility = Notification.VISIBILITY_SECRET
        chan.setShowBadge(false)
        nm.createNotificationChannel(chan)
        val n = Notification.Builder(this, ch)
            .setContentTitle("PixelCall Keeper")
            .setContentText("Chal raha hai")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        if (!serviceUp) {
            serviceUp = true
            instance = this
            auto = getSharedPreferences("pck", Context.MODE_PRIVATE).getBoolean("auto", true)
            boostLevel = getSharedPreferences("pck", Context.MODE_PRIVATE).getInt("boostLevel", 3)
            Thread { serverLoop() }.start()
            Thread { monitor() }.start()
        }
        return START_STICKY
    }

    private fun serverLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("127.0.0.1", PORT))
            server = ss
            while (!ss.isClosed) {
                val c = ss.accept()
                try { client?.close() } catch (_: Exception) {}
                client = c
                helperConnected = true
                lastError = null
                Thread { readLoop(c) }.start()
            }
        } catch (e: Exception) {
            if (serviceUp) lastError = "Server: ${e.message}"
        }
    }

    private fun readLoop(c: Socket) {
        try {
            val inp = DataInputStream(BufferedInputStream(c.getInputStream()))
            while (true) {
                val len = inp.readUnsignedShort()
                if (len == 0) { finalizeFile(); continue }
                val buf = ByteArray(len)
                inp.readFully(buf)
                synchronized(fileLock) {
                    fos?.write(buf)
                    pcmBytes += len
                }
            }
        } catch (_: Exception) {
        } finally {
            finalizeFile()
            if (client === c) { helperConnected = false; client = null }
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun send(cmd: String) {
        val c = client ?: return
        sender.execute {
            try {
                c.getOutputStream().write((cmd + "\n").toByteArray())
                c.getOutputStream().flush()
            } catch (e: Exception) {
                lastError = "Send: ${e.message}"
            }
        }
    }

    private fun openNewFile(source: Int): Boolean {
        return synchronized(fileLock) {
            try {
                val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".wav"
                val tmp = File(dir(this), "_new").apply { mkdirs() }
                val f = File(tmp, name)
                val o = FileOutputStream(f)
                o.write(ByteArray(44))
                fos = o
                curFile = f
                curSource = source
                curStart = System.currentTimeMillis()
                pcmBytes = 0
                true
            } catch (e: Exception) {
                lastError = "File: ${e.message}"
                false
            }
        }
    }

    private fun startRec(source: Int) {
        if (!helperConnected || recording) return
        if (!openNewFile(source)) return
        recording = true
        startedAt = System.currentTimeMillis()
        send("S$source")
    }

    // Halki awaaz ko tez karta hai, shor ko dabata hai (kaan se lagane par samne wale ki awaaz ke liye)
    private class Agc(level: Int) {
        private var gain = 1.0f
        private var att = 1.0f
        private var hpIn = 0f
        private var hpOut = 0f
        private var lp1 = 0f
        private var lp2 = 0f
        private var nf = 50f
        private val target = when (level) { 1 -> 2200f; 2 -> 2600f; else -> 3200f }
        private val maxGain = when (level) { 1 -> 8f; 2 -> 14f; else -> 24f }

        fun process(b: ByteArray, off: Int, len: Int) {
            val total = len / 2
            val sub = 160
            var s = 0
            while (s < total) {
                val cnt = minOf(sub, total - s)
                val tmp = FloatArray(cnt)
                var sum = 0.0
                for (i in 0 until cnt) {
                    val idx = off + (s + i) * 2
                    val x = ((b[idx + 1].toInt() shl 8) or (b[idx].toInt() and 0xFF)).toShort().toFloat()
                    val h = x - hpIn + 0.97f * hpOut
                    hpIn = x
                    hpOut = h
                    lp1 += 0.66f * (h - lp1)
                    lp2 += 0.66f * (lp1 - lp2)
                    tmp[i] = lp2
                    sum += (lp2 * lp2).toDouble()
                }
                val rms = Math.sqrt(sum / cnt).toFloat()
                nf = if (rms < nf) nf * 0.9f + rms * 0.1f else nf * 1.0001f
                if (nf < 4f) nf = 4f
                if (nf > 400f) nf = 400f
                val speech = rms > maxOf(nf * 1.8f, 30f)
                var tg = gain
                if (speech) {
                    val want = (target / rms).coerceIn(1.0f, maxGain)
                    tg = if (want < gain) gain + (want - gain) * 0.6f else gain + (want - gain) * 0.05f
                }
                val attTarget = if (speech) 1.0f else 0.35f
                val a0 = att
                val a1 = a0 + (attTarget - a0) * (if (attTarget > a0) 0.5f else 0.05f)
                val g0 = gain
                for (i in 0 until cnt) {
                    val g = (g0 + (tg - g0) * (i + 1) / cnt) * (a0 + (a1 - a0) * (i + 1) / cnt)
                    var y = tmp[i] * g
                    val ay = abs(y)
                    if (ay > 24000f) {
                        y = (if (y < 0f) -1f else 1f) * minOf(32767f, 24000f + (ay - 24000f) * 0.3f)
                    }
                    val v = y.toInt().coerceIn(-32768, 32767)
                    val idx = off + (s + i) * 2
                    b[idx] = (v and 0xFF).toByte()
                    b[idx + 1] = ((v shr 8) and 0xFF).toByte()
                }
                gain = tg
                att = a1
                s += cnt
            }
        }
    }

    private class Probe(val name: String, val ar: AudioRecord) {
        val buf = ByteArrayOutputStream()
        @Volatile var peak = 0
        @Volatile var active = true
        @Volatile var direct = false
        var thread: Thread? = null
    }

    private val lockP = Any()

    // WhatsApp / Instagram: app ke andar se mic (Accessibility ON ho tabhi)
    private fun startRecLocal() {
        if (recording) return
        if (!PckAccessibilityService.connected) {
            lastError = "Pehle Setup mein Accessibility ON karein"
            return
        }
        if (!openNewFile(7)) return
        recording = true
        startedAt = System.currentTimeMillis()
        localRun = true
        info = null
        val t = Thread { runLocal() }
        localThread = t
        t.start()
    }

    private fun runLocal() {
        val probes = mutableListOf<Probe>()
        val agc = if (boostLevel > 0) Agc(boostLevel) else null
        try {
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val size = maxOf(min, rate * 2)
            val sources = listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION to "VR",
                MediaRecorder.AudioSource.UNPROCESSED to "RAW",
                MediaRecorder.AudioSource.CAMCORDER to "CAM",
                MediaRecorder.AudioSource.MIC to "MIC"
            )
            for ((src, name) in sources) {
                try {
                    val ar = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                    if (ar.state == AudioRecord.STATE_INITIALIZED) {
                        ar.startRecording()
                        probes += Probe(name, ar)
                    } else ar.release()
                } catch (_: Exception) {}
            }
            if (probes.isEmpty()) throw IllegalStateException("koi mic source start nahi hua")

            probes.forEach { p ->
                val th = Thread {
                    val data = ByteArray(3200)
                    while (p.active && localRun) {
                        val n = try { p.ar.read(data, 0, data.size) } catch (_: Exception) { -1 }
                        if (n < 0) break
                        if (n == 0) continue
                        var i = 0
                        while (i + 1 < n) {
                            val v = abs(((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt())
                            if (v > p.peak) p.peak = v
                            i += 2
                        }
                        synchronized(lockP) {
                            if (p.direct) {
                                agc?.process(data, 0, n)
                                synchronized(fileLock) { fos?.write(data, 0, n); pcmBytes += n }
                            } else p.buf.write(data, 0, n)
                        }
                    }
                }
                p.thread = th
                th.start()
            }

            val t0 = System.currentTimeMillis()
            while (localRun && System.currentTimeMillis() - t0 < 5000) Thread.sleep(100)

            val best = probes.maxByOrNull { it.peak }!!
            info = "WhatsApp mic: " + probes.joinToString(" ") { "${it.name}=${it.peak}" } + "  → ${best.name}"
            synchronized(lockP) {
                val raw = best.buf.toByteArray()
                agc?.process(raw, 0, raw.size)
                synchronized(fileLock) {
                    fos?.write(raw)
                    pcmBytes += raw.size
                }
                best.buf.reset()
                best.direct = true
            }
            probes.filter { it !== best }.forEach { it.active = false }
            while (localRun) Thread.sleep(100)
        } catch (e: Exception) {
            lastError = "VoIP: ${e.message}"
        } finally {
            localRun = false
            probes.forEach { it.active = false }
            probes.forEach { p ->
                try { p.thread?.join(700) } catch (_: Exception) {}
                try { p.ar.stop() } catch (_: Exception) {}
                try { p.ar.release() } catch (_: Exception) {}
            }
            finalizeFile()
        }
    }

    private fun stopRec() {
        if (!recording) return
        if (localRun) localRun = false else send("X")
    }

    fun manualToggle(source: Int) {
        if (recording) { manual = false; stopRec(); return }
        manual = true
        if (source == 7) startRecLocal() else startRec(source)
        if (!recording) manual = false
    }

    private fun finalizeFile() {
        var doneFile: File? = null
        var src = 4
        var start = 0L
        synchronized(fileLock) {
            val o = fos
            if (o == null) { recording = false; return }
            try { o.flush(); o.close() } catch (_: Exception) {}
            fos = null
            val f = curFile
            curFile = null
            if (f != null) {
                if (pcmBytes < 64000) {
                    f.delete()
                    if (pcmBytes < 3200 && lastError == null) lastError = "Recording khali rahi (helper audio nahi de paya)"
                } else {
                    writeHeader(f, pcmBytes)
                    doneFile = f
                    src = curSource
                    start = curStart
                }
            }
            recording = false
            manual = false
        }
        doneFile?.let { fileAway(it, src, start) }
    }

    private fun writeHeader(f: File, dataLen: Long) {
        try {
            RandomAccessFile(f, "rw").use { r ->
                val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()); h.putInt((36 + dataLen).toInt()); h.put("WAVE".toByteArray())
                h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
                h.putInt(16000); h.putInt(32000); h.putShort(2); h.putShort(16)
                h.put("data".toByteArray()); h.putInt(dataLen.toInt())
                r.seek(0); r.write(h.array())
            }
        } catch (e: Exception) {
            lastError = "Header: ${e.message}"
        }
    }

    // ----- folder / contact naming -----
    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun queryLog(since: Long): Pair<String, String>? {
        if (!has(Manifest.permission.READ_CALL_LOG)) return null
        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf((since - 20000).toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                if (c.moveToFirst()) return Pair(c.getString(0) ?: "", c.getString(1) ?: "")
            }
        } catch (_: Exception) {}
        return null
    }

    private fun contactName(number: String): String? {
        if (number.isBlank() || !has(Manifest.permission.READ_CONTACTS)) return null
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        } catch (_: Exception) {}
        return null
    }

    private fun fileAway(f: File, src: Int, start: Long) {
        Thread {
            try {
                var person = "Unknown"
                var type = "Phone Calls"
                if (src == 4) {
                    var number = ""
                    var name = ""
                    for (i in 0 until 3) {
                        Thread.sleep(if (i == 0) 2500L else 3000L)
                        val r = queryLog(start)
                        if (r != null) { number = r.first; name = r.second; break }
                    }
                    if (name.isBlank()) name = contactName(number) ?: ""
                    person = when {
                        name.isNotBlank() -> name
                        number.isNotBlank() -> number
                        else -> "Unknown"
                    }
                } else {
                    val fresh = CallNotificationListener.time >= start - 120000
                    val app = if (fresh) CallNotificationListener.app else ""
                    val nm = if (fresh) CallNotificationListener.name else ""
                    type = if (app.isNotBlank()) "$app Calls" else "Other Calls"
                    person = if (nm.isNotBlank()) nm else "Unknown"
                }
                val dest = File(File(dir(this), cleanName(person)), type).apply { mkdirs() }
                val target = File(dest, f.name)
                if (!f.renameTo(target)) { f.copyTo(target, true); f.delete() }
            } catch (e: Exception) {
                lastError = "Folder: ${e.message}"
            }
        }.start()
    }

    private fun monitor() {
        val am = getSystemService(AudioManager::class.java)
        var quiet = 0
        var onCall = 0
        while (serviceUp) {
            try { Thread.sleep(1000) } catch (_: Exception) {}
            val m = am.mode
            val phone = m == AudioManager.MODE_IN_CALL
            val voip = m == AudioManager.MODE_IN_COMMUNICATION && PckAccessibilityService.connected
            val anyCall = m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
            if (manual) {
                if (recording && curSource != 4 && !anyCall) {
                    quiet++
                    if (quiet >= 10) { quiet = 0; stopRec() }
                } else quiet = 0
                continue
            }
            if (!auto) continue
            if (phone || voip) {
                quiet = 0
                onCall++
                if (!recording && onCall >= 2) {
                    if (phone && helperConnected) startRec(4)
                    else if (voip) startRecLocal()
                }
            } else {
                onCall = 0
                if (recording) {
                    quiet++
                    if (quiet >= 5) { quiet = 0; stopRec() }
                } else quiet = 0
            }
        }
    }

    override fun onDestroy() {
        serviceUp = false
        localRun = false
        try { server?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        finalizeFile()
        helperConnected = false
        instance = null
        super.onDestroy()
    }
}
