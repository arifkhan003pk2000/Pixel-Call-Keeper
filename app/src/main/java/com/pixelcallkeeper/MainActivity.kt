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
    private var toggleBtn: Button? = null

    private val ticker = object : Runnable {
        override fun run() { refreshHome(); handler.postDelayed(this, 1000) }
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
        showHome()
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { stopPlayback(); super.onDestroy() }

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
        statusView = tv("", 18, accent, true)
        toggleBtn = btn("Recording shuru karein") { toggleRecording() }
        screen(
            tv("PixelCall Keeper", 30f, Color.WHITE, true),
            tv("Private recordings, aapke phone par", 15f, Color.LTGRAY),
            statusView!!,
            toggleBtn!!,
            tv("Call ke waqt: call shuru karein, SPEAKER ON karein, phir yahan Recording shuru dabayein. " +
               "Phone ka mic dono taraf ki awaaz pakadta hai.", 14f, Color.LTGRAY)
        )
        refreshHome()
    }

    private fun refreshHome() {
        val s = statusView ?: return
        val b = toggleBtn ?: return
        if (RecorderService.running) {
            val sec = (System.currentTimeMillis() - RecorderService.startedAt) / 1000
            s.text = "Recording chal rahi hai: %02d:%02d".format(sec / 60, sec % 60)
            b.text = "Recording band karein"
        } else {
            s.text = RecorderService.lastError?.let { "Error: $it" } ?: "Taiyar"
            b.text = "Recording shuru karein"
        }
    }

    private fun toggleRecording() {
        if (RecorderService.running) {
            stopService(Intent(this, RecorderService::class.java))
        } else if (hasPerms()) {
            startForegroundService(Intent(this, RecorderService::class.java))
        } else askPerms()
    }

    private fun hasPerms() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPerms() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(p.toTypedArray(), 1)
    }

    // ---------- Recordings ----------
    private fun showRecordings() {
        val files = RecorderService.dir(this).listFiles { f -> f.extension == "m4a" }
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
        screen(
            tv("Setup", 28f, Color.WHITE, true),
            tv("Microphone: " + if (hasPerms()) "Allowed" else "Allow nahi hai", 16f, accent, true),
            btn("Microphone / notification permission do") { askPerms() },
            btn("App settings kholen") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            tv("Zaroori:\n" +
               "1) App settings > Battery > Unrestricted karein.\n" +
               "2) Call ke dauran SPEAKER ON rakhein.\n" +
               "3) Agar recording khali (silent) aaye, to Android mic ko call ke dauran block kar raha hai. " +
               "Mujhe bata dein, agla version helper wala tareeqa use karega.\n" +
               "4) Samne wale ki ijazat ke bina call record na karein.", 14f, Color.LTGRAY)
        )
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        showSetup()
    }
}
