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
import android.media.AudioManager
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import java.io.BufferedInputStream
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

class RecorderService : Service() {

    companion object {
        const val PORT = 38355
        @Volatile var serviceUp = false
        @Volatile var helperConnected = false
        @Volatile var recording = false
        @Volatile var startedAt = 0L
        @Volatile var lastError: String? = null
        @Volatile var auto = true
        @Volatile private var instance: RecorderService? = null

        fun dir(ctx: Context): File =
            File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "PixelCallKeeper").apply { mkdirs() }

        fun cleanName(s: String): String =
            s.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().ifEmpty { "Unknown" }

        fun setAuto(ctx: Context, v: Boolean) {
            auto = v
            ctx.getSharedPreferences("pck", Context.MODE_PRIVATE).edit().putBoolean("auto", v).apply()
        }

        fun toggleManual() { instance?.manualToggle() }
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = "pck"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "PixelCall Keeper", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setContentTitle("PixelCall Keeper")
            .setContentText("Helper ka intezar / recording")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        if (!serviceUp) {
            serviceUp = true
            instance = this
            auto = getSharedPreferences("pck", Context.MODE_PRIVATE).getBoolean("auto", true)
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

    private fun startRec(source: Int) {
        if (!helperConnected || recording) return
        synchronized(fileLock) {
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
            } catch (e: Exception) {
                lastError = "File: ${e.message}"
                return
            }
        }
        recording = true
        startedAt = System.currentTimeMillis()
        send("S$source")
    }

    private fun stopRec() {
        if (!recording) return
        send("X")
    }

    fun manualToggle() {
        if (recording) { manual = false; stopRec() }
        else { manual = true; startRec(4) }
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
                if (pcmBytes < 3200) {
                    f.delete()
                    if (lastError == null) lastError = "Recording khali rahi (helper audio nahi de paya)"
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
        while (serviceUp) {
            try { Thread.sleep(1000) } catch (_: Exception) {}
            if (!auto || manual) continue
            val m = am.mode
            val inCall = m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
            if (inCall) {
                quiet = 0
                if (!recording && helperConnected) startRec(if (m == AudioManager.MODE_IN_CALL) 4 else 7)
            } else if (recording) {
                quiet++
                if (quiet >= 3) { quiet = 0; stopRec() }
            } else quiet = 0
        }
    }

    override fun onDestroy() {
        serviceUp = false
        try { server?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        finalizeFile()
        helperConnected = false
        instance = null
        super.onDestroy()
    }
}
