package com.pixelcallkeeper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0B0F1A")
    private val accent = Color.parseColor("#22C5F0")
    private lateinit var content: FrameLayout
    private var player: MediaPlayer? = null
    private var playingName: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var statusView: TextView? = null
    private var noteView: TextView? = null
    private var toggleBtn: Button? = null

    private val ticker = object : Runnable {
        override fun run() { ensureService(); refreshHome(); handler.postDelayed(this, 1000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float = 16f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.BLACK)
        setBackgroundColor(accent)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setOnApplyWindowInsetsListener { v, ins ->
                @Suppress("DEPRECATION")
                v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins
            }
        }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        listOf("Home" to ::showHome, "Recordings" to ::showRecordings, "Setup" to ::showSetup).forEach { (name, f) ->
            nav.addView(Button(this).apply {
                text = name; isAllCaps = false
                setOnClickListener { f() }
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        root.addView(nav)
        setContentView(root)
        if (!hasPerms()) askPerms()
        showHome()
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { stopPlayback(); super.onDestroy() }

    private fun ensureService() {
        if (hasPerms() && !RecorderService.serviceUp) {
            try { startForegroundService(Intent(this, RecorderService::class.java)) } catch (_: Exception) {}
        }
    }

    private fun screen(vararg views: View): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(20)) }
        views.forEach { col.addView(it) }
        val sv = ScrollView(this).apply { addView(col) }
        content.removeAllViews(); content.addView(sv)
        return sv
    }

    // ---------- Home ----------
    private fun showHome() {
        stopPlayback()
        statusView = tv("", 18f, accent, true)
        noteView = tv("", 14f, Color.parseColor("#FFB74D"))
        toggleBtn = btn("Recording shuru karein") { RecorderService.toggleManual() }
        val sw = Switch(this).apply {
            text = "Auto-record (call par khud shuru)"
            setTextColor(Color.WHITE)
            isChecked = RecorderService.auto
            setOnCheckedChangeListener { _, c -> RecorderService.setAuto(this@MainActivity, c) }
            setPadding(dp(4), dp(14), dp(4), dp(14))
        }
        screen(
            tv("PixelCall Keeper", 30f, Color.WHITE, true),
            tv("Private recordings, aapke phone par", 15f, Color.LTGRAY),
            statusView!!, noteView!!, sw, toggleBtn!!,
            tv("Helper pehle Setup ke mutabiq USB se chalana hota hai. " +
               "Uske baad call shuru hote hi recording khud shuru ho jayegi.", 14f, Color.LTGRAY)
        )
        refreshHome()
    }

    private fun refreshHome() {
        val s = statusView ?: return
        val b = toggleBtn ?: return
        if (RecorderService.recording) {
            val sec = (System.currentTimeMillis() - RecorderService.startedAt) / 1000
            s.text = "Recording chal rahi hai: %02d:%02d".format(sec / 60, sec % 60)
            b.text = "Recording band karein"
            b.isEnabled = true
        } else {
            s.text = when {
                !RecorderService.serviceUp -> "Service band hai (microphone permission dein)"
                RecorderService.helperConnected -> "Taiyar: helper connected"
                else -> "Helper connected nahi. Setup mein command dekhein."
            }
            b.text = "Recording shuru karein"
            b.isEnabled = RecorderService.helperConnected
        }
        noteView?.text = RecorderService.lastError?.let { "Note: $it" } ?: ""
    }

    private fun hasPerms() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPerms() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(p.toTypedArray(), 1)
    }

    // ---------- Recordings ----------
    private fun showRecordings() {
        val files = RecorderService.dir(this).listFiles { f -> f.extension == "wav" || f.extension == "m4a" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val views = mutableListOf<View>(tv("Recordings", 28f, Color.WHITE, true))
        if (files.isEmpty()) views += tv("Abhi koi recording nahi hai.", 15f, Color.LTGRAY)
        files.forEach { f ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val kb = f.length() / 1024
            row.addView(Button(this).apply {
                text = (if (playingName == f.name) "■ " else "▶ ") + f.nameWithoutExtension + "  (${kb} KB)"
                isAllCaps = false; textSize = 13f
                setOnClickListener { togglePlay(f) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
            row.addView(Button(this).apply {
                text = "Delete"; isAllCaps = false
                setOnClickListener { confirmDelete(f) }
            }, LinearLayout.LayoutParams(-2, dp(52)))
            views += row
        }
        screen(*views.toTypedArray())
    }

    private fun togglePlay(f: File) {
        val same = playingName == f.name
        stopPlayback()
        if (same) { showRecordings(); return }
        try {
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath); prepare(); start()
                setOnCompletionListener { stopPlayback(); showRecordings() }
            }
            playingName = f.name
        } catch (e: Exception) {
            Toast.makeText(this, "Play nahi ho saki: ${e.message}", Toast.LENGTH_LONG).show()
        }
        showRecordings()
    }

    private fun stopPlayback() {
        try { player?.stop() } catch (_: Exception) {}
        player?.release(); player = null; playingName = null
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this).setTitle("Delete karein?").setMessage(f.name)
            .setPositiveButton("Delete") { _, _ -> stopPlayback(); f.delete(); showRecordings() }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- Setup ----------
    private fun showSetup() {
        stopPlayback()
        val cmd = tv("adb shell \"CLASSPATH=\$(pm path com.pixelcallkeeper | cut -d: -f2) " +
            "nohup setsid app_process / com.pixelcallkeeper.Helper >/data/local/tmp/pck.log 2>&1 &\"",
            13f, accent).apply { setTextIsSelectable(true) }
        screen(
            tv("Setup", 28f, Color.WHITE, true),
            tv("Microphone: " + if (hasPerms()) "Allowed" else "Allow nahi hai", 16f, accent, true),
            btn("Microphone / notification permission do") { askPerms() },
            btn("App settings kholen") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            tv("Helper chalane ka tareeqa (har restart ke baad ek baar):", 15f, Color.WHITE, true),
            tv("1) Phone USB se computer se jodein, USB debugging ON rakhein.\n" +
               "2) App kholein (Home par 'Helper connected nahi' dikhega).\n" +
               "3) Computer par platform-tools folder mein cmd kholein aur ye command chalayein:", 14f, Color.LTGRAY),
            cmd,
            tv("4) Kuch second mein Home par 'Taiyar: helper connected' aa jayega. USB hata sakte hain.\n" +
               "5) Battery > Unrestricted zaroor karein.\n" +
               "6) Samne wale ki ijazat ke bina call record na karein.", 14f, Color.LTGRAY),
            btn("Service band karein") { RecorderService.stopAll() }
        )
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        ensureService()
        showHome()
    }
}
