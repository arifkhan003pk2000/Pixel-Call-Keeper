package com.pixelcallkeeper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Person(val label: String, val dir: File, val legacy: Boolean)

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0B0F1A")
    private val accent = Color.parseColor("#22C5F0")
    private val audioExt = setOf("wav", "m4a")
    private lateinit var content: FrameLayout
    private lateinit var playerBar: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var posView: TextView
    private lateinit var titleView: TextView
    private lateinit var playBtn: Button
    private lateinit var speedBtn: Button
    private var player: MediaPlayer? = null
    private var playingFile: File? = null
    private var speed = 1.0f
    private var recPerson: File? = null
    private var recTypeDir: File? = null
    private var recTypeName = ""
    private var tab = "home"
    private val handler = Handler(Looper.getMainLooper())
    private var statusView: TextView? = null
    private var noteView: TextView? = null
    private var toggleBtn: Button? = null
    private var voipBtn: Button? = null
    private var bothBtn: Button? = null

    private val ticker = object : Runnable {
        override fun run() { ensureService(); refreshHome(); handler.postDelayed(this, 1000) }
    }
    private val playTick = object : Runnable {
        override fun run() {
            updatePos()
            if (player?.isPlaying == true) handler.postDelayed(this, 500)
        }
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

    private fun smallBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 12f
        setPadding(0, 0, 0, 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(2); marginEnd = dp(2) }
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

        // ----- player bar -----
        playerBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#151B2B"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        titleView = tv("", 13f, Color.LTGRAY)
        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) { try { player?.seekTo(p) } catch (_: Exception) {}; updatePos() }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        posView = tv("0:00 / 0:00", 13f, Color.WHITE)
        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        playBtn = smallBtn("❚❚") { togglePause() }
        speedBtn = smallBtn("1x") { cycleSpeed() }
        ctl.addView(smallBtn("⏪10") { seekBy(-10000) })
        ctl.addView(playBtn)
        ctl.addView(smallBtn("10⏩") { seekBy(10000) })
        ctl.addView(speedBtn)
        ctl.addView(smallBtn("Share") { playingFile?.let { share(it) } })
        ctl.addView(smallBtn("✕") { stopPlayback(); refreshList() })
        playerBar.addView(titleView); playerBar.addView(seek); playerBar.addView(posView); playerBar.addView(ctl)
        root.addView(playerBar)

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

    private fun refreshList() { if (tab == "rec") showRecordings() }

    // ---------- Home ----------
    private fun showHome() {
        tab = "home"
        statusView = tv("", 18f, accent, true)
        noteView = tv("", 14f, Color.parseColor("#FFB74D"))
        toggleBtn = btn("Phone call record (manual)") { RecorderService.toggleManual(4) }
        voipBtn = btn("WhatsApp: sirf samne wale ki awaaz (safe)") { RecorderService.toggleManual(9) }
        bothBtn = btn("WhatsApp: dono awaaz (call kharab ho sakti hai)") { RecorderService.toggleManual(7) }
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
            statusView!!, noteView!!, sw, toggleBtn!!, voipBtn!!, bothBtn!!,
            tv("Helper pehle Setup ke mutabiq USB se chalana hota hai. " +
               "Normal phone call par recording khud shuru hoti hai. WhatsApp / Instagram par sirf manual button se.", 14f, Color.LTGRAY)
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
            voipBtn?.text = "Recording band karein"
            voipBtn?.isEnabled = true
            bothBtn?.text = "Recording band karein"
            bothBtn?.isEnabled = true
        } else {
            s.text = when {
                !RecorderService.serviceUp -> "Service band hai (microphone permission dein)"
                RecorderService.helperConnected -> "Taiyar: helper connected"
                else -> "Helper connected nahi. Setup mein command dekhein."
            }
            b.text = "Phone call record (manual)"
            b.isEnabled = RecorderService.helperConnected
            voipBtn?.text = "WhatsApp: sirf samne wale ki awaaz (safe)"
            voipBtn?.isEnabled = RecorderService.helperConnected
            bothBtn?.text = "WhatsApp: dono awaaz (call kharab ho sakti hai)"
            bothBtn?.isEnabled = RecorderService.helperConnected
        }
        noteView?.text = RecorderService.lastError?.let { "Note: $it" } ?: ""
    }

    private fun hasPerms() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPerms() {
        val p = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(p.toTypedArray(), 1)
    }

    // ---------- Folder helpers ----------
    private fun baseDir() = RecorderService.dir(this)

    private fun audioFiles(d: File): List<File> =
        d.listFiles { f -> f.isFile && f.extension in audioExt }?.toList() ?: emptyList()

    private fun allAudio(d: File): Sequence<File> =
        d.walkTopDown().filter { it.isFile && it.extension in audioExt }

    private fun persons(): List<Person> {
        val base = baseDir()
        val out = mutableListOf<Person>()
        base.listFiles { f -> f.isDirectory }?.forEach { d ->
            if (allAudio(d).any()) out += Person(if (d.name == "_new") "Unsorted" else d.name, d, false)
        }
        if (audioFiles(base).isNotEmpty()) out += Person("Purani recordings", base, true)
        return out.sortedByDescending { p -> if (p.legacy) "" else (allAudio(p.dir).maxOfOrNull { it.name } ?: "") }
    }

    private fun typeOrder(n: String) = when (n) {
        "Phone Calls" -> 0; "WhatsApp Calls" -> 1; "Instagram Calls" -> 2; "Other Calls" -> 3; else -> 4
    }

    private fun typeIcon(n: String) = when {
        n.startsWith("Phone") -> "📞"
        n.startsWith("WhatsApp") -> "💬"
        n.startsWith("Instagram") -> "📸"
        else -> "🎙"
    }

    private fun types(person: File): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        person.listFiles { f -> f.isDirectory }?.forEach { d ->
            if (audioFiles(d).isNotEmpty()) out += d.name to d
        }
        if (audioFiles(person).isNotEmpty()) out += "Recordings" to person
        return out.sortedBy { typeOrder(it.first) }
    }

    private fun personLabel(): String = recPerson?.let { if (it.name == "_new") "Unsorted" else it.name } ?: ""

    private fun dayLabel(day: String): String {
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = iso.format(Date())
        val yest = iso.format(Date(System.currentTimeMillis() - 86400000L))
        val pretty = try { SimpleDateFormat("dd MMM yyyy", Locale.US).format(iso.parse(day)!!) } catch (_: Exception) { day }
        return when (day) { today -> "Aaj  •  $pretty"; yest -> "Kal  •  $pretty"; else -> pretty }
    }

    private fun lastLabel(files: List<File>): String {
        val last = files.maxOfOrNull { it.name } ?: ""
        return if (last.length >= 10) dayLabel(last.substring(0, 10)) else ""
    }

    // ---------- Recordings screens ----------
    private fun showRecordings() {
        tab = "rec"
        val td = recTypeDir
        if (td != null && audioFiles(td).isNotEmpty()) { showFiles(td); return }
        recTypeDir = null
        val p = recPerson
        if (p != null && p.exists() && types(p).isNotEmpty()) { showTypes(p); return }
        recPerson = null
        showPersons()
    }

    private fun openPerson(p: Person) {
        if (p.legacy) {
            recPerson = null; recTypeDir = p.dir; recTypeName = "Purani recordings"
        } else {
            recPerson = p.dir
            val t = types(p.dir)
            if (t.size == 1) { recTypeDir = t[0].second; recTypeName = t[0].first } else recTypeDir = null
        }
        showRecordings()
    }

    private fun backFromFiles() {
        recTypeDir = null
        val p = recPerson
        if (p != null && types(p).size <= 1) recPerson = null
        showRecordings()
    }

    private fun showPersons() {
        val title = tv("Recordings", 28f, Color.WHITE, true)
        val search = EditText(this).apply {
            hint = "Naam ya number dhundhein"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true)
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val all = persons()
        fun fill(q: String) {
            box.removeAllViews()
            val list = all.filter { it.label.contains(q.trim(), ignoreCase = true) }
            if (list.isEmpty()) box.addView(tv(if (all.isEmpty()) "Abhi koi recording nahi hai." else "Kuch nahi mila.", 15f, Color.LTGRAY))
            list.forEach { p ->
                val files = if (p.legacy) audioFiles(p.dir) else allAudio(p.dir).toList()
                box.addView(Button(this).apply {
                    text = "👤  ${p.label}\n${files.size} recording  •  ${lastLabel(files)}"
                    isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener { openPerson(p) }
                    setOnLongClickListener { if (!p.legacy && p.dir.name != "_new") personMenu(p); true }
                    layoutParams = LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(8) }
                })
            }
        }
        fill("")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { fill(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val hint = tv("Folder ko dabaye rakhein: naam badalna ya delete.", 12f, Color.GRAY)
        screen(title, search, box, hint)
    }

    private fun showTypes(person: File) {
        val views = mutableListOf<View>(
            Button(this).apply {
                text = "← Peeche"; isAllCaps = false
                setOnClickListener { recPerson = null; showRecordings() }
                layoutParams = LinearLayout.LayoutParams(-2, dp(44))
            },
            tv(personLabel(), 24f, Color.WHITE, true)
        )
        types(person).forEach { (name, dir) ->
            val files = audioFiles(dir)
            views += Button(this).apply {
                text = "${typeIcon(name)}  $name\n${files.size} recording  •  ${lastLabel(files)}"
                isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { recTypeDir = dir; recTypeName = name; showRecordings() }
                layoutParams = LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(8) }
            }
        }
        screen(*views.toTypedArray())
    }

    private fun showFiles(dir: File) {
        val crumb = if (recPerson != null) "${personLabel()} › $recTypeName" else recTypeName
        val views = mutableListOf<View>(
            Button(this).apply {
                text = "← Peeche"; isAllCaps = false
                setOnClickListener { backFromFiles() }
                layoutParams = LinearLayout.LayoutParams(-2, dp(44))
            },
            tv(crumb, 22f, Color.WHITE, true)
        )
        val files = audioFiles(dir).sortedByDescending { it.name }
        var lastDay = ""
        files.forEach { f ->
            val day = if (f.name.length >= 10) f.name.substring(0, 10) else ""
            if (day != lastDay) {
                lastDay = day
                views += tv(dayLabel(day), 15f, accent, true).apply { setPadding(dp(4), dp(18), dp(4), dp(4)) }
            }
            val time = if (f.name.length >= 16) f.name.substring(11, 16).replace('-', ':') else ""
            val dur = if (f.extension == "wav" && f.length() > 44) {
                val s = (f.length() - 44) / 32000
                "  •  %d:%02d".format(s / 60, s % 60)
            } else ""
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(Button(this).apply {
                text = (if (playingFile == f) "■  " else "▶  ") + time + dur
                isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { play(f, crumb) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
            row.addView(Button(this).apply {
                text = "Share"; isAllCaps = false; textSize = 12f
                setPadding(0, 0, 0, 0)
                setOnClickListener { share(f) }
            }, LinearLayout.LayoutParams(dp(64), dp(52)))
            row.addView(Button(this).apply {
                text = "⋮"; isAllCaps = false
                setOnClickListener { fileMenu(f) }
            }, LinearLayout.LayoutParams(dp(48), dp(52)))
            views += row
        }
        screen(*views.toTypedArray())
    }

    // ---------- Menus ----------
    private fun personMenu(p: Person) {
        AlertDialog.Builder(this).setTitle(p.label)
            .setItems(arrayOf("Naam badlein (jaise unknown number ko naam dena)", "Poora folder delete")) { _, which ->
                if (which == 0) renamePerson(p) else confirmDeletePerson(p)
            }.show()
    }

    private fun renamePerson(p: Person) {
        val input = EditText(this).apply { setText(p.label); setSelectAllOnFocus(true) }
        AlertDialog.Builder(this).setTitle("Naya naam").setView(input)
            .setPositiveButton("Theek hai") { _, _ ->
                val target = File(baseDir(), RecorderService.cleanName(input.text.toString()))
                if (target != p.dir) {
                    stopPlayback()
                    if (target.exists()) { p.dir.copyRecursively(target, false); p.dir.deleteRecursively() }
                    else if (!p.dir.renameTo(target)) { p.dir.copyRecursively(target, false); p.dir.deleteRecursively() }
                }
                recPerson = null; recTypeDir = null
                showRecordings()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmDeletePerson(p: Person) {
        AlertDialog.Builder(this).setTitle("Poora folder delete karein?")
            .setMessage("${p.label}: is ki saari recordings mit jayengi.")
            .setPositiveButton("Delete") { _, _ ->
                stopPlayback(); p.dir.deleteRecursively()
                recPerson = null; recTypeDir = null
                showRecordings()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun fileMenu(f: File) {
        AlertDialog.Builder(this).setTitle(f.nameWithoutExtension)
            .setItems(arrayOf("Share", "Dusre naam / folder mein bhejein", "Delete")) { _, which ->
                when (which) { 0 -> share(f); 1 -> moveDialog(f); else -> confirmDelete(f) }
            }.show()
    }

    private fun moveDialog(f: File) {
        val base = baseDir()
        val deep = f.parentFile?.parentFile?.parentFile == base
        val type = if (deep) f.parentFile!!.name else "Other Calls"
        val current = if (deep) f.parentFile!!.parentFile!!.name else (f.parentFile?.name ?: "")
        val input = EditText(this).apply { setText(current); setSelectAllOnFocus(true) }
        AlertDialog.Builder(this).setTitle("Kis naam / number ke folder mein?").setView(input)
            .setPositiveButton("Theek hai") { _, _ ->
                val dest = File(File(base, RecorderService.cleanName(input.text.toString())), type).apply { mkdirs() }
                if (playingFile == f) stopPlayback()
                val target = File(dest, f.name)
                if (!f.renameTo(target)) { f.copyTo(target, true); f.delete() }
                recPerson = dest.parentFile; recTypeDir = dest; recTypeName = type
                showRecordings()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this).setTitle("Delete karein?").setMessage(f.name)
            .setPositiveButton("Delete") { _, _ ->
                if (playingFile == f) stopPlayback()
                f.delete(); showRecordings()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- Share ----------
    private fun share(f: File) {
        try {
            val b = Uri.Builder().scheme("content").authority(ShareProvider.AUTH)
            f.relativeTo(baseDir()).path.split(File.separatorChar).forEach { b.appendPath(it) }
            val uri = b.build()
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Recording kis par bhejni hai?"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share nahi ho saki: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Player ----------
    private fun fmt(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    private fun updatePos() {
        val p = player ?: return
        try {
            seek.max = p.duration
            seek.progress = p.currentPosition
            posView.text = "${fmt(p.currentPosition)} / ${fmt(p.duration)}"
        } catch (_: IllegalStateException) {}
    }

    private fun play(f: File, crumb: String) {
        if (playingFile == f) { togglePause(); return }
        stopPlayback()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(f.absolutePath)
            mp.prepare()
            mp.start()
            mp.setOnCompletionListener { playBtn.text = "▶"; updatePos() }
            player = mp
            playingFile = f
            titleView.text = "$crumb  •  ${f.nameWithoutExtension}"
            playerBar.visibility = View.VISIBLE
            playBtn.text = "❚❚"
            if (speed != 1.0f) applySpeed()
            handler.post(playTick)
        } catch (e: Exception) {
            Toast.makeText(this, "Play nahi ho saki: ${e.message}", Toast.LENGTH_LONG).show()
        }
        refreshList()
    }

    private fun togglePause() {
        val p = player ?: return
        try {
            if (p.isPlaying) { p.pause(); playBtn.text = "▶" }
            else { p.start(); playBtn.text = "❚❚"; if (speed != 1.0f) applySpeed(); handler.post(playTick) }
        } catch (_: IllegalStateException) {}
    }

    private fun seekBy(delta: Int) {
        val p = player ?: return
        try {
            val t = (p.currentPosition + delta).coerceIn(0, p.duration)
            p.seekTo(t)
            updatePos()
        } catch (_: IllegalStateException) {}
    }

    private fun cycleSpeed() {
        speed = when (speed) { 1.0f -> 1.5f; 1.5f -> 2.0f; 2.0f -> 0.75f; else -> 1.0f }
        speedBtn.text = speed.toString().removeSuffix(".0") + "x"
        applySpeed()
    }

    private fun applySpeed() {
        val p = player ?: return
        try {
            val was = p.isPlaying
            p.playbackParams = PlaybackParams().setSpeed(speed)
            if (!was) p.pause()
        } catch (_: Exception) {}
    }

    private fun stopPlayback() {
        handler.removeCallbacks(playTick)
        try { player?.stop() } catch (_: Exception) {}
        player?.release(); player = null; playingFile = null
        playerBar.visibility = View.GONE
    }

    // ---------- Setup ----------
    private fun showSetup() {
        tab = "setup"
        val cmd = tv("adb shell \"CLASSPATH=\$(pm path com.pixelcallkeeper | cut -d: -f2) " +
            "nohup setsid app_process / com.pixelcallkeeper.Helper >/data/local/tmp/pck.log 2>&1 &\"",
            13f, accent).apply { setTextIsSelectable(true) }
        screen(
            tv("Setup", 28f, Color.WHITE, true),
            tv("Microphone: " + if (hasPerms()) "Allowed" else "Allow nahi hai", 16f, accent, true),
            btn("Permissions do (mic, call log, contacts)") { askPerms() },
            btn("WhatsApp / Instagram naam ke liye: Notification access") {
                try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {}
            },
            btn("App settings kholen") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            tv("Helper chalane ka tareeqa (har restart ke baad ek baar):", 15f, Color.WHITE, true),
            tv("1) Phone USB se computer se jodein, USB debugging ON rakhein.\n" +
               "2) Computer par platform-tools folder mein cmd kholein aur ye command chalayein:", 14f, Color.LTGRAY),
            cmd,
            tv("3) Kuch second mein Home par 'Taiyar: helper connected' aa jayega. USB hata sakte hain.\n" +
               "4) Battery > Unrestricted zaroor karein.\n" +
               "5) Samne wale ki ijazat ke bina call record na karein.", 14f, Color.LTGRAY),
            btn("Service band karein") { RecorderService.stopAll() }
        )
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        ensureService()
        if (tab == "setup") showSetup() else if (tab == "home") showHome()
    }
}
