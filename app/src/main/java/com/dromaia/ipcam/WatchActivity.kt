package com.dromaia.ipcam

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live view.
 *  - without Watch.ID: the cameras four per page (light stream). Tap / OK opens one alone here; Back returns to the grid.
 *  - with Watch.ID: that camera alone, full quality; ‹ › go through the others; Back closes.
 *  - Watch.GROUP: only the cameras of that group. Watch.TOUR: one camera after the other by itself.
 * One camera alone has a bar: back, previous / next, quality, sound, photo, recording, mini window (phone),
 * turning the camera (PTZ), talking (phone cameras), tour. On a TV the bar is hidden until OK / Menu, and the remote
 * does the rest: ◄ ► next / previous, 1–9 camera N, 0 the grid.
 * The videos play only while the screen is in front (or in the mini window): released in onStop.
 */
class WatchActivity : Base() {
    companion object {
        private const val REQ_SNAP = 41
        private const val REQ_REC = 42
        private const val REQ_MIC = 43
        private const val SPEED = 0.5f       // PTZ speed (-1..1)
        private const val BAR_MS = 6000L     // TV: the bar hides after 6 s without keys
    }

    private var cams: List<Camera> = emptyList()
    private var group: String? = null
    private var single = false     // one camera alone (else the grid of four)
    private var gridRoot = true    // Back from one camera returns to the grid (false when opened on one camera)
    private var index = 0          // the camera shown alone
    private var page = 0           // the page of the grid
    private var hq = true
    private var muted = true
    private var touring = false
    private var barOn = true       // the bar of one camera is wanted (phone: shown at first; TV: hidden at first)
    private var ptzMode = false
    private var ptzCam: Camera? = null
    private var talking = false
    private var pip = false
    private var stopped = true
    private var asking = false     // a permission question is open (do not go to the mini window)
    private var swallowKey = -1    // the rest of a key press already used (its up and repeats)
    private var swallowTouch = false
    private var heldKey = 0        // PTZ: the arrow key now held
    private var lastPtzError = 0L
    private var focusPos = -1      // grid: the tile to focus (the camera we came back from)
    private var rowsKey = ""
    private var recorder: Recorder? = null
    private val tiles = ArrayList<Tile>()
    private val ui = Handler(Looper.getMainLooper())
    private val work: ExecutorService = Executors.newSingleThreadExecutor() // PTZ and talk, in order
    private val rtl: Boolean by lazy { !L.en(this) }
    private val pipOk: Boolean by lazy {
        !tv && Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private lateinit var root: FrameLayout
    private lateinit var grid: LinearLayout
    private lateinit var gridBar: LinearLayout
    private lateinit var gridTitle: TextView
    private lateinit var gridTour: TextView
    private lateinit var gridPrev: TextView
    private lateinit var gridNext: TextView
    private lateinit var pageText: TextView
    private lateinit var bar: LinearLayout
    private lateinit var nameText: TextView
    private lateinit var prevBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var qualityBtn: TextView
    private lateinit var soundBtn: TextView
    private lateinit var recBtn: TextView
    private lateinit var pipBtn: TextView
    private lateinit var ptzBtn: TextView
    private lateinit var talkBtn: TextView
    private lateinit var tourBtn: TextView
    private lateinit var badge: TextView    // «جولة»
    private lateinit var recText: TextView  // «● 0:12»
    private lateinit var hint: TextView     // TV, first time
    private lateinit var label: TextView    // the camera's name for 3 s after changing camera
    private lateinit var ptzHint: TextView  // TV: how to turn the camera
    private var pad: View? = null           // phone: arrows to turn the camera

    private val hideBar = Runnable { if (talking) scheduleHide() else showBar(false) }
    private val hideHint = Runnable { if (::hint.isInitialized) hint.visibility = View.GONE }
    private val hideLabel = Runnable { if (::label.isInitialized) label.visibility = View.GONE }
    private val tourRun = Runnable {
        if (touring && single && !stopped) {
            step(1)
            scheduleTour()
        }
    }
    private val recTick = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val s = r.seconds
            recText.text = String.format(Locale.US, "● %d:%02d", s / 60, s % 60)
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getStringExtra(Watch.ID)
        group = intent.getStringExtra(Watch.GROUP)?.trim()?.ifEmpty { null }
        val all = Store.all(this)
        val g = group
        var list: List<Camera> = if (g != null) all.filter { it.group.trim() == g } else all
        if (id != null && list.none { it.id == id }) list = all // the camera is not in that group: go through all
        cams = list
        if (cams.isEmpty()) {
            toast(t("لا توجد كاميرات", "No cameras"))
            finish()
            return
        }
        val tourAsked = intent.getBooleanExtra(Watch.TOUR, false)
        single = id != null || tourAsked
        gridRoot = id == null
        index = if (id != null) maxOf(0, cams.indexOfFirst { it.id == id }) else 0
        touring = tourAsked && cams.size > 1
        barOn = !tv && !touring
        if (saved != null) {
            single = saved.getBoolean("single", single)
            gridRoot = saved.getBoolean("gridRoot", gridRoot)
            index = saved.getInt("index", index)
            page = saved.getInt("page", 0)
            hq = saved.getBoolean("hq", true)
            muted = saved.getBoolean("muted", true)
            touring = saved.getBoolean("tour", touring) && cams.size > 1
            barOn = saved.getBoolean("barOn", barOn)
        }
        index = index.coerceIn(0, cams.size - 1)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        // grid: the bar above the cameras; one camera: the cameras take the whole screen
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        gridBar = makeGridBar()
        content.addView(gridBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        content.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        bar = makeBar()
        val blp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        blp.gravity = Gravity.TOP
        root.addView(bar, blp)

        if (!tv) {
            val p = makePad()
            p.visibility = View.GONE
            pad = p
            overlay(p, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 24)
        }
        ptzHint = pill(15f)
        ptzHint.text = t(
            "وضع التحريك: الأسهم تحرّك الكاميرا · CH+ / CH− للتقريب والتبعيد · رجوع للخروج",
            "Move mode: arrows turn the camera · CH+ / CH− zoom in / out · Back to leave",
        )
        overlay(ptzHint, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        badge = pill(14f, Ui.GOLD)
        badge.text = t("⟳ جولة", "⟳ Tour")
        overlay(badge, Gravity.BOTTOM or Gravity.END)
        recText = pill(15f, Ui.RED)
        recText.textDirection = View.TEXT_DIRECTION_LTR
        overlay(recText, Gravity.BOTTOM or Gravity.START)
        label = pill(16f)
        overlay(label, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 32)
        hint = pill(18f)
        hint.text = t(
            "◄ ► للتنقل بين الكاميرات · OK للقائمة · 1–9 لكاميرا مباشرة",
            "◄ ► switch cameras · OK for the menu · 1–9 a camera directly",
        )
        overlay(hint, Gravity.CENTER)

        setContentView(root)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("single", single)
        out.putBoolean("gridRoot", gridRoot)
        out.putInt("index", index)
        out.putInt("page", page)
        out.putBoolean("hq", hq)
        out.putBoolean("muted", muted)
        out.putBoolean("tour", touring)
        out.putBoolean("barOn", barOn)
    }

    // the video plays only while the screen is in front (no data used in the background)
    override fun onStart() {
        super.onStart()
        stopped = false
        hideSystemBars()
        if (!::grid.isInitialized) return
        render()
        if (touring) scheduleTour()
    }

    override fun onStop() {
        stopped = true
        if (::grid.isInitialized) {
            stopRecording()
            talkUp()
            leavePtz()
            ui.removeCallbacks(tourRun)
            ui.removeCallbacks(hideBar)
            releaseAll()
        }
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try {
            work.shutdown()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // turning the phone (or the mini window changing size) does not reconnect: the same videos are only placed again
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::grid.isInitialized) layoutTiles(force = false)
        if (!pip) hideSystemBars()
    }

    // ---------------------------------------------------------------- building the screen

    private fun makeGridBar(): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.HORIZONTAL
        b.gravity = Gravity.CENTER_VERTICAL
        b.setBackgroundColor(0xB3000000.toInt())
        val p = Ui.dp(this, 8)
        b.setPadding(p, p, p, p)

        b.addView(small(t("→ رجوع", "← Back")) { finish() })
        gridTitle = Ui.text(this, "", 17f, Ui.TEXT, true)
        gridTitle.setPadding(p * 2, 0, p * 2, 0)
        gridTitle.isSingleLine = true
        b.addView(gridTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        gridTour = small(t("⟳ جولة", "⟳ Tour")) { startTour() }
        b.addView(gridTour)
        gridPrev = small(t("‹ السابق", "‹ Previous")) { goPage(-1) }
        b.addView(gridPrev)
        pageText = Ui.text(this, "", 15f, Ui.TEXT)
        pageText.setPadding(p, 0, p, 0)
        pageText.textDirection = View.TEXT_DIRECTION_LTR
        b.addView(pageText)
        gridNext = small(t("التالي ›", "Next ›")) { goPage(1) }
        b.addView(gridNext)
        return b
    }

    private fun makeBar(): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.HORIZONTAL
        b.gravity = Gravity.CENTER_VERTICAL
        b.setBackgroundColor(0xB3000000.toInt())
        val p = Ui.dp(this, 6)
        b.setPadding(p, p, p, p)
        b.addView(small(t("→ رجوع", "← Back")) { goBack() })

        // the rest scrolls sideways when the screen is narrow (the remote scrolls it by moving the focus)
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.isFillViewport = true
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        nameText = Ui.text(this, "", 17f, Ui.TEXT, true)
        nameText.setPadding(p * 2, 0, p * 2, 0)
        nameText.isSingleLine = true
        row.addView(nameText)
        // in Arabic «previous» is to the right and points right
        prevBtn = arrow(if (rtl) "›" else "‹", t("الكاميرا السابقة", "Previous camera")) { step(-1) }
        row.addView(prevBtn)
        nextBtn = arrow(if (rtl) "‹" else "›", t("الكاميرا التالية", "Next camera")) { step(1) }
        row.addView(nextBtn)
        qualityBtn = small(qualityLabel()) { toggleQuality() }
        row.addView(qualityBtn)
        soundBtn = small(soundLabel()) { toggleSound() }
        row.addView(soundBtn)
        row.addView(small(t("📷 صورة", "📷 Photo"), t("حفظ صورة", "Save a photo")) { snapshot() })
        recBtn = small(recLabel()) { toggleRecord() }
        row.addView(recBtn)
        pipBtn = small(t("⧉ نافذة صغيرة", "⧉ Mini window"), t("نافذة صغيرة فوق التطبيقات", "Small window over other apps")) { enterPip() }
        row.addView(pipBtn)
        ptzBtn = small(ptzLabel()) { if (ptzMode) leavePtz() else enterPtz() }
        row.addView(ptzBtn)
        talkBtn = small(talkLabel(), t("اضغط مطوّلًا للتحدّث عبر الكاميرا", "Hold to talk through the camera")) {
            toast(t("اضغط مطوّلًا على 🎤 وتحدّث", "Hold 🎤 and speak"))
        }
        // hold to talk: finger down / up, or OK held on the remote
        talkBtn.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.parent?.requestDisallowInterceptTouchEvent(true) // the bar must not scroll away from the finger
                    talkDown()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    talkUp()
                }
            }
            true
        }
        talkBtn.setOnKeyListener { _, code, e ->
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
                false
            } else {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) talkDown()
                else if (e.action == KeyEvent.ACTION_UP) talkUp()
                true
            }
        }
        row.addView(talkBtn)
        tourBtn = small(tourLabel()) { if (touring) stopTour() else startTour() }
        row.addView(tourBtn)
        scroll.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        b.addView(scroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        b.visibility = View.GONE
        return b
    }

    // phone: ▲ ▼ ◄ ► and zoom over the video; held = the camera turns, released = it stops
    private fun makePad(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.layoutDirection = View.LAYOUT_DIRECTION_LTR // left is left, in Arabic too
        val p = Ui.dp(this, 6)
        box.setPadding(p, p, p, p)
        box.background = Ui.round(0x80000000.toInt(), Ui.dp(this, 16).toFloat())

        fun row(vararg views: View): LinearLayout {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER
            for (v in views) {
                val lp = LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56))
                lp.setMargins(p / 2, p / 2, p / 2, p / 2)
                r.addView(v, lp)
            }
            return r
        }

        val up = padButton("▲", t("تحريك للأعلى", "Turn up"), 0f, SPEED, 0f)
        val down = padButton("▼", t("تحريك للأسفل", "Turn down"), 0f, -SPEED, 0f)
        val left = padButton("◄", t("تحريك لليسار", "Turn left"), -SPEED, 0f, 0f)
        val right = padButton("►", t("تحريك لليمين", "Turn right"), SPEED, 0f, 0f)
        val zoomIn = padButton("+", t("تقريب", "Zoom in"), 0f, 0f, SPEED)
        val zoomOut = padButton("−", t("تبعيد", "Zoom out"), 0f, 0f, -SPEED)
        val close = small("✕", t("إنهاء التحريك", "Stop moving the camera")) { leavePtz() }
        for (v in listOf(up, down, left, right, zoomIn, zoomOut, close)) v.setPadding(0, 0, 0, 0)
        box.addView(row(up))
        box.addView(row(left, close, right))
        box.addView(row(down))
        box.addView(row(zoomOut, zoomIn))
        return box
    }

    private fun padButton(text: String, desc: String, x: Float, y: Float, z: Float): TextView {
        val b = small(text, desc) {}
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    move(x, y, z)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    stopMove()
                }
            }
            true
        }
        return b
    }

    private fun small(label: String, desc: String? = null, onClick: () -> Unit): TextView {
        val b = Ui.button(this, label, false, onClick)
        b.textSize = 14f * Ui.scale(this)
        b.minHeight = Ui.dp(this, 48)
        b.minWidth = Ui.dp(this, 48)
        b.isSingleLine = true
        val p = Ui.dp(this, 8)
        b.setPadding(p * 2, p, p * 2, p)
        if (desc != null) b.contentDescription = desc
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.marginStart = Ui.dp(this, 4)
        b.layoutParams = lp
        return b
    }

    // an arrow drawn as given (not mirrored by the Arabic direction)
    private fun arrow(glyph: String, desc: String, onClick: () -> Unit): TextView {
        val b = small(glyph, desc, onClick)
        b.textDirection = View.TEXT_DIRECTION_LTR
        b.textSize = 20f * Ui.scale(this)
        return b
    }

    private fun pill(size: Float, color: Int = Ui.TEXT): TextView {
        val v = Ui.text(this, "", size, color, true)
        val p = Ui.dp(this, 8)
        v.setPadding(p * 2, p, p * 2, p)
        v.gravity = Gravity.CENTER
        v.background = Ui.round(0xB3000000.toInt(), Ui.dp(this, 10).toFloat())
        v.visibility = View.GONE
        return v
    }

    private fun overlay(v: View, gravity: Int, marginDp: Int = 16) {
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = gravity
        val m = Ui.dp(this, marginDp)
        lp.setMargins(m, m, m, m)
        root.addView(v, lp)
    }

    private fun qualityLabel() = if (hq) t("جودة عالية", "High quality") else t("جودة خفيفة", "Light quality")
    private fun soundLabel() = if (muted) t("🔇 الصوت", "🔇 Sound") else t("🔊 الصوت", "🔊 Sound")
    private fun recLabel() = if (recorder != null) t("⏹ إيقاف التسجيل", "⏹ Stop recording") else t("⏺ تسجيل", "⏺ Record")
    private fun ptzLabel() = if (ptzMode) t("🎮 إنهاء التحريك", "🎮 Stop moving") else t("🎮 تحريك", "🎮 Move")
    private fun talkLabel() = if (talking) t("🎤 جارٍ التحدّث…", "🎤 Talking…") else t("🎤 تحدّث", "🎤 Talk")
    private fun tourLabel() = if (touring) t("⟳ إيقاف الجولة", "⟳ Stop tour") else t("⟳ جولة", "⟳ Tour")

    // ---------------------------------------------------------------- showing the cameras

    private fun pages(): Int = (cams.size + 3) / 4

    private fun cur(): Camera? = if (single) cams.getOrNull(index) else null

    /** Builds the videos of the current mode (one camera alone, or a page of the grid) and starts them. */
    private fun render() {
        stopRecording()
        talkUp()
        leavePtz()
        releaseAll()
        if (cams.isEmpty()) return
        if (single) {
            index = index.coerceIn(0, cams.size - 1)
            val cam = cams[index]
            val tile = Tile(this, cam, main = hq, showName = false) { onVideoTap() }
            tile.view.isFocusable = false // no gold ring around the whole screen
            tile.muted = muted
            tile.setZoomable(!pip)
            tiles.add(tile)
            gridBar.visibility = View.GONE
            updateBar(cam)
            val show = barOn && !touring && !pip
            bar.visibility = if (show) View.VISIBLE else View.GONE
            ui.removeCallbacks(hideBar)
            if (show && tv) scheduleHide()
            maybeHint()
        } else {
            val n = pages()
            if (page !in 0 until n) page = 0
            val from = page * 4
            for (pos in from until minOf(cams.size, from + 4)) {
                val tile = Tile(this, cams[pos], main = false, showName = true) { openOne(pos) }
                tile.muted = true // never four sounds at once
                tiles.add(tile)
            }
            bar.visibility = View.GONE
            ui.removeCallbacks(hideBar)
            gridBar.visibility = if (pip) View.GONE else View.VISIBLE
            updateGridBar()
        }
        layoutTiles(force = true)
        tiles.forEach { it.start() }
        badge.visibility = if (touring && !pip) View.VISIBLE else View.GONE
        if (!single) {
            val k = if (focusPos in 0 until tiles.size) focusPos else 0
            focusPos = -1
            ui.post { if (!single) tiles.getOrNull(k)?.view?.requestFocus() }
        }
    }

    // 1 → whole screen; 2 → side by side (landscape) or one above the other (portrait); 3–4 → 2 × 2
    private fun layoutTiles(force: Boolean) {
        val views = tiles.map { it.view }
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val rows: List<List<View>> = when (views.size) {
            0 -> emptyList()
            1 -> listOf(views)
            2 -> if (land) listOf(views) else views.map { listOf(it) }
            else -> views.chunked(2)
        }
        val key = views.size.toString() + ":" + rows.joinToString(",") { it.size.toString() }
        if (!force && key == rowsKey) return // same arrangement: the videos stay where they are
        rowsKey = key
        for (v in views) (v.parent as? ViewGroup)?.removeView(v)
        grid.removeAllViews()
        val gap = if (views.size > 1) Ui.dp(this, 1) else 0
        for (r in rows) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (v in r) {
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                lp.setMargins(gap, gap, gap, gap)
                row.addView(v, lp)
            }
            // a third camera alone keeps the same size as the others
            if (r.size == 1 && views.size == 3) row.addView(View(this), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun updateBar(cam: Camera) {
        nameText.text = if (cams.size > 1) "${cam.name}  ${index + 1}/${cams.size}" else cam.name
        val many = cams.size > 1
        prevBtn.visibility = if (many) View.VISIBLE else View.GONE
        nextBtn.visibility = if (many) View.VISIBLE else View.GONE
        tourBtn.visibility = if (many) View.VISIBLE else View.GONE
        pipBtn.visibility = if (pipOk) View.VISIBLE else View.GONE
        ptzBtn.visibility = if (cam.ptz) View.VISIBLE else View.GONE
        talkBtn.visibility = if (cam.brand == Brand.PHONE) View.VISIBLE else View.GONE
        qualityBtn.text = qualityLabel()
        soundBtn.text = soundLabel()
        recBtn.text = recLabel()
        ptzBtn.text = ptzLabel()
        talkBtn.text = talkLabel()
        tourBtn.text = tourLabel()
    }

    private fun updateGridBar() {
        gridTitle.text = group ?: t("كل الكاميرات", "All cameras")
        val many = pages() > 1
        gridPrev.visibility = if (many) View.VISIBLE else View.GONE
        gridNext.visibility = if (many) View.VISIBLE else View.GONE
        pageText.visibility = if (many) View.VISIBLE else View.GONE
        pageText.text = "${page + 1} / ${pages()}"
        gridTour.visibility = if (cams.size > 1) View.VISIBLE else View.GONE
    }

    private fun releaseAll() {
        tiles.forEach { it.stop() }
        tiles.clear()
    }

    /** Camera number [pos] (0-based) alone. */
    private fun openOne(pos: Int) {
        if (pos !in cams.indices) return
        if (single && pos == index) return
        single = true
        index = pos
        render()
        showLabel()
    }

    private fun toGrid() {
        stopTour()
        single = false
        gridRoot = true
        page = index / 4
        focusPos = index % 4
        render()
    }

    private fun goBack() {
        if (single && gridRoot) toGrid() else finish()
    }

    private fun goPage(d: Int) {
        val n = pages()
        if (n < 2) return
        page = ((page + d) % n + n) % n
        focusPos = 0
        render()
    }

    /** Next (+1) / previous (−1) camera, alone. */
    private fun step(d: Int) {
        val n = cams.size
        if (n < 2) return
        index = ((index + d) % n + n) % n
        render()
        showLabel()
    }

    private fun onVideoTap() {
        if (single) showBar(bar.visibility != View.VISIBLE)
    }

    private fun showBar(on: Boolean, focus: Boolean = false) {
        if (!single) return
        barOn = on
        val show = on && !pip
        bar.visibility = if (show) View.VISIBLE else View.GONE
        ui.removeCallbacks(hideBar)
        if (show) {
            if (tv) scheduleHide()
            if (focus) (if (prevBtn.visibility == View.VISIBLE) prevBtn else qualityBtn).requestFocus()
        }
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideBar)
        if (tv) ui.postDelayed(hideBar, BAR_MS)
    }

    // the camera's name for 3 seconds, when the bar is hidden
    private fun showLabel() {
        val cam = cur() ?: return
        if (pip || bar.visibility == View.VISIBLE) return
        label.text = if (cams.size > 1) "${cam.name} · ${index + 1}/${cams.size}" else cam.name
        label.visibility = View.VISIBLE
        ui.removeCallbacks(hideLabel)
        ui.postDelayed(hideLabel, 3000)
    }

    // TV, the first time only: how the remote works here
    private fun maybeHint() {
        if (!tv || pip || Prefs.bool(this, "hintSeen", false)) return
        Prefs.put(this, "hintSeen", true)
        hint.visibility = View.VISIBLE
        ui.removeCallbacks(hideHint)
        ui.postDelayed(hideHint, 6000)
    }

    // ---------------------------------------------------------------- buttons

    private fun toggleQuality() {
        hq = !hq
        qualityBtn.text = qualityLabel()
        stopRecording()
        tiles.forEach {
            it.main = hq
            it.start()
        }
    }

    private fun toggleSound() {
        muted = !muted
        soundBtn.text = soundLabel()
        if (!talking) tiles.forEach { it.muted = muted }
    }

    private fun startTour() {
        if (cams.size < 2) return
        touring = true
        barOn = false
        if (!single) {
            single = true
            index = page * 4
            render()
        } else {
            bar.visibility = View.GONE
            ui.removeCallbacks(hideBar)
            badge.visibility = if (pip) View.GONE else View.VISIBLE
        }
        tourBtn.text = tourLabel()
        showLabel()
        scheduleTour()
    }

    private fun stopTour() {
        if (!touring) return
        touring = false
        ui.removeCallbacks(tourRun)
        if (::badge.isInitialized) badge.visibility = View.GONE
        if (::tourBtn.isInitialized) tourBtn.text = tourLabel()
    }

    private fun scheduleTour() {
        ui.removeCallbacks(tourRun)
        ui.postDelayed(tourRun, maxOf(5, Prefs.tourSec(this)) * 1000L)
    }

    private fun noPicture() = t("لا توجد صورة بعد. انتظر حتى يظهر البث", "No picture yet. Wait for the video")

    private fun snapshot() {
        if (Media.needsPermission(this)) {
            ask(Manifest.permission.WRITE_EXTERNAL_STORAGE, REQ_SNAP)
            return
        }
        val tile = tiles.firstOrNull() ?: return
        val bmp = tile.snapshot()
        if (bmp == null) {
            toast(noPicture())
            return
        }
        val name = Media.stamp(tile.cam.name)
        val app = applicationContext
        val ok = t("حُفظت الصورة في المعرض", "Photo saved to the gallery")
        val bad = t("تعذّر حفظ الصورة", "Could not save the photo")
        Thread {
            val saved = Media.saveImage(app, bmp, name)
            ui.post { toast(if (saved) ok else bad) }
        }.start()
    }

    private fun toggleRecord() {
        if (recorder != null) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (recorder != null) return
        if (Media.needsPermission(this)) {
            ask(Manifest.permission.WRITE_EXTERNAL_STORAGE, REQ_REC)
            return
        }
        val tile = tiles.firstOrNull()
        val tex = tile?.textureView
        if (tile == null || tex == null || !tile.hasPicture) {
            toast(noPicture())
            return
        }
        val size = tile.frameSize(Recorder.MAX_SIDE)
        val r = Recorder(applicationContext, tex, size.first, size.second, Media.stamp(tile.cam.name)) { rec, ok -> onRecorded(rec, ok) }
        if (!r.start()) {
            toast(t("تعذّر بدء التسجيل", "Could not start recording"))
            return
        }
        recorder = r
        recBtn.text = recLabel()
        recText.text = "● 0:00"
        recText.visibility = if (pip) View.GONE else View.VISIBLE
        ui.removeCallbacks(recTick)
        ui.postDelayed(recTick, 500)
        toast(t("بدأ التسجيل (دقيقة واحدة على الأكثر)", "Recording (one minute at most)"))
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        r.stop()
        recordingOff()
    }

    // main thread, once per recording: saved or not (also after 60 seconds or an error)
    private fun onRecorded(r: Recorder, ok: Boolean) {
        if (recorder === r) {
            recorder = null
            recordingOff()
        }
        toast(if (ok) t("حُفظ الفيديو في المعرض", "Video saved to the gallery") else t("تعذّر حفظ الفيديو", "Could not save the video"))
    }

    private fun recordingOff() {
        ui.removeCallbacks(recTick)
        if (::recText.isInitialized) recText.visibility = View.GONE
        if (::recBtn.isInitialized) recBtn.text = recLabel()
    }

    // ---------------------------------------------------------------- PTZ (turning the camera)

    private fun enterPtz() {
        val cam = cur() ?: return
        if (!cam.ptz || ptzMode) return
        ptzMode = true
        ptzCam = cam
        ptzBtn.text = ptzLabel()
        if (tv) {
            showBar(false)
            ptzHint.visibility = View.VISIBLE
        } else {
            pad?.visibility = View.VISIBLE
        }
    }

    private fun leavePtz() {
        if (!ptzMode) return
        heldKey = 0
        stopMove()
        ptzMode = false
        ptzCam = null
        pad?.visibility = View.GONE
        if (::ptzHint.isInitialized) ptzHint.visibility = View.GONE
        if (::ptzBtn.isInitialized) ptzBtn.text = ptzLabel()
    }

    private fun move(x: Float, y: Float, z: Float) {
        val cam = ptzCam ?: return
        bg {
            val ok = try {
                Onvif.ptzMove(cam, x, y, z)
            } catch (_: Exception) {
                false
            }
            if (!ok) ui.post { ptzFailed() }
        }
    }

    private fun stopMove() {
        val cam = ptzCam ?: return
        bg {
            try {
                Onvif.ptzStop(cam)
            } catch (_: Exception) {
            }
        }
    }

    private fun ptzFailed() {
        val now = System.currentTimeMillis()
        if (now - lastPtzError < 4000) return
        lastPtzError = now
        toast(t("تعذّر تحريك الكاميرا. تأكد من إعدادات ONVIF في الكاميرا", "Could not turn the camera. Check its ONVIF settings"))
    }

    // TV in move mode: arrows turn the camera while held, CH+ / CH− zoom
    private fun ptzKey(e: KeyEvent): Boolean {
        val v = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> floatArrayOf(-SPEED, 0f, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> floatArrayOf(SPEED, 0f, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> floatArrayOf(0f, SPEED, 0f)
            KeyEvent.KEYCODE_DPAD_DOWN -> floatArrayOf(0f, -SPEED, 0f)
            KeyEvent.KEYCODE_CHANNEL_UP -> floatArrayOf(0f, 0f, SPEED)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> floatArrayOf(0f, 0f, -SPEED)
            else -> return false
        }
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0) {
                heldKey = e.keyCode
                move(v[0], v[1], v[2])
            }
        } else if (e.action == KeyEvent.ACTION_UP && heldKey == e.keyCode) {
            heldKey = 0
            stopMove()
        }
        return true
    }

    // ---------------------------------------------------------------- talk (phone cameras)

    private fun talkDown() {
        if (talking) return
        val cam = cur() ?: return
        if (cam.brand != Brand.PHONE) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ask(Manifest.permission.RECORD_AUDIO, REQ_MIC)
            return
        }
        talking = true
        talkBtn.text = talkLabel()
        tiles.forEach { it.muted = true } // do not hear our own voice back from the camera
        ui.removeCallbacks(hideBar)
        val app = applicationContext
        val reported = AtomicBoolean(false)
        val failed = t("تعذّر التحدّث عبر هذه الكاميرا", "Could not talk through this camera")
        bg {
            val ok = try {
                Talk.start(app, cam) { msg ->
                    reported.set(true)
                    ui.post { toast(msg) }
                }
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                ui.post {
                    if (!reported.get()) toast(failed)
                    talkUp()
                }
            }
        }
    }

    private fun talkUp() {
        if (!talking) return
        talking = false
        if (::talkBtn.isInitialized) {
            talkBtn.text = talkLabel()
            talkBtn.isPressed = false
        }
        tiles.forEach { it.muted = muted }
        bg {
            try {
                Talk.stop()
            } catch (_: Exception) {
            }
        }
        if (single && tv && ::bar.isInitialized && bar.visibility == View.VISIBLE) scheduleHide()
    }

    private fun bg(task: () -> Unit) {
        try {
            work.execute { task() }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- picture-in-picture (phone)

    private fun enterPip() {
        if (!pipOk || !single || pip || Build.VERSION.SDK_INT < 26) return
        try {
            val size = tiles.firstOrNull()?.frameSize(0)
            var ratio = if (size != null && size.first > 0 && size.second > 0) size.first.toFloat() / size.second else 16f / 9f
            ratio = ratio.coerceIn(0.42f, 2.38f) // Android refuses shapes outside 1:2.39 … 2.39:1
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000)).build()
            enterPictureInPictureMode(params)
        } catch (_: Exception) {
            toast(t("تعذّر فتح النافذة الصغيرة", "Could not open the mini window"))
        }
    }

    // Home pressed while watching one camera: keep watching in a small window
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::grid.isInitialized && single && pipOk && !asking && !pip && !isFinishing && !Lock.needed(this)) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
        if (!::grid.isInitialized) return
        if (pip) {
            leavePtz()
            talkUp()
            for (v in listOf(bar, gridBar, badge, hint, label, ptzHint, recText)) v.visibility = View.GONE
            ui.removeCallbacks(hideBar)
            tiles.forEach { it.setZoomable(false) }
        } else {
            if (stopped) { // the small window was closed
                finish()
                return
            }
            if (single) {
                bar.visibility = if (barOn && !touring) View.VISIBLE else View.GONE
                tiles.forEach { it.setZoomable(true) }
            } else {
                gridBar.visibility = View.VISIBLE
            }
            badge.visibility = if (touring) View.VISIBLE else View.GONE
            recText.visibility = if (recorder != null) View.VISIBLE else View.GONE
            hideSystemBars()
        }
    }

    // ---------------------------------------------------------------- permissions

    private fun ask(permission: String, code: Int) {
        asking = true
        try {
            requestPermissions(arrayOf(permission), code)
        } catch (_: Exception) {
            asking = false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        asking = false
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_SNAP -> if (granted) snapshot() else toast(t("لا يمكن حفظ الصورة دون إذن التخزين", "The photo cannot be saved without the storage permission"))
            REQ_REC -> if (granted) startRecording() else toast(t("لا يمكن حفظ الفيديو دون إذن التخزين", "The video cannot be saved without the storage permission"))
            REQ_MIC -> toast(
                if (granted) t("اضغط مطوّلًا على 🎤 وتحدّث", "Hold 🎤 and speak")
                else t("التحدّث يحتاج إذن الميكروفون", "Talking needs the microphone permission"),
            )
        }
    }

    // ---------------------------------------------------------------- remote control, keyboard and touch

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (!::grid.isInitialized || pip || systemKey(code)) return super.dispatchKeyEvent(e)
        val down = e.action == KeyEvent.ACTION_DOWN
        val up = e.action == KeyEvent.ACTION_UP
        // the rest of a key press that was already used (its repeats and its release)
        if (code == swallowKey && (up || e.repeatCount > 0)) {
            if (up) swallowKey = -1
            return true
        }
        // the tour: any key stops it, and does nothing else
        if (touring && down) {
            stopTour()
            return used(code)
        }
        val barShown = bar.visibility == View.VISIBLE
        if (single && barShown) scheduleHide()

        if (down && e.repeatCount == 0) {
            val d = digit(code)
            if (d >= 0) {
                if (d == 0) {
                    if (single) toGrid()
                } else if (d > cams.size) {
                    toast(t("لا توجد كاميرا بالرقم $d", "There is no camera number $d"))
                } else {
                    openOne(d - 1)
                }
                return used(code)
            }
        }

        if (single && !barShown) {
            if (ptzMode && ptzKey(e)) return true
            if (down) {
                when (code) {
                    // ◄ ►: in Arabic the next camera is to the left
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (e.repeatCount == 0) step(if (rtl) 1 else -1)
                        return used(code)
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (e.repeatCount == 0) step(if (rtl) -1 else 1)
                        return used(code)
                    }
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        if (e.repeatCount == 0) step(1)
                        return used(code)
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        if (e.repeatCount == 0) step(-1)
                        return used(code)
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        showBar(true, focus = true)
                        return used(code)
                    }
                }
            }
        } else if (single && down && code == KeyEvent.KEYCODE_MENU) {
            showBar(false)
            return used(code)
        } else if (!single && down && e.repeatCount == 0) {
            when (code) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    goPage(1)
                    return used(code)
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    goPage(-1)
                    return used(code)
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun used(code: Int): Boolean {
        swallowKey = code
        return true
    }

    private fun digit(code: Int): Int = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
        else -> -1
    }

    // keys left to Android: volume, power, home…
    private fun systemKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_SETTINGS -> true
        else -> false
    }

    // the tour: a touch anywhere stops it (and does nothing else)
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (touring && e.actionMasked == MotionEvent.ACTION_DOWN) {
            stopTour()
            swallowTouch = true
            return true
        }
        if (swallowTouch) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) swallowTouch = false
            return true
        }
        return super.dispatchTouchEvent(e)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            !::grid.isInitialized -> super.onBackPressed()
            touring -> stopTour()
            ptzMode -> leavePtz()
            single && tv && bar.visibility == View.VISIBLE -> showBar(false)
            single && gridRoot -> toGrid()
            else -> super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun toast(s: String) {
        try {
            Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }
}
