package com.dromaia.ipcam

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Recordings of one Hikvision camera (a channel of the recorder): the video, the day (‹ yesterday · date · tomorrow ›,
 * «اليوم»), a 24-hour line of that day (recorded parts in gold, events as small coloured marks, where it plays now),
 * the time playing (the recorder's own clock), and buttons: −5 min, −30 s, ⏯, +30 s, +5 min, «مباشر», photo,
 * another camera.
 * Phone upright: the video above, the rest below it. Phone on its side and TV: the video on the whole screen, the rest
 * over it (phone: a tap on the video hides / shows it; TV: it hides after a few seconds of playing, any key shows it).
 * TV remote on the day line: ◄ ► move the cursor (held = faster), ▲ ▼ next / previous day, OK plays from the cursor,
 * Back returns to the buttons. Media keys: ⏯, ⏪ / ⏩ = 30 seconds.
 * The recorder's password is asked about before anything is sent: after a refusal nothing is sent for a while
 * (Hik.refused), so the recorder does not lock this device out.
 * Extras: [ID] camera id, [AT] start time (recorder clock, wall-clock ms; none = 10 minutes ago), [FROM_LIVE].
 */
class PlaybackActivity : Base() {
    companion object {
        const val ID = "id"              // String: the camera (one channel of the recorder)
        const val AT = "at"              // Long: where to start, recorder clock (wall-clock ms); -1 = 10 minutes ago
        const val FROM_LIVE = "fromLive" // Boolean: opened from the live view («مباشر» simply goes back to it)
        private const val REQ_SNAP = 51
        private const val PANEL_MS = 8000L // TV: the buttons hide after 8 s of playing without keys
        private const val ERR_REACH = 1
        private const val ERR_AUTH = 2

        fun intent(c: Context, id: String, at: Long = -1L, fromLive: Boolean = false): Intent =
            Intent(c, PlaybackActivity::class.java).putExtra(ID, id).putExtra(AT, at).putExtra(FROM_LIVE, fromLive)
    }

    // one day of the recorder: the recorded parts, the events, or why it could not be read ([at] = when it was read)
    private class DayData(val segs: List<Hik.Segment>, val events: List<Hik.Event>, val error: Int, val at: Long)

    private var cam: Camera? = null
    private var tile: Tile? = null
    private val ui = Handler(Looper.getMainLooper())
    private val work: ExecutorService = Executors.newSingleThreadExecutor() // questions to the recorder, one at a time
    @Volatile private var offset = RecTime.localNow() - System.currentTimeMillis() // recorder clock − this device's
    @Volatile private var loadGen = 0
    private val cache = HashMap<Long, DayData>()
    private var day = 0L          // 00:00 of the day shown
    private var from = -1L        // recorder time where the stream now playing started
    private var time = -1L        // recorder time on screen (playing, or where it stopped)
    private var active = false    // the stream plays or is connecting
    private var paused = false    // stopped with ⏯ (stays stopped when the screen comes back)
    private var resume = false    // play (again) when the screen comes to the front
    private var front = false     // the screen is in front
    private var readyAt = 0L      // when the picture started (elapsedRealtime), if the player cannot tell its position
    private var follow = true     // the day shown follows the time playing (false while he looks at another day)
    private var switched = false  // another camera was chosen here
    private var overlay = false   // the video on the whole screen, the rest over it
    private var panelOn = true
    private var swallowKey = -1   // the rest of a key press already used
    private var lastFocus: View? = null
    private var lineSaid = ""     // what TalkBack reads for the day line (changes once a minute)
    private var legendOn = false

    private lateinit var root: FrameLayout
    private lateinit var videoBox: RatioFrame
    private lateinit var top: LinearLayout
    private lateinit var nameText: TextView
    private lateinit var timeText: TextView
    private lateinit var panel: LinearLayout
    private lateinit var dayBar: DayBar
    private lateinit var line: DayTimeline
    private lateinit var legend: TextView
    private lateinit var hintText: TextView
    private lateinit var msgRow: LinearLayout
    private lateinit var msgText: TextView
    private lateinit var retryBtn: TextView
    private lateinit var back5: TextView
    private lateinit var back30: TextView
    private lateinit var playBtn: TextView
    private lateinit var fwd30: TextView
    private lateinit var fwd5: TextView
    private lateinit var liveBtn: TextView
    private lateinit var photoBtn: TextView
    private lateinit var camBtn: TextView

    private val hidePanel = Runnable {
        if (overlay && tv && panelOn && active && line.cursor < 0 && msgRow.visibility != View.VISIBLE) showPanel(false)
    }

    // twice a second: the time playing (start + the player's position), the day line, "now"
    private val tick = object : Runnable {
        override fun run() {
            val tl = tile
            if (front && active && tl != null && tl.isPlaying) {
                val now = SystemClock.elapsedRealtime()
                if (readyAt == 0L) readyAt = now
                var pos = tl.position
                val wall = now - readyAt
                if (pos < 0L || pos > wall + 30_000L) pos = wall // the player cannot tell: the time since it started
                time = from + pos
                line.play = time
                if (follow && line.cursor < 0 && RecTime.dayOf(time) != day) {
                    day = RecTime.dayOf(time)
                    dayChanged()
                }
                showTime()
            }
            line.now = nowMs()
            line.invalidate()
            describeLine()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val all = Store.all(this)
        val id = intent.getStringExtra(ID)
        val c = if (id != null) all.firstOrNull { it.id == id } else all.firstOrNull { it.brand == Brand.HIKVISION }
        if (c == null) {
            toast(t("لم أجد هذه الكاميرا", "This camera was not found"))
            finish()
            return
        }
        if (c.brand != Brand.HIKVISION) {
            notHikvision(c)
            return
        }
        cam = c
        var at = intent.getLongExtra(AT, -1L)
        if (saved != null) {
            at = saved.getLong("time", at)
            paused = saved.getBoolean("paused", false)
        }
        build(c)
        day = RecTime.dayOf(if (at > 0) at else nowMs())
        dayChanged()
        begin(at)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (time > 0) out.putLong("time", time)
        out.putBoolean("paused", paused)
    }

    // the video plays only while the screen is in front
    override fun onStart() {
        super.onStart()
        front = true
        hideSystemBars()
        if (!::panel.isInitialized) return
        ui.removeCallbacks(tick)
        ui.post(tick)
        if (resume) {
            resume = false
            if (time > 0) playAt(time, 0)
        }
    }

    override fun onStop() {
        front = false
        if (::panel.isInitialized) {
            ui.removeCallbacks(tick)
            ui.removeCallbacks(hidePanel)
            if (active) {
                tile?.pause() // the last picture stays; it goes on from there when the screen comes back
                active = false
                resume = true
                updatePlay()
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        tile?.stop()
        try {
            work.shutdownNow()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // turning the phone changes the look (video above / video on the whole screen) without reconnecting
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::panel.isInitialized) {
            val want = tv || newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (want != overlay) arrange()
        }
        hideSystemBars()
    }

    // ---------------------------------------------------------------- the clock and the day

    private fun nowMs(): Long = System.currentTimeMillis() + offset

    private fun today(): Long = RecTime.dayOf(nowMs())

    // the recorder's clock first (its "now" and its "today"); a time asked for plays at once
    private fun begin(at: Long) {
        val c = cam ?: return
        if (at > 0) {
            if (paused) showStopped(at) else playAt(at, 0)
        }
        exec {
            val now = try {
                Hik.now(c)
            } catch (_: Exception) {
                RecTime.localNow()
            }
            offset = now - System.currentTimeMillis()
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (at <= 0) {
                    val start = nowMs() - 10 * RecTime.MIN
                    if (paused) showStopped(start) else playAt(start, 0)
                }
                showDayBar() // "today" by the recorder's clock
            }
        }
    }

    // stopped at [at], nothing playing (e.g. it was paused before the screen was made again)
    private fun showStopped(at: Long) {
        time = at
        from = at
        active = false
        line.play = at
        val d = RecTime.dayOf(at)
        if (d != day) {
            day = d
            dayChanged()
        }
        updatePlay()
        showTime()
    }

    private fun showDayBar() {
        dayBar.set(day, today())
        line.day = day
        line.now = nowMs()
        line.invalidate()
        describeLine()
    }

    private fun dayChanged() {
        showDayBar()
        load(day)
    }

    // ‹ › «اليوم» or ▲ ▼ on the day line: another day is shown; the video goes on until he picks a time
    private fun pickDay(d: Long, keepCursor: Boolean = false) {
        val nd = d.coerceIn(0L, today())
        if (nd == day) return
        val cur = line.cursor
        day = nd
        follow = time > 0 && RecTime.dayOf(time) == nd
        dayChanged()
        if (keepCursor && cur >= 0) line.cursor = line.clamp(nd + (cur - RecTime.dayOf(cur)))
    }

    // the recorded parts and events of day [d]: from the memory (a past day; today for one minute), else the recorder
    private fun load(d: Long, force: Boolean = false) {
        val c = cam ?: return
        val have = cache[d]
        if (!force && have != null && (d < today() || SystemClock.elapsedRealtime() - have.at < 60_000L)) {
            showData(have)
            return
        }
        val g = ++loadGen
        showData(have, loading = true)
        // a short wait: ▲ ▼ pressed several times asks only for the last day
        ui.postDelayed({ if (g == loadGen) exec { fetchDay(c, d, g) } }, 250)
    }

    // background thread
    private fun fetchDay(c: Camera, d: Long, g: Int) {
        if (g != loadGen) return
        val res = if (Hik.refused(c)) {
            DayData(emptyList(), emptyList(), ERR_AUTH, 0L)
        } else {
            val segs = try {
                Hik.recordings(c, d, d + RecTime.DAY)
            } catch (_: Exception) {
                null
            }
            if (segs == null) {
                DayData(emptyList(), emptyList(), if (Hik.refused(c)) ERR_AUTH else ERR_REACH, 0L)
            } else {
                val evs = try {
                    Hik.events(listOf(c), d, d + RecTime.DAY)
                } catch (_: Exception) {
                    null
                }
                DayData(segs.sortedBy { it.start }, evs?.filter { it.channel == c.channel } ?: emptyList(), 0, SystemClock.elapsedRealtime())
            }
        }
        ui.post {
            if (g != loadGen || isFinishing || isDestroyed || cam?.id != c.id) return@post
            if (res.error == 0) cache[d] = res
            if (d == day) showData(res)
        }
    }

    private fun showData(d: DayData?, loading: Boolean = false) {
        line.segs = d?.segs ?: emptyList()
        line.events = d?.events ?: emptyList()
        line.invalidate()
        showLegend(d?.events ?: emptyList())
        when {
            loading -> showMessage(t("جارٍ تحميل تسجيلات هذا اليوم…", "Loading this day's recordings…"), false)
            d == null -> showMessage(null, false)
            d.error == ERR_AUTH -> showMessage(authText(), false)
            d.error == ERR_REACH -> showMessage(
                t(
                    "تعذّر الوصول إلى المسجّل. تأكد أنه يعمل، وإن كنت خارج البيت فتأكد من عنوانه من الخارج",
                    "Could not reach the recorder. Make sure it is on; away from home, check its outside address",
                ),
                true,
            )
            d.segs.isEmpty() -> showMessage(t("لا توجد تسجيلات في هذا اليوم", "No recordings on this day"), false)
            else -> showMessage(null, false)
        }
        schedulePanelHide()
    }

    private fun authText() = t(
        "رفض المسجّل اسم المستخدم أو كلمة المرور. عدّل الكاميرا وصحّح كلمة المرور",
        "The recorder refused the username or password. Edit the camera and fix the password",
    )

    private fun showMessage(text: String?, retry: Boolean) {
        if (text == null) {
            msgRow.visibility = View.GONE
            return
        }
        msgText.text = text
        retryBtn.visibility = if (retry) View.VISIBLE else View.GONE
        msgRow.visibility = View.VISIBLE
    }

    // ● شخص ● سيارة …: the colours of the marks on the day line (only the kinds of this day)
    private fun showLegend(events: List<Hik.Event>) {
        val kinds = EventKinds.ORDER.filter { k -> events.any { it.kind == k } }
        val sb = SpannableStringBuilder()
        for (k in kinds) {
            if (sb.isNotEmpty()) sb.append("    ")
            val s = sb.length
            sb.append("●")
            sb.setSpan(ForegroundColorSpan(EventKinds.color(k)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ").append(EventKinds.label(this, k))
        }
        legend.text = sb
        legendOn = kinds.isNotEmpty()
        legend.visibility = if (legendOn && !(tv && line.isFocused)) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- playing

    /** Plays from [at] (recorder clock). In a gap without recording: [snap] 1 = the next recording, −1 = the one before, 0 = as asked. */
    private fun playAt(at: Long, snap: Int) {
        val c = cam ?: return
        var ms = at.coerceIn(0L, maxOf(0L, nowMs() - 5_000L))
        if (snap != 0) {
            val segs = cache[RecTime.dayOf(ms)]?.segs
            if (segs != null && segs.isNotEmpty() && segs.none { ms >= it.start && ms < it.end }) {
                val near = if (snap > 0) segs.filter { it.start > ms }.minByOrNull { it.start }
                else segs.filter { it.end <= ms }.maxByOrNull { it.end }
                if (near != null) {
                    ms = if (snap > 0) near.start else maxOf(near.start, near.end - 30_000L)
                    val hms = RecTime.hms(ms)
                    toast(t("لا يوجد تسجيل في ذلك الوقت، التشغيل من $hms", "No recording at that time, playing from $hms"))
                }
            }
        }
        line.cursor = -1L
        from = ms
        time = ms
        line.play = ms
        paused = false
        follow = true
        val d = RecTime.dayOf(ms)
        if (d != day) {
            day = d
            dayChanged()
        }
        if (!front) {
            // the screen is not in front yet (or any more): it starts when it comes back
            resume = true
            active = false
        } else if (Hik.refused(c)) {
            // the recorder refused the password a moment ago: nothing is sent to it meanwhile
            active = false
            tile?.stop()
            showMessage(authText(), false)
        } else {
            active = true
            tile?.start() // the stream's address is made by urlFor, from [time]
        }
        line.invalidate()
        showTime()
        updatePlay()
        schedulePanelHide()
    }

    private fun togglePlay() {
        val tl = tile ?: return
        if (active && !tl.authFailed) {
            tl.pause() // the picture stays on screen (a photo can still be taken)
            active = false
            paused = true
            updatePlay()
            showTime()
            showPanel(true)
        } else {
            val cur = line.cursor
            when {
                cur >= 0 -> playAt(cur, 1)
                time > 0 -> playAt(time, 0)
                else -> playAt(nowMs() - 10 * RecTime.MIN, 0)
            }
        }
    }

    // −5 min … +5 min, from the cursor if one is being chosen, else from the time playing
    private fun jump(delta: Long) {
        val cur = line.cursor
        val base = when {
            cur >= 0 -> cur
            time > 0 -> time
            else -> nowMs() - 10 * RecTime.MIN
        }
        playAt(base + delta, if (delta > 0) 1 else -1)
    }

    // «مباشر»: back to the live view of this camera
    private fun goLive() {
        val c = cam ?: return
        if (intent.getBooleanExtra(FROM_LIVE, false) && !switched) {
            finish()
            return
        }
        try {
            startActivity(Watch.one(this, c.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (_: Exception) {
        }
        finish()
    }

    private fun showTime() {
        if (time <= 0) {
            timeText.text = "--:--:--"
            timeText.contentDescription = t("وقت التسجيل غير معروف بعد", "Recording time not known yet")
            return
        }
        val tl = tile
        val icon = when {
            active && tl != null && tl.isPlaying -> "▶"
            active -> "…"
            else -> "⏸"
        }
        val date = if (RecTime.dayOf(time) != day) RecTime.short(time) + "  " else ""
        val hms = RecTime.hms(time)
        timeText.text = "$icon $date$hms"
        timeText.contentDescription = t("وقت التسجيل ", "Recording time ") + date + hms
    }

    private fun updatePlay() {
        playBtn.text = if (active) t("⏸ إيقاف", "⏸ Pause") else t("▶ تشغيل", "▶ Play")
        playBtn.contentDescription = if (active) t("إيقاف مؤقت", "Pause") else t("تشغيل", "Play")
    }

    // what TalkBack reads for the day line
    private fun describeLine() {
        val pos = if (line.cursor >= 0) line.cursor else line.play
        val at = if (pos >= day && pos < day + RecTime.DAY) t("، المؤشر عند ", ", cursor at ") + RecTime.hm(pos) else ""
        val d = t("شريط اليوم، ", "Day bar, ") + RecTime.date(this, day) + at
        if (d != lineSaid) {
            lineSaid = d
            line.contentDescription = d
        }
    }

    private fun onVideoTap() {
        if (tile?.retryOnce() == true) return // after a wrong password: one more try
        if (overlay) showPanel(!panelOn)
    }

    // ---------------------------------------------------------------- photo, another camera

    private fun snapshot() {
        if (Media.needsPermission(this)) {
            try {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_SNAP)
            } catch (_: Exception) {
            }
            return
        }
        val tl = tile ?: return
        val bmp = tl.snapshot()
        if (bmp == null) {
            toast(t("لا توجد صورة بعد. انتظر حتى يظهر الفيديو", "No picture yet. Wait for the video"))
            return
        }
        val name = Media.stamp(tl.cam.name)
        val app = applicationContext
        val ok = t("حُفظت الصورة في المعرض", "Photo saved to the gallery")
        val bad = t("تعذّر حفظ الصورة", "Could not save the photo")
        Thread {
            val saved = try {
                Media.saveImage(app, bmp, name)
            } catch (_: Exception) {
                false
            }
            ui.post { toast(if (saved) ok else bad) }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_SNAP) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) snapshot()
        else toast(t("لا يمكن حفظ الصورة دون إذن التخزين", "The photo cannot be saved without the storage permission"))
    }

    private fun hikCams(): List<Camera> = Store.all(this).filter { it.brand == Brand.HIKVISION }

    // 📹: another Hikvision camera, at the same time
    private fun chooseCamera() {
        val list = hikCams()
        if (list.size < 2) return
        val names = list.map { if (it.id == cam?.id) "✓ " + it.name else it.name }.toTypedArray()
        try {
            AlertDialog.Builder(this)
                .setTitle(t("اختر الكاميرا", "Choose a camera"))
                .setItems(names) { _, which -> list.getOrNull(which)?.let { switchTo(it) } }
                .show()
        } catch (_: Exception) {
        }
    }

    private fun switchTo(c: Camera) {
        if (c.id == cam?.id) return
        tile?.stop()
        cam = c
        switched = true
        cache.clear()
        loadGen++
        nameText.text = c.name
        makeTile(c)
        load(day, force = true)
        if (!paused && time > 0) {
            playAt(time, 0)
        } else {
            active = false
            updatePlay()
            showTime()
        }
    }

    // ---------------------------------------------------------------- building the screen

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun makeTile(c: Camera) {
        val tl = Tile(this, c, main = true, showName = false, urlFor = { remote ->
            // every connection (also after a break or a lost connection) starts at the time on screen
            from = time
            readyAt = 0L
            c.playback(Hik.stamp(from), remote)
        }) { onVideoTap() }
        tl.view.isFocusable = false // the remote's focus stays on the buttons and the day line
        tl.setZoomable(!tv)
        tl.onPlaying = {
            readyAt = SystemClock.elapsedRealtime()
            showTime()
            schedulePanelHide()
        }
        tl.onEnded = {
            active = false
            updatePlay()
            showTime()
            toast(t("انتهى التسجيل", "End of the recording"))
            showPanel(true)
        }
        tl.onError = { e ->
            if (Tile.notFound(e)) {
                // the recorder answered but has nothing at that time: no automatic retries
                active = false
                updatePlay()
                showTime()
                t("لا يوجد تسجيل في هذا الوقت. اختر وقتًا آخر على الشريط", "No recording at this time. Choose another time on the bar")
            } else {
                null
            }
        }
        videoBox.removeAllViews()
        videoBox.addView(tl.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        tile = tl
    }

    private fun build(c: Camera) {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        videoBox = RatioFrame(this)
        videoBox.setBackgroundColor(Color.BLACK)
        makeTile(c)

        // top: back (phone), the camera's name, the time playing
        top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        if (!tv) top.addView(ctl(t("→ رجوع", "← Back"), null) { finish() })
        nameText = Ui.text(this, c.name, 17f, Ui.TEXT, true)
        nameText.isSingleLine = true
        nameText.ellipsize = TextUtils.TruncateAt.END
        nameText.setPadding(dp(10), 0, dp(10), 0)
        Ui.heading(nameText)
        top.addView(nameText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        timeText = Ui.text(this, "--:--:--", 20f, Ui.GOLD, true)
        timeText.textDirection = View.TEXT_DIRECTION_LTR
        timeText.setPadding(dp(8), 0, dp(8), 0)
        top.addView(timeText)

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL

        dayBar = DayBar(this) { d -> pickDay(d) }

        line = DayTimeline(this)
        line.onPick = { at -> playAt(at, 1) }
        line.onDay = { step -> pickDay(day + step * RecTime.DAY, keepCursor = true) }
        line.onOk = { togglePlay() }
        line.setOnFocusChangeListener { _, has ->
            if (tv) {
                hintText.visibility = if (has) View.VISIBLE else View.GONE
                legend.visibility = if (legendOn && !has) View.VISIBLE else View.GONE
            }
            if (!has && line.cursor >= 0) line.cursor = -1L // leaving the line drops the cursor not played
        }

        legend = Ui.text(this, "", 13f, Ui.MUTED)
        legend.visibility = View.GONE
        hintText = Ui.text(
            this,
            t(
                "◄ ► الوقت (اضغط مطوّلًا للأسرع) · ▲ ▼ اليوم التالي / السابق · OK تشغيل من المؤشر · رجوع للأزرار",
                "◄ ► time (hold for faster) · ▲ ▼ next / previous day · OK play from the cursor · Back for the buttons",
            ),
            13f, Ui.MUTED,
        )
        hintText.visibility = View.GONE

        msgRow = LinearLayout(this)
        msgRow.orientation = LinearLayout.HORIZONTAL
        msgRow.gravity = Gravity.CENTER_VERTICAL
        msgText = Ui.text(this, "", 15f, Ui.TEXT)
        msgText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        msgRow.addView(msgText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        retryBtn = ctl(t("↻ إعادة المحاولة", "↻ Try again"), null) { load(day, force = true) }
        msgRow.addView(retryBtn)
        msgRow.visibility = View.GONE

        back5 = jumpBtn("−5 " + t("دقائق", "min"), t("رجوع 5 دقائق", "Back 5 minutes"), -5 * RecTime.MIN)
        back30 = jumpBtn("−30 " + t("ثانية", "s"), t("رجوع 30 ثانية", "Back 30 seconds"), -30_000L)
        playBtn = Ui.button(this, t("⏸ إيقاف", "⏸ Pause"), true) { togglePlay() }
        sizeCtl(playBtn)
        fwd30 = jumpBtn("+30 " + t("ثانية", "s"), t("تقديم 30 ثانية", "Forward 30 seconds"), 30_000L)
        fwd5 = jumpBtn("+5 " + t("دقائق", "min"), t("تقديم 5 دقائق", "Forward 5 minutes"), 5 * RecTime.MIN)
        liveBtn = ctl(t("● مباشر", "● Live"), t("البث المباشر لهذه الكاميرا", "This camera live")) { goLive() }
        photoBtn = ctl(t("📷 صورة", "📷 Photo"), t("حفظ صورة", "Save a photo")) { snapshot() }
        camBtn = ctl(t("📹 الكاميرا", "📹 Camera"), t("اختيار كاميرا أخرى", "Choose another camera")) { chooseCamera() }
        camBtn.visibility = if (hikCams().size > 1) View.VISIBLE else View.GONE
        updatePlay()

        arrange()
        setContentView(root)
        if (tv) playBtn.post { playBtn.requestFocus() }
    }

    // puts the parts in place: video above and the rest below (phone upright), or the video on the whole screen with
    // the rest over it (phone on its side, TV). Called again when the phone turns.
    private fun arrange() {
        overlay = tv || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val parts = listOf<View>(
            videoBox, top, panel, dayBar.view, line, legend, hintText, msgRow,
            back5, back30, playBtn, fwd30, fwd5, liveBtn, photoBtn, camBtn,
        )
        for (v in parts) (v.parent as? ViewGroup)?.removeView(v)
        root.removeAllViews()
        panel.removeAllViews()

        // the buttons: one row on a wide screen; phone upright: the time buttons, then the others
        val jumps = row()
        jumps.layoutDirection = View.LAYOUT_DIRECTION_LTR // earlier on the left, like the day line
        for (b in listOf(back5, back30, playBtn, fwd30, fwd5)) jumps.addView(b, Ui.share(this, 3))
        val acts = if (overlay) jumps else row()
        for (b in listOf(liveBtn, photoBtn, camBtn)) acts.addView(b, Ui.share(this, 3))

        if (tv) {
            // the day line first: ▲ from the buttons reaches it, ▼ from the buttons reaches the day
            panel.addView(line, Ui.below(this, 0))
            panel.addView(hintText, Ui.below(this, 6))
            panel.addView(legend, Ui.below(this, 6))
            panel.addView(msgRow, Ui.below(this, 6))
            panel.addView(jumps, Ui.below(this, 8))
            panel.addView(dayBar.view, Ui.below(this, 6))
        } else {
            panel.addView(dayBar.view, Ui.below(this, 0))
            panel.addView(line, Ui.below(this, 6))
            panel.addView(legend, Ui.below(this, 6))
            panel.addView(msgRow, Ui.below(this, 6))
            panel.addView(jumps, Ui.below(this, 8))
            if (acts !== jumps) panel.addView(acts, Ui.below(this, 6))
        }

        val match = FrameLayout.LayoutParams.MATCH_PARENT
        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
        if (overlay) {
            videoBox.ratio = 0f
            root.addView(videoBox, FrameLayout.LayoutParams(match, match))
            top.setBackgroundColor(0xB3000000.toInt())
            panel.setBackgroundColor(0xB3000000.toInt())
            if (tv) {
                // TV: away from the screen's edges (some TVs cut them off)
                top.setPadding(dp(48), dp(24), dp(48), dp(8))
                panel.setPadding(dp(48), dp(8), dp(48), dp(24))
            } else {
                top.setPadding(dp(8), dp(4), dp(8), dp(4))
                panel.setPadding(dp(12), dp(6), dp(12), dp(8))
            }
            val tlp = FrameLayout.LayoutParams(match, wrap)
            tlp.gravity = Gravity.TOP
            root.addView(top, tlp)
            val plp = FrameLayout.LayoutParams(match, wrap)
            plp.gravity = Gravity.BOTTOM
            root.addView(panel, plp)
        } else {
            videoBox.ratio = 9f / 16f
            top.setBackgroundColor(Ui.BG)
            top.setPadding(dp(8), dp(6), dp(8), dp(6))
            panel.setBackgroundColor(Ui.BG)
            panel.setPadding(dp(12), dp(10), dp(12), dp(16))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setBackgroundColor(Ui.BG)
            col.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            col.addView(videoBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val sc = ScrollView(this)
            sc.isFillViewport = true
            sc.addView(panel, FrameLayout.LayoutParams(match, wrap))
            col.addView(sc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(col, FrameLayout.LayoutParams(match, match))
        }
        showPanel(true)
    }

    private fun row(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        return r
    }

    private fun sizeCtl(b: TextView) {
        b.textSize = 14f * Ui.scale(this)
        b.minHeight = dp(48)
        b.minWidth = dp(48)
        b.isSingleLine = true
        b.ellipsize = TextUtils.TruncateAt.END
        val p = dp(6)
        b.setPadding(p, p, p, p)
    }

    private fun ctl(label: String, desc: String?, onClick: () -> Unit): TextView {
        val b = Ui.button(this, label, false, onClick)
        sizeCtl(b)
        if (desc != null) b.contentDescription = desc
        return b
    }

    // −5 دقائق … +5 دقائق: written left to right so the sign stays before the number in Arabic too
    private fun jumpBtn(label: String, desc: String, delta: Long): TextView {
        val b = ctl(label, desc) { jump(delta) }
        b.textDirection = View.TEXT_DIRECTION_LTR
        return b
    }

    // phone on its side: a tap on the video; TV: any key shows, 8 s of playing without keys hides
    private fun showPanel(on: Boolean) {
        val want = on || !overlay
        if (!want && panelOn) lastFocus = currentFocus
        panelOn = want
        val v = if (want) View.VISIBLE else View.GONE
        top.visibility = v
        panel.visibility = v
        if (want && tv) {
            val cf = currentFocus
            if (cf == null || !cf.isShown) {
                val f = lastFocus
                (if (f != null && f.isShown) f else playBtn).requestFocus()
            }
        }
        schedulePanelHide()
    }

    private fun schedulePanelHide() {
        if (!::panel.isInitialized) return
        ui.removeCallbacks(hidePanel)
        if (overlay && tv && panelOn) ui.postDelayed(hidePanel, PANEL_MS)
    }

    private fun notHikvision(c: Camera) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.setBackgroundColor(Ui.BG)
        val p = dp(if (tv) 48 else 24)
        col.setPadding(p, p, p, p)
        val title = Ui.heading(Ui.text(this, c.name, 22f, Ui.TEXT, true))
        title.gravity = Gravity.CENTER
        col.addView(title)
        val msg = Ui.text(
            this,
            t("التسجيلات متاحة لكاميرات ومسجّلات Hikvision فقط.", "Recordings are available for Hikvision cameras and recorders only."),
            17f, Ui.MUTED,
        )
        msg.gravity = Gravity.CENTER
        col.addView(msg, Ui.below(this, 12))
        val back = Ui.button(this, t("رجوع", "Back"), true) { finish() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(24)
        col.addView(back, lp)
        setContentView(col)
        back.post { back.requestFocus() }
    }

    // ---------------------------------------------------------------- remote control

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!::panel.isInitialized) return super.dispatchKeyEvent(e)
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        val up = e.action == KeyEvent.ACTION_UP
        if (code == swallowKey && (up || e.repeatCount > 0)) {
            if (up) swallowKey = -1
            return true
        }
        if (down && e.repeatCount == 0) {
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlay()
                    return used(code)
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (!active) togglePlay()
                    return used(code)
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (active) togglePlay()
                    return used(code)
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    jump(30_000L)
                    return used(code)
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    jump(-30_000L)
                    return used(code)
                }
            }
        }
        if (overlay && tv) {
            // buttons hidden: the first key only shows them
            if (!panelOn && down && code != KeyEvent.KEYCODE_BACK && !systemKey(code)) {
                showPanel(true)
                return used(code)
            }
            schedulePanelHide()
        }
        return super.dispatchKeyEvent(e)
    }

    private fun used(code: Int): Boolean {
        swallowKey = code
        return true
    }

    // keys left to Android: volume, power, home…
    private fun systemKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_SETTINGS -> true
        else -> false
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            !::panel.isInitialized -> super.onBackPressed()
            // TV, on the day line: back to the buttons (the cursor not played is dropped)
            tv && line.isFocused -> {
                line.cursor = -1L
                playBtn.requestFocus()
            }
            overlay && tv && panelOn && active -> showPanel(false)
            else -> super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun exec(task: () -> Unit) {
        try {
            work.execute { task() }
        } catch (_: Exception) {
        }
    }

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

/** A frame whose height follows its width ([ratio] = height / width; 0 = the size its parent gives). */
internal class RatioFrame(c: Context) : FrameLayout(c) {
    var ratio = 0f
        set(v) {
            field = v
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (ratio > 0f && View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.EXACTLY) {
            val h = (View.MeasureSpec.getSize(widthMeasureSpec) * ratio).toInt()
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}

/**
 * ‹ day › and «اليوم» (the recorder's days), for the recordings and the events. Left = earlier, in Arabic too
 * (like the day line). [onPick] gets 00:00 of the day chosen; the screen calls [set] to show it.
 */
internal class DayBar(private val a: Base, private val onPick: (Long) -> Unit) {
    val view = LinearLayout(a)
    private val label = Ui.text(a, "", 16f, Ui.TEXT, true)
    private var day = 0L
    private var today = 0L
    private val prev: TextView
    private val next: TextView
    private val todayBtn: TextView

    init {
        view.orientation = LinearLayout.HORIZONTAL
        view.gravity = Gravity.CENTER_VERTICAL
        view.layoutDirection = View.LAYOUT_DIRECTION_LTR
        prev = arrow("‹", a.t("اليوم السابق", "Previous day")) { onPick(day - RecTime.DAY) }
        next = arrow("›", a.t("اليوم التالي", "Next day")) { if (day < today) onPick(day + RecTime.DAY) }
        todayBtn = Ui.button(a, a.t("اليوم", "Today"), false) { if (day != today) onPick(today) }
        size(todayBtn)
        label.gravity = Gravity.CENTER
        label.isSingleLine = true
        label.ellipsize = TextUtils.TruncateAt.END
        view.addView(prev)
        view.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        view.addView(next)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.marginStart = Ui.dp(a, 8)
        view.addView(todayBtn, lp)
    }

    private fun size(b: TextView) {
        b.textSize = 15f * Ui.scale(a)
        b.minHeight = Ui.dp(a, 48)
        b.minWidth = Ui.dp(a, 48)
        b.isSingleLine = true
        val p = Ui.dp(a, 8)
        b.setPadding(p * 2, p, p * 2, p)
    }

    private fun arrow(glyph: String, desc: String, onClick: () -> Unit): TextView {
        val b = Ui.button(a, glyph, false, onClick)
        size(b)
        b.textSize = 20f * Ui.scale(a)
        b.textDirection = View.TEXT_DIRECTION_LTR // never mirrored: ‹ is always the day before
        b.contentDescription = desc
        return b
    }

    /** Shows [day]; [today] = the recorder's today (no day after it). */
    fun set(day: Long, today: Long) {
        this.day = day
        this.today = today
        label.text = RecTime.dayName(a, day, today)
        // the last day: «›» and «اليوم» stay (the remote's focus is not lost) but look faded and do nothing
        val last = day >= today
        next.alpha = if (last) 0.35f else 1f
        todayBtn.alpha = if (last) 0.35f else 1f
    }
}

/**
 * The 24 hours of one day (recorder clock), always left = earlier (like a video's progress bar, in Arabic too):
 * recorded parts in gold, events as small coloured marks above them, the time playing (white line), the cursor
 * (gold, with its time) while choosing, the hours below. Finger: touch / drag, lift = play from there.
 * Remote: ◄ ► move the cursor 1 minute (held: 5, then 10 minutes), ▲ ▼ next / previous day, OK = play from the
 * cursor (OK without a cursor = pause / play). TalkBack: swipe up / down = 5 minutes later / earlier.
 */
internal class DayTimeline(c: Context) : View(c) {
    var day = 0L
    var now = Long.MAX_VALUE
    var segs: List<Hik.Segment> = emptyList()
    var events: List<Hik.Event> = emptyList()
    var play = -1L
    var cursor = -1L
        set(v) {
            field = v
            invalidate()
        }
    var onPick: ((Long) -> Unit)? = null
    var onDay: ((Int) -> Unit)? = null
    var onOk: (() -> Unit)? = null

    private val tv = Device.isTv(c)
    private val density = c.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init {
        isFocusable = true
        isClickable = true
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        background = Ui.states(c, Ui.CARD, Ui.CARD, 12) // gold ring when the remote is on it
        val sp = density * c.resources.configuration.fontScale * Ui.scale(c)
        label.textSize = 11f * sp
        label.color = Ui.MUTED
        label.textAlign = Paint.Align.CENTER
        bubble.textSize = 12f * sp
        bubble.color = Ui.BG
        bubble.textAlign = Paint.Align.CENTER
        bubble.typeface = Typeface.DEFAULT_BOLD
    }

    private fun px(v: Float): Float = v * density
    private fun x0(): Float = px(14f)
    private fun x1(): Float = width - px(14f)
    private fun xOf(ms: Long): Float = x0() + (x1() - x0()) * ((ms - day).toFloat() / RecTime.DAY)
    private fun msOf(x: Float): Long = day + (((x - x0()) / (x1() - x0())).coerceIn(0f, 1f) * RecTime.DAY).toLong()

    /** [ms] kept inside this day and not after now. */
    fun clamp(ms: Long): Long {
        val hi = maxOf(day, minOf(day + RecTime.DAY - 1000L, now))
        return ms.coerceIn(day, hi)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = px(if (tv) 96f else 84f).toInt()
        setMeasuredDimension(View.getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), View.resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val x0 = x0()
        val x1 = x1()
        val end = day + RecTime.DAY
        val lfm = label.fontMetrics
        val base = height - px(6f) - lfm.descent            // the hours' numbers
        val trackBottom = base + lfm.ascent - px(5f)
        val trackTop = trackBottom - px(if (tv) 22f else 18f)
        val tickTop = trackTop - px(9f)                       // the events' marks

        // the day, its recorded parts in gold
        fill.color = 0xFF0A0F1C.toInt()
        box.set(x0, trackTop, x1, trackBottom)
        canvas.drawRoundRect(box, px(4f), px(4f), fill)
        fill.color = Ui.GOLD
        for (s in segs) {
            val a = maxOf(s.start, day)
            val b = minOf(s.end, end)
            if (b <= a) continue
            val xa = xOf(a)
            box.set(xa, trackTop, maxOf(xOf(b), xa + px(1f)), trackBottom)
            canvas.drawRect(box, fill)
        }
        // what has not happened yet (today, after now)
        if (now in day until end) {
            fill.color = 0xB30A0F1C.toInt()
            box.set(xOf(now), trackTop, x1, trackBottom)
            canvas.drawRect(box, fill)
        }
        // a small line every hour, a number every few hours
        val step = labelStep(x1 - x0)
        fill.color = 0x66FFFFFF
        for (h in 0..24) {
            val x = x0 + (x1 - x0) * h / 24f
            val big = h % step == 0
            canvas.drawRect(x - px(0.5f), trackBottom, x + px(0.5f), trackBottom + px(if (big) 4f else 2f), fill)
            if (big) canvas.drawText(h.toString(), x, base, label)
        }
        // the events, coloured by kind
        for (e in events) {
            if (e.start < day || e.start >= end) continue
            fill.color = EventKinds.color(e.kind)
            val x = xOf(e.start)
            canvas.drawRect(x - px(1.5f), tickTop, x + px(1.5f), trackTop - px(1f), fill)
        }
        // where it plays now
        if (play in day until end) {
            fill.color = Color.WHITE
            val x = xOf(play)
            canvas.drawRect(x - px(1f), tickTop, x + px(1f), trackBottom + px(3f), fill)
        }
        // the cursor being chosen, with its time above it
        val cur = cursor
        if (cur in day until end) {
            fill.color = Ui.GOLD
            val x = xOf(cur)
            val bfm = bubble.fontMetrics
            val text = RecTime.hm(cur)
            val w = bubble.measureText(text) + px(12f)
            val bh = bfm.descent - bfm.ascent + px(4f)
            val bt = maxOf(0f, tickTop - px(2f) - bh)
            val bl = (x - w / 2f).coerceIn(0f, maxOf(0f, width - w))
            box.set(bl, bt, bl + w, bt + bh)
            canvas.drawRoundRect(box, px(6f), px(6f), fill)
            canvas.drawText(text, bl + w / 2f, bt + px(2f) - bfm.ascent, bubble)
            canvas.drawRect(x - px(1.5f), bt + bh, x + px(1.5f), trackBottom + px(4f), fill)
        }
    }

    // every 1, 2, 3, 4 or 6 hours: as many numbers as fit
    private fun labelStep(w: Float): Int {
        val need = label.measureText("24") + px(10f)
        for (s in intArrayOf(1, 2, 3, 4, 6)) if (w / 24f * s >= need) return s
        return 6
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true) // the page must not scroll away from the finger
                isPressed = true
                cursor = clamp(msOf(e.x))
            }
            MotionEvent.ACTION_MOVE -> cursor = clamp(msOf(e.x))
            MotionEvent.ACTION_UP -> {
                isPressed = false
                val at = cursor
                cursor = -1L
                if (at >= 0) onPick?.invoke(at)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                cursor = -1L
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // held: 1 minute at a time, then 5, then 10
                val n = event.repeatCount
                val step = when {
                    n >= 16 -> 10 * RecTime.MIN
                    n >= 6 -> 5 * RecTime.MIN
                    else -> RecTime.MIN
                }
                val base = when {
                    cursor >= 0 -> cursor
                    play in day until day + RecTime.DAY -> play
                    else -> clamp(day + 12 * 60 * RecTime.MIN)
                }
                cursor = clamp(base + if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.repeatCount == 0) onDay?.invoke(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) {
                    val at = cursor
                    if (at >= 0) {
                        cursor = -1L
                        onPick?.invoke(at)
                    } else {
                        onOk?.invoke()
                    }
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> true
        else -> super.onKeyUp(keyCode, event)
    }

    // TalkBack: a slider; swipe up / down = 5 minutes later / earlier
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            val base = if (play in day until day + RecTime.DAY) play else clamp(day + 12 * 60 * RecTime.MIN)
            val d = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 5 * RecTime.MIN else -5 * RecTime.MIN
            onPick?.invoke(clamp(base + d))
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
}

/**
 * Recorder times: "wall-clock ms" (the recorder's date and time read as if UTC, see Hik), shown as they are.
 * Western digits in both languages, like the rest of the app.
 */
internal object RecTime {
    const val DAY = 86_400_000L
    const val MIN = 60_000L
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private val AR_DAYS = arrayOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
    private val EN_DAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val AR_MONTHS = arrayOf(
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر",
    )
    private val EN_MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** This device's clock as a wall clock (its own time zone): until the recorder tells its own. */
    fun localNow(): Long {
        val n = System.currentTimeMillis()
        return n + TimeZone.getDefault().getOffset(n)
    }

    /** 00:00 of the day of [ms]. */
    fun dayOf(ms: Long): Long = ms - ms.mod(DAY)

    /** "14:32:05" */
    fun hms(ms: Long): String {
        val s = (ms - dayOf(ms)) / 1000L
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    /** "14:32" */
    fun hm(ms: Long): String {
        val m = (ms - dayOf(ms)) / MIN
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    private fun cal(ms: Long): Calendar {
        val c = Calendar.getInstance(UTC, Locale.US)
        c.timeInMillis = ms
        return c
    }

    /** "7/11" */
    fun short(ms: Long): String {
        val c = cal(ms)
        return "${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH) + 1}"
    }

    /** «الجمعة 7 نوفمبر 2026» / "Fri 7 Nov 2026" */
    fun date(ctx: Context, day: Long): String {
        val c = cal(day)
        val wd = (c.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)
        val d = c.get(Calendar.DAY_OF_MONTH)
        val m = c.get(Calendar.MONTH).coerceIn(0, 11)
        val y = c.get(Calendar.YEAR)
        return if (L.en(ctx)) "${EN_DAYS[wd]} $d ${EN_MONTHS[m]} $y" else "${AR_DAYS[wd]} $d ${AR_MONTHS[m]} $y"
    }

    /** The date, with «اليوم» / «أمس» before it when it is today / yesterday. */
    fun dayName(ctx: Context, day: Long, today: Long): String = when (day) {
        today -> L.t(ctx, "اليوم", "Today") + " · " + date(ctx, day)
        today - DAY -> L.t(ctx, "أمس", "Yesterday") + " · " + date(ctx, day)
        else -> date(ctx, day)
    }

    /** A length of time on screen: "0:45", "12:05", "1:02:05". */
    fun clock(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /** A length of time in words for TalkBack: «45 ثانية», «دقيقتان» / "45 seconds", "2 minutes". */
    fun spoken(ctx: Context, ms: Long): String {
        val s = (ms / 1000L).toInt().coerceAtLeast(1)
        if (s < 60) {
            return if (L.en(ctx)) (if (s == 1) "1 second" else "$s seconds")
            else Ui.count(s, "ثانية واحدة", "ثانيتان", "ثوانٍ", "ثانية")
        }
        val m = (s + 30) / 60
        return if (L.en(ctx)) (if (m == 1) "1 minute" else "$m minutes")
        else Ui.count(m, "دقيقة واحدة", "دقيقتان", "دقائق", "دقيقة")
    }
}
