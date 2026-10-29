package com.pixelcallkeeper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

    private val cBg = Color.parseColor("#0E0607")
    private val cCard = Color.parseColor("#1C0E11")
    private val cCard2 = Color.parseColor("#2B141A")
    private val cCyan = Color.parseColor("#FF4D4D")
    private val cPurple = Color.parseColor("#9B1020")
    private val cMuted = Color.parseColor("#C4A1A6")
    private val cGreen = Color.parseColor("#34D399")
    private val cOrange = Color.parseColor("#FBBF24")
    private val cRed = Color.parseColor("#F87171")
    private val audioExt = setOf("wav", "m4a")

    private lateinit var content: FrameLayout
    private lateinit var playerBar: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var posView: TextView
    private lateinit var titleView: TextView
    private lateinit var playBtn: Button
    private lateinit var speedBtn: Button
    private val navButtons = mutableMapOf<String, Button>()
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
    private var waView: TextView? = null
    private var dotView: View? = null
    private var toggleBtn: Button? = null
    private var voipBtn: Button? = null

    private val ticker = object : Runnable {
        override fun run() { ensureService(); refreshHome(); handler.postDelayed(this, 1000) }
    }
    private val playTick = object : Runnable {
        override fun run() {
            updatePos()
            if (player?.isPlaying == true) handler.postDelayed(this, 500)
        }
    }

    // ---------- style helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, r: Int = 16) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun gradBg(r: Int = 16) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cCyan, cPurple))
            .apply { cornerRadius = dp(r).toFloat() }

    private fun tv(t: String, size: Float = 16f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        background = gradBg(16); stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(10) }
    }

    private fun ghostBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f
        background = roundBg(cCard2, 16); stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) }
    }

    private fun listBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        background = roundBg(cCard, 16); stateListAnimator = null
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minHeight = dp(60)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    private fun smallBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 12f; setTextColor(Color.WHITE)
        background = roundBg(cCard2, 12); stateListAnimator = null
        setPadding(0, 0, 0, 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(3); marginEnd = dp(3) }
    }

    private fun card(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(cCard, 20)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            views.forEach { addView(it) }
        }

    private fun badge(type: String, sizeDp: Int = 44): TextView {
        val glyph: String
        val bg = GradientDrawable()
        when {
            type.startsWith("WhatsApp") -> { glyph = "✆\uFE0E"; bg.shape = GradientDrawable.OVAL; bg.setColor(Color.parseColor("#25D366")) }
            type.startsWith("Phone") -> { glyph = "✆\uFE0E"; bg.shape = GradientDrawable.OVAL; bg.setColor(Color.parseColor("#2F80ED")) }
            type.startsWith("Instagram") -> {
                glyph = "◎"
                bg.orientation = GradientDrawable.Orientation.BL_TR
                bg.colors = intArrayOf(Color.parseColor("#FEDA75"), Color.parseColor("#FA7E1E"),
                    Color.parseColor("#D62976"), Color.parseColor("#962FBF"), Color.parseColor("#4F5BD5"))
                bg.cornerRadius = dp(sizeDp / 3).toFloat()
            }
            else -> { glyph = "♪"; bg.shape = GradientDrawable.OVAL; bg.setColor(Color.parseColor("#6B5559")) }
        }
        return TextView(this).apply {
            text = glyph
            setTextColor(Color.WHITE)
            textSize = sizeDp * 0.45f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = bg
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
    }

    private fun initialBadge(name: String, sizeDp: Int = 44): TextView {
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#7A1B25")) }
        val ch = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
        return TextView(this).apply {
            text = ch; setTextColor(Color.WHITE); textSize = sizeDp * 0.42f
            gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            background = bg
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
    }

    private fun iconRow(left: View, title: String, sub: String, trailing: List<View>, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundBg(cCard, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        left.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) }
        row.addView(left)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 16f, Color.WHITE, true))
        col.addView(tv(sub, 12f, cMuted))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        trailing.forEach { row.addView(it) }
        return row
    }

    private fun setTab(t: String) {
        tab = t
        navButtons.forEach { (k, b) ->
            if (k == t) { b.background = gradBg(14); b.setTextColor(Color.WHITE) }
            else { b.background = roundBg(Color.TRANSPARENT, 14); b.setTextColor(cMuted) }
        }
    }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
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
            background = roundBg(cCard, 20)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), 0, dp(8), dp(4)) }
        }
        titleView = tv("", 13f, cMuted)
        seek = SeekBar(this)
        seek.progressTintList = ColorStateList.valueOf(cCyan)
        seek.thumbTintList = ColorStateList.valueOf(cCyan)
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

        // ----- bottom nav -----
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundBg(cCard, 0)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val items = listOf<Triple<String, String, () -> Unit>>(
            Triple("home", "🏠  Home", { showHome() }),
            Triple("rec", "🎙  Recordings", { showRecordings() }),
            Triple("setup", "⚙  Setup", { showSetup() })
        )
        items.forEach { (key, label, act) ->
            val b = Button(this).apply {
                text = label; isAllCaps = false; textSize = 13f; stateListAnimator = null
                setOnClickListener { act() }
            }
            navButtons[key] = b
            nav.addView(b, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        root.addView(nav)
        setContentView(root)
        if (!hasPerms()) askPerms()
        showHome()
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { stopPlayback(); super.onDestroy() }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            tab == "rec" && recTypeDir != null -> backFromFiles()
            tab == "rec" && recPerson != null -> { recPerson = null; showRecordings() }
            tab != "home" -> showHome()
            else -> super.onBackPressed()
        }
    }

    private fun ensureService() {
        if (hasPerms() && !RecorderService.serviceUp) {
            try { startForegroundService(Intent(this, RecorderService::class.java)) } catch (_: Exception) {}
        }
    }

    private fun screen(vararg views: View): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(20), dp(18), dp(20)) }
        views.forEach { col.addView(it) }
        val sv = ScrollView(this).apply { addView(col) }
        content.removeAllViews(); content.addView(sv)
        return sv
    }

    private fun refreshList() { if (tab == "rec") showRecordings() }

    // ---------- Home ----------
    private fun showHome() {
        setTab("home")
        statusView = tv("", 17f, Color.WHITE, true)
        noteView = tv("", 13f, cOrange)
        waView = tv("", 13f, cMuted)
        dotView = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cOrange) }
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) }
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(dotView); addView(statusView)
        }
        toggleBtn = btn("Phone call record (manual)") { RecorderService.toggleManual(4) }
        voipBtn = ghostBtn("WhatsApp / Instagram record (manual)") { RecorderService.toggleManual(7) }
        val sw = Switch(this).apply {
            text = "Auto-record (call par khud shuru)"
            setTextColor(Color.WHITE)
            isChecked = RecorderService.auto
            setOnCheckedChangeListener { _, c -> RecorderService.setAuto(this@MainActivity, c) }
        }
        val sw2 = Switch(this).apply {
            text = "WhatsApp: halki awaaz tez karein (kaan se lagane par)"
            setTextColor(Color.WHITE)
            isChecked = RecorderService.boost
            setOnCheckedChangeListener { _, c -> RecorderService.setBoost(this@MainActivity, c) }
        }
        screen(
            tv("PixelCall Keeper", 28f, Color.WHITE, true),
            tv("Private recordings, aapke phone par", 14f, cMuted),
            card(statusRow, noteView!!, waView!!),
            card(sw),
            card(sw2),
            toggleBtn!!, voipBtn!!,
            tv("Normal phone call: kaan se lagake bhi record hoti hai.\n" +
               "WhatsApp / Instagram: speaker ON par sabse saaf. Kaan se lagane par awaaz khud-ba-khud adjust hoti hai.", 12f, cMuted)
                .apply { setPadding(dp(4), dp(14), dp(4), dp(4)) },
            tv("Build v11", 11f, cMuted)
        )
        refreshHome()
    }

    private fun refreshHome() {
        val s = statusView ?: return
        val b = toggleBtn ?: return
        val v = voipBtn
        val dot = dotView?.background as? GradientDrawable
        if (RecorderService.recording) {
            val sec = (System.currentTimeMillis() - RecorderService.startedAt) / 1000
            s.text = "Recording chal rahi hai: %02d:%02d".format(sec / 60, sec % 60)
            b.text = "Recording band karein"; b.isEnabled = true
            v?.text = "Recording band karein"; v?.isEnabled = true
            dot?.setColor(cRed)
        } else {
            s.text = when {
                !RecorderService.serviceUp -> "Service band hai (microphone permission dein)"
                RecorderService.helperConnected -> "Taiyar: helper connected"
                else -> "Helper connected nahi (Setup dekhein)"
            }
            b.text = "Phone call record (manual)"
            b.isEnabled = RecorderService.helperConnected
            v?.text = "WhatsApp / Instagram record (manual)"
            v?.isEnabled = PckAccessibilityService.connected
            dot?.setColor(if (RecorderService.helperConnected) cGreen else cOrange)
        }
        b.alpha = if (b.isEnabled) 1f else 0.45f
        v?.alpha = if (v?.isEnabled == true) 1f else 0.45f
        noteView?.text = listOfNotNull(RecorderService.lastError?.let { "Note: $it" }, RecorderService.info).joinToString("\n")
        waView?.text = if (PckAccessibilityService.connected)
            "WhatsApp / Instagram: taiyar (speaker ON rakhein)"
        else "WhatsApp / Instagram: Setup mein Accessibility ON karein"
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

    // ---------- time helpers (12 ghanta) ----------
    private val fileFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private fun fileDate(name: String): Date? =
        try { fileFmt.parse(name.substringBeforeLast('.')) } catch (_: Exception) { null }
    private fun t12(name: String): String =
        fileDate(name)?.let { SimpleDateFormat("h:mm a", Locale.US).format(it) } ?: ""
    private fun dt12(name: String): String =
        fileDate(name)?.let { SimpleDateFormat("dd MMM, h:mm a", Locale.US).format(it) } ?: name

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

    private fun backBtn(onClick: () -> Unit) = Button(this).apply {
        text = "←  Peeche"; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
        background = roundBg(cCard2, 14); stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(42))
    }

    // ---------- Recordings screens ----------
    private fun showRecordings() {
        setTab("rec")
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
            hint = "🔍  Naam ya number dhundhein"
            setTextColor(Color.WHITE); setHintTextColor(cMuted); setSingleLine(true)
            background = roundBg(cCard, 16)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val all = persons()
        fun fill(q: String) {
            box.removeAllViews()
            val list = all.filter { it.label.contains(q.trim(), ignoreCase = true) }
            if (list.isEmpty()) box.addView(tv(if (all.isEmpty()) "Abhi koi recording nahi hai." else "Kuch nahi mila.", 15f, cMuted))
            list.forEach { p ->
                val files = if (p.legacy) audioFiles(p.dir) else allAudio(p.dir).toList()
                val trailing = if (p.legacy) emptyList() else types(p.dir).map { (tn, _) ->
                    badge(tn, 24).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(6) }
                    }
                }
                val r = iconRow(initialBadge(p.label), p.label,
                    "${files.size} recording  •  ${lastLabel(files)}", trailing) { openPerson(p) }
                r.setOnLongClickListener { if (!p.legacy && p.dir.name != "_new") personMenu(p); true }
                box.addView(r)
            }
        }
        fill("")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { fill(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val hint = tv("Folder ko dabaye rakhein: naam badalna ya delete.", 12f, cMuted)
            .apply { setPadding(dp(4), dp(14), dp(4), dp(4)) }
        screen(title, search, box, hint)
    }

    private fun showTypes(person: File) {
        val views = mutableListOf<View>(
            backBtn { recPerson = null; showRecordings() },
            tv(personLabel(), 24f, Color.WHITE, true).apply { setPadding(dp(4), dp(12), dp(4), dp(4)) }
        )
        types(person).forEach { (name, dir) ->
            val files = audioFiles(dir)
            views += iconRow(badge(name), name, "${files.size} recording  •  ${lastLabel(files)}", emptyList()) {
                recTypeDir = dir; recTypeName = name; showRecordings()
            }
        }
        screen(*views.toTypedArray())
    }

    private fun showFiles(dir: File) {
        val crumb = if (recPerson != null) "${personLabel()} › $recTypeName" else recTypeName
        val views = mutableListOf<View>(
            backBtn { backFromFiles() },
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(12), dp(4), dp(4))
                val bd = badge(recTypeName, 34)
                bd.layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) }
                addView(bd)
                addView(tv(crumb, 20f, Color.WHITE, true))
            }
        )
        val files = audioFiles(dir).sortedByDescending { it.name }
        var lastDay = ""
        files.forEach { f ->
            val day = if (f.name.length >= 10) f.name.substring(0, 10) else ""
            if (day != lastDay) {
                lastDay = day
                views += tv(dayLabel(day), 14f, cCyan, true).apply { setPadding(dp(4), dp(18), dp(4), dp(2)) }
            }
            val dur = durText(f) + "  •  " + sizeText(f)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val play = listBtn((if (playingFile == f) "■  " else "▶  ") + t12(f.name) + dur) { play(f, crumb) }
            play.layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply { topMargin = dp(8) }
            play.minHeight = dp(56)
            row.addView(play)
            row.addView(Button(this).apply {
                text = "Share"; isAllCaps = false; textSize = 12f; setTextColor(Color.WHITE)
                background = roundBg(cCard2, 14); stateListAnimator = null; setPadding(0, 0, 0, 0)
                setOnClickListener { share(f) }
            }, LinearLayout.LayoutParams(dp(62), dp(56)).apply { topMargin = dp(8); marginStart = dp(6) })
            row.addView(Button(this).apply {
                text = "⋮"; isAllCaps = false; textSize = 18f; setTextColor(Color.WHITE)
                background = roundBg(cCard2, 14); stateListAnimator = null; setPadding(0, 0, 0, 0)
                setOnClickListener { fileMenu(f) }
            }, LinearLayout.LayoutParams(dp(46), dp(56)).apply { topMargin = dp(8); marginStart = dp(6) })
            views += row
        }
        screen(*views.toTypedArray())
    }

    // ---------- Menus ----------
    private fun personMenu(p: Person) {
        AlertDialog.Builder(this).setTitle(p.label)
            .setItems(arrayOf(
                "Naam badlein (jaise unknown number ko naam dena)",
                "Poora folder Downloads mein bhejein (app se hata kar)",
                "Poora folder Downloads mein copy karein",
                "Poora folder delete"
            )) { _, which ->
                when (which) {
                    0 -> renamePerson(p)
                    1 -> saveFolderDl(p, true)
                    2 -> saveFolderDl(p, false)
                    else -> confirmDeletePerson(p)
                }
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
        val items = mutableListOf(
            "Share",
            "Downloads mein bhejein (app se hata kar)",
            "Downloads mein copy karein (app mein bhi rahe)",
            "Dusre naam / folder mein bhejein",
            "Delete"
        )
        if (f.extension == "wav") items.add("Chhoti file (m4a) banayein")
        AlertDialog.Builder(this).setTitle(dt12(f.name))
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> share(f)
                    1 -> saveDl(f, true)
                    2 -> saveDl(f, false)
                    3 -> moveDialog(f)
                    4 -> confirmDelete(f)
                    else -> compressOne(f)
                }
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
        AlertDialog.Builder(this).setTitle("Delete karein?").setMessage(dt12(f.name))
            .setPositiveButton("Delete") { _, _ ->
                if (playingFile == f) stopPlayback()
                f.delete(); showRecordings()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- compress / downloads ----------
    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private fun sizeText(f: File): String =
        if (f.length() >= 1048576) "%.1f MB".format(f.length() / 1048576.0) else "${f.length() / 1024} KB"

    private val durCache = HashMap<String, Long>()
    private fun durText(f: File): String {
        val key = f.absolutePath + f.length()
        val sec = durCache.getOrPut(key) {
            if (f.extension == "wav") maxOf(0L, (f.length() - 44) / 32000)
            else try {
                val r = android.media.MediaMetadataRetriever()
                r.setDataSource(f.absolutePath)
                val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                r.release()
                ms / 1000
            } catch (_: Exception) { 0L }
        }
        return if (sec > 0) "  •  %d:%02d".format(sec / 60, sec % 60) else ""
    }

    private fun compressFile(f: File): File? {
        val m4a = File(f.parentFile, f.nameWithoutExtension + ".m4a")
        return if (AacEncoder.encode(f, m4a) && m4a.length() > 1000) {
            runOnUiThread { if (playingFile == f) stopPlayback() }
            f.delete()
            m4a
        } else {
            m4a.delete()
            null
        }
    }

    private fun compressOne(f: File) {
        toast("File chhoti ki ja rahi hai...")
        Thread {
            val before = f.length()
            val r = compressFile(f)
            toast(if (r != null) "Ho gaya: ${"%.1f".format(before / 1048576.0)} MB se ${sizeText(r)}" else "Chhoti nahi ho saki")
            runOnUiThread { refreshList() }
        }.start()
    }

    private fun compressAll() {
        toast("Purani WAV files chhoti ki ja rahi hain...")
        Thread {
            val list = allAudio(baseDir()).filter { it.extension == "wav" }.toList()
            var saved = 0L
            var n = 0
            list.forEach { f ->
                val before = f.length()
                val r = compressFile(f)
                if (r != null) { n++; saved += before - r.length() }
            }
            toast("$n files chhoti hui, ${"%.1f".format(saved / 1048576.0)} MB bach gaye")
            runOnUiThread { refreshList() }
        }.start()
    }

    private fun pruneEmpty(d: File?) {
        var cur = d
        val base = baseDir()
        while (cur != null && cur != base && cur.isDirectory && cur.listFiles()?.isEmpty() == true) {
            val parent = cur.parentFile
            cur.delete()
            cur = parent
        }
    }

    // move=true: file sirf Downloads mein rahegi (app se hat jayegi). move=false: dono jagah.
    private fun saveFiles(files: List<File>, move: Boolean) {
        if (move) runOnUiThread { stopPlayback() }
        Thread {
            val base = baseDir()
            var n = 0
            files.forEach { f ->
                val parent = f.parentFile
                if (Exporter.toDownloads(this, f, base)) {
                    n++
                    if (move) { f.delete(); pruneEmpty(parent) }
                }
            }
            toast(
                if (n == 0) "Save nahi ho saki"
                else if (move) "$n recording Downloads/PixelCallKeeper mein chali gayi (app se hata di)"
                else "$n recording Downloads/PixelCallKeeper mein copy ho gayi"
            )
            runOnUiThread { refreshList() }
        }.start()
    }

    private fun saveDl(f: File, move: Boolean) = saveFiles(listOf(f), move)

    private fun saveAllDl(move: Boolean) = saveFiles(allAudio(baseDir()).toList(), move)

    private fun saveFolderDl(p: Person, move: Boolean) = saveFiles(allAudio(p.dir).toList(), move)

    // ---------- Share ----------
    private fun share(f: File) {
        if (f.extension == "wav") {
            toast("File chhoti ki ja rahi hai, phir share hogi...")
            Thread {
                val m = compressFile(f)
                runOnUiThread { refreshList(); doShare(m ?: f) }
            }.start()
            return
        }
        doShare(f)
    }

    private fun doShare(f: File) {
        try {
            val b = Uri.Builder().scheme("content").authority(ShareProvider.AUTH)
            f.relativeTo(baseDir()).path.split(File.separatorChar).forEach { b.appendPath(it) }
            val uri = b.build()
            val i = Intent(Intent.ACTION_SEND).apply {
                type = Exporter.mime(f)
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
            titleView.text = "$crumb  •  ${dt12(f.name)}"
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
    private val helperCmd = "adb shell \"CLASSPATH=\$(pm path com.pixelcallkeeper | cut -d: -f2) " +
        "nohup setsid app_process / com.pixelcallkeeper.Helper >/data/local/tmp/pck.log 2>&1 &\""

    private fun showSetup() {
        setTab("setup")
        val cmd = tv(helperCmd, 12f, cCyan).apply { setTextIsSelectable(true) }
        screen(
            tv("Setup", 28f, Color.WHITE, true),
            card(
                tv("Permissions", 16f, Color.WHITE, true),
                tv("Microphone: " + if (hasPerms()) "Allowed ✓" else "Allow nahi hai", 13f, if (hasPerms()) cGreen else cOrange),
                btn("Permissions do (mic, call log, contacts)") { askPerms() }
            ),
            card(
                tv("WhatsApp / Instagram", 16f, Color.WHITE, true),
                tv("Accessibility: " + if (PckAccessibilityService.connected) "ON ✓" else "OFF", 13f,
                    if (PckAccessibilityService.connected) cGreen else cOrange),
                tv("Is se mic call ke dauran chalta rehta hai. Ye service screen ka koi data nahi padhti. " +
                   "Settings > Accessibility > Installed apps > PixelCall Keeper ko ON karein.", 12f, cMuted),
                btn("Accessibility settings kholen") {
                    try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
                },
                ghostBtn("Notification access (naam pakadne ke liye)") {
                    try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {}
                }
            ),
            card(
                tv("Helper (phone calls ke liye)", 16f, Color.WHITE, true),
                tv("Har phone restart ke baad ek baar USB se chalana hota hai. " +
                   "Cmd (platform-tools folder) mein ye command chalayein:", 12f, cMuted),
                cmd,
                ghostBtn("Command copy karein") {
                    val cm = getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("cmd", helperCmd))
                    Toast.makeText(this, "Copy ho gaya", Toast.LENGTH_SHORT).show()
                }
            ),
            card(
                tv("Storage / Downloads", 16f, Color.WHITE, true),
                tv("Nayi recordings chhoti m4a file mein save hoti hain (MB kam, awaaz wahi).", 12f, cMuted),
                Switch(this).apply {
                    text = "Nayi recordings Downloads mein bhi copy hon (do jagah rahengi)"
                    setTextColor(Color.WHITE)
                    isChecked = RecorderService.autoDownload
                    setOnCheckedChangeListener { _, c -> RecorderService.setAutoDownload(this@MainActivity, c) }
                },
                ghostBtn("Purani saari WAV ko chhota (m4a) karein") { compressAll() },
                ghostBtn("Saari recordings Downloads mein bhejein (app se hata kar)") { saveAllDl(true) },
                ghostBtn("Saari recordings Downloads mein copy karein") { saveAllDl(false) }
            ),
            card(
                tv("Zaroori", 16f, Color.WHITE, true),
                tv("• Battery > Unrestricted karein.\n• Samne wale ki ijazat ke bina call record na karein.", 12f, cMuted),
                ghostBtn("App settings kholen") {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                },
                ghostBtn("Service band karein") { RecorderService.stopAll() }
            )
        )
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        ensureService()
        if (tab == "setup") showSetup() else if (tab == "home") showHome()
    }
}
