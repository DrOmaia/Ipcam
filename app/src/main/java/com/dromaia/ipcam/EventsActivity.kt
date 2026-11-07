package com.dromaia.ipcam

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Events of the Hikvision recorders for one day (recorder clock), newest first: person / vehicle (AcuSense), motion,
 * line crossing, intrusion. Filters «الكل / شخص / سيارة / حركة / عبور خط». A tap / OK on an event plays its
 * recording from 5 seconds before it (PlaybackActivity).
 * Extras: [ID] = one camera; [GROUP] = the cameras of that group; none = every Hikvision camera.
 * Cameras of the same recorder are asked together (Camera.sameDevice), each recorder once, side by side.
 * A recorder that refused the password is not asked again for a while (Hik.refused).
 * TV: every event is a focusable card; after a refresh the remote's focus stays on the same event.
 */
class EventsActivity : Base() {
    companion object {
        const val ID = "id"       // String: only this camera
        const val GROUP = "group" // String: only the cameras of this group
        private const val PAGE = 100 // cards shown at a time («عرض المزيد» adds more)
        private const val ERR_REACH = 1
        private const val ERR_AUTH = 2
        private val FILTERS = listOf("all", "human", "vehicle", "motion", "line")

        fun intent(c: Context, id: String? = null, group: String? = null): Intent {
            val i = Intent(c, EventsActivity::class.java)
            if (id != null) i.putExtra(ID, id)
            if (group != null) i.putExtra(GROUP, group)
            return i
        }
    }

    private class Row(val ev: Hik.Event, val cam: Camera) {
        val key = cam.id + "|" + ev.start + "|" + ev.kind
    }

    // one recorder's answer: its events, a name to show, or why it could not be read
    private class Answer(val rows: List<Row>, val label: String, val error: Int)

    private var devices: List<List<Camera>> = emptyList()
    private var filter = "all"
    private var day = 0L
    private var dayChosen = false  // he chose the day himself (else: the recorder's today)
    @Volatile private var offset = RecTime.localNow() - System.currentTimeMillis()
    @Volatile private var gen = 0
    private var rows: List<Row>? = null // the day's events, newest first; null = not read yet
    private var failed: List<Answer> = emptyList()
    private var loading = false
    private var loadedDay = -1L
    private var loadedAt = 0L
    private var shown = PAGE
    private var focusKey: String? = null // the event the remote's focus was on
    private var firstFocus = true
    private val ui = Handler(Looper.getMainLooper())
    private val pool: ExecutorService = Executors.newFixedThreadPool(3)
    private val chips = LinkedHashMap<String, TextView>()

    private lateinit var dayBar: DayBar
    private lateinit var statusText: TextView
    private lateinit var listBox: LinearLayout

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val hik = Store.all(this).filter { it.brand == Brand.HIKVISION }
        val id = intent.getStringExtra(ID)
        val group = intent.getStringExtra(GROUP)?.trim()?.ifEmpty { null }
        val cams = when {
            id != null -> hik.filter { it.id == id }
            group != null -> hik.filter { it.group.trim() == group }
            else -> hik
        }
        // the cameras of one recorder together
        val devs = ArrayList<MutableList<Camera>>()
        for (c in cams) {
            val d = devs.firstOrNull { it[0].sameDevice(c) }
            if (d != null) d.add(c) else devs.add(mutableListOf(c))
        }
        devices = devs
        if (saved != null) {
            filter = saved.getString("filter") ?: "all"
            day = saved.getLong("day", 0L)
            dayChosen = day > 0
        }

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, if (tv) 48 else 16)
        col.setPadding(p, Ui.dp(this, if (tv) 27 else 16), p, p) // TV: away from the screen's edges
        scroll.addView(col)
        setContentView(scroll)

        // back · «الأحداث» · refresh
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(small(t("→ رجوع", "← Back"), null) { finish() })
        val title = Ui.heading(Ui.text(this, t("🔔 الأحداث", "🔔 Events"), 24f, Ui.TEXT, true))
        title.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0)
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val refresh = small(t("↻ تحديث", "↻ Refresh"), t("تحديث الأحداث", "Refresh the events")) { load(keep = true) }
        head.addView(refresh)
        col.addView(head)
        val sub = when {
            id != null -> cams.firstOrNull()?.name ?: ""
            group != null -> group
            else -> t("كل كاميرات Hikvision", "All Hikvision cameras")
        }
        col.addView(Ui.text(this, sub, 15f, Ui.MUTED), Ui.below(this, 4))

        if (cams.isEmpty()) {
            refresh.visibility = View.GONE
            col.addView(
                Ui.text(
                    this,
                    t(
                        "الأحداث متاحة لكاميرات ومسجّلات Hikvision فقط، ولا توجد منها كاميرا هنا.",
                        "Events are available for Hikvision cameras and recorders only, and there is none here.",
                    ),
                    17f, Ui.TEXT,
                ),
                Ui.below(this, 24),
            )
            return
        }

        // the kinds: All / Person / Vehicle / Motion / Line (the chosen one in gold)
        val chipScroll = HorizontalScrollView(this)
        chipScroll.isHorizontalScrollBarEnabled = false
        val chipRow = LinearLayout(this)
        chipRow.orientation = LinearLayout.HORIZONTAL
        for (k in FILTERS) {
            val b = small(chipText(k, null), null) { setFilter(k) }
            chips[k] = b
            chipRow.addView(b)
        }
        chipScroll.addView(chipRow)
        col.addView(chipScroll, Ui.below(this, 12))

        dayBar = DayBar(this) { d -> pickDay(d) }
        col.addView(dayBar.view, Ui.below(this, 10))

        statusText = Ui.text(this, "", 15f, Ui.MUTED)
        statusText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        col.addView(statusText, Ui.below(this, 10))

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        col.addView(listBox, Ui.below(this, 4))

        if (!dayChosen) day = RecTime.dayOf(nowMs())
        dayBar.set(day, today())
        if (tv) chips[filter]?.let { c -> c.post { c.requestFocus() } }
        start()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("filter", filter)
        if (dayChosen) out.putLong("day", day)
    }

    // back from a recording after a while: today's list again (new events), the focus kept
    override fun onStart() {
        super.onStart()
        if (::listBox.isInitialized && loadedAt > 0 && !loading && day == today() &&
            SystemClock.elapsedRealtime() - loadedAt > 120_000L
        ) {
            load(keep = true)
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try {
            pool.shutdownNow()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun nowMs(): Long = System.currentTimeMillis() + offset

    private fun today(): Long = RecTime.dayOf(nowMs())

    // the first recorder's clock (its "today"), then the events
    private fun start() {
        val first = devices.firstOrNull()?.firstOrNull() ?: return
        rows = null
        loading = true
        render()
        val g = ++gen
        exec {
            val now = try {
                Hik.now(first)
            } catch (_: Exception) {
                RecTime.localNow()
            }
            offset = now - System.currentTimeMillis()
            ui.post {
                if (g != gen || isFinishing || isDestroyed) return@post
                if (!dayChosen) day = today()
                dayBar.set(day, today())
                load(keep = false)
            }
        }
    }

    // asks every recorder for the day's events; keep = the old list stays on screen meanwhile (same day)
    private fun load(keep: Boolean) {
        val g = ++gen
        val d = day
        if (!keep || loadedDay != d) {
            rows = null
            failed = emptyList()
            shown = PAGE
        }
        loading = true
        render()
        val devs = devices
        Thread {
            try {
                val futures = devs.map { dev -> pool.submit(Callable { fetch(dev, d) }) }
                val answers = futures.mapIndexed { i, f ->
                    try {
                        f.get()
                    } catch (_: Exception) {
                        Answer(emptyList(), label(devs[i]), ERR_REACH)
                    }
                }
                ui.post {
                    if (g != gen || isFinishing || isDestroyed) return@post
                    rows = answers.flatMap { it.rows }.sortedByDescending { it.ev.start }
                    failed = answers.filter { it.error != 0 }
                    loadedDay = d
                    loadedAt = SystemClock.elapsedRealtime()
                    loading = false
                    render()
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    // background thread: one recorder
    private fun fetch(dev: List<Camera>, d: Long): Answer {
        val first = dev[0]
        val name = label(dev)
        if (Hik.refused(first)) return Answer(emptyList(), name, ERR_AUTH)
        val evs = try {
            Hik.events(dev, d, d + RecTime.DAY)
        } catch (_: Exception) {
            null
        }
        if (evs == null) return Answer(emptyList(), name, if (Hik.refused(first)) ERR_AUTH else ERR_REACH)
        val byChannel = dev.associateBy { it.channel }
        return Answer(evs.mapNotNull { e -> byChannel[e.channel]?.let { Row(e, it) } }, name, 0)
    }

    // a recorder by its cameras' names: «الباب، الحوش…»
    private fun label(dev: List<Camera>): String =
        dev.take(2).joinToString(t("، ", ", ")) { it.name } + if (dev.size > 2) "…" else ""

    private fun setFilter(k: String) {
        if (k == filter) return
        filter = k
        shown = PAGE
        render()
    }

    private fun pickDay(d: Long) {
        val td = today()
        day = d.coerceIn(0L, td)
        dayChosen = true
        dayBar.set(day, td)
        load(keep = false)
    }

    private fun matches(f: String, kind: String) = f == "all" || kind == f

    private fun chipText(k: String, n: Int?): String {
        val name = when (k) {
            "human" -> t("شخص", "Person")
            "vehicle" -> t("سيارة", "Vehicle")
            "motion" -> t("حركة", "Motion")
            "line" -> t("عبور خط", "Line")
            else -> t("الكل", "All")
        }
        return if (n == null) name else "$name ($n)"
    }

    private fun styleChips() {
        val all = rows
        for ((k, b) in chips) {
            val sel = k == filter
            b.text = chipText(k, all?.count { matches(k, it.ev.kind) })
            b.background = Ui.states(this, if (sel) Ui.GOLD else Ui.CARD, if (sel) Ui.GOLD_DOWN else Ui.CARD_DOWN)
            b.setTextColor(if (sel) Ui.BG else Ui.TEXT)
            b.isSelected = sel
            if (Build.VERSION.SDK_INT >= 30) b.stateDescription = if (sel) t("محدَّد", "Selected") else null
        }
    }

    private fun countText(n: Int): String =
        if (L.en(this)) (if (n == 1) "1 event" else "$n events") else Ui.count(n, "حدث واحد", "حدثان", "أحداث", "حدثًا")

    // the line above the list and the cards; the remote's focus stays on the same event when it was in the list
    private fun render() {
        if (!::listBox.isInitialized) return
        val hadFocus = listBox.findFocus() != null
        styleChips()
        val all = rows
        val list = all?.filter { matches(filter, it.ev.kind) } ?: emptyList()

        val lines = ArrayList<String>()
        if (all == null) {
            lines.add(t("جارٍ تحميل الأحداث…", "Loading the events…"))
        } else {
            val allFailed = failed.size >= devices.size
            if (list.isNotEmpty()) {
                lines.add(countText(list.size))
            } else if (!allFailed) {
                lines.add(
                    if (filter == "all") t("لا توجد أحداث في هذا اليوم.", "No events on this day.")
                    else t("لا توجد أحداث من هذا النوع في هذا اليوم.", "No events of this kind on this day."),
                )
            }
            val sep = t("، ", ", ")
            val reach = failed.filter { it.error == ERR_REACH }
            val auth = failed.filter { it.error == ERR_AUTH }
            if (reach.isNotEmpty()) {
                val names = reach.joinToString(sep) { it.label }
                lines.add(
                    t(
                        "تعذّر الوصول إلى المسجّل ($names). تأكد أنه يعمل، وإن كنت خارج البيت فتأكد من عنوانه من الخارج.",
                        "Could not reach the recorder ($names). Make sure it is on; away from home, check its outside address.",
                    ),
                )
            }
            if (auth.isNotEmpty()) {
                val names = auth.joinToString(sep) { it.label }
                lines.add(
                    t(
                        "رفض المسجّل ($names) اسم المستخدم أو كلمة المرور. عدّل الكاميرا وصحّح كلمة المرور.",
                        "The recorder ($names) refused the username or password. Edit the camera and fix the password.",
                    ),
                )
            }
            if (loading) lines.add(t("جارٍ التحديث…", "Updating…"))
        }
        statusText.text = lines.joinToString("\n")
        statusText.setTextColor(if (all != null && failed.isNotEmpty()) Ui.RED else Ui.MUTED)

        listBox.removeAllViews()
        for (r in list.take(shown)) listBox.addView(card(r), Ui.below(this, 8))
        if (list.size > shown) {
            val more = Ui.button(this, t("عرض المزيد", "Show more")) {
                focusKey = list.getOrNull(shown)?.key // the remote lands on the first new event
                shown += PAGE
                render()
            }
            listBox.addView(more, Ui.below(this, 10))
        }
        if (hadFocus) {
            restoreFocus()
        } else if (tv && firstFocus && list.isNotEmpty() && all != null && !loading) {
            // TV, the first list: the remote starts on the newest event (unless it already went elsewhere)
            firstFocus = false
            val cf = currentFocus
            if (cf == null || chips.values.any { it === cf }) listBox.getChildAt(0)?.requestFocus()
        }
    }

    private fun restoreFocus() {
        val key = focusKey
        var target: View? = null
        if (key != null) {
            for (i in 0 until listBox.childCount) {
                val v = listBox.getChildAt(i)
                if (v.tag == key) {
                    target = v
                    break
                }
            }
        }
        (target ?: listBox.getChildAt(0) ?: chips[filter])?.requestFocus()
    }

    // one event: colour of its kind · icon · kind and camera · time and length
    private fun card(r: Row): View {
        val card = Ui.card(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.tag = r.key
        val kind = r.ev.kind

        val bar = View(this)
        bar.background = Ui.round(EventKinds.color(kind), Ui.dp(this, 2).toFloat())
        card.addView(bar, LinearLayout.LayoutParams(Ui.dp(this, 4), Ui.dp(this, 40)))

        val icon = Ui.text(this, EventKinds.icon(kind), 24f)
        icon.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0)
        card.addView(icon)

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.addView(Ui.text(this, EventKinds.label(this, kind), 17f, Ui.TEXT, true))
        val camName = Ui.text(this, r.cam.name, 14f, Ui.MUTED)
        camName.isSingleLine = true
        camName.ellipsize = TextUtils.TruncateAt.END
        mid.addView(camName)
        card.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val end = LinearLayout(this)
        end.orientation = LinearLayout.VERTICAL
        end.gravity = Gravity.END
        val hms = RecTime.hms(r.ev.start)
        val time = Ui.text(this, hms, 17f, Ui.GOLD, true)
        time.textDirection = View.TEXT_DIRECTION_LTR
        end.addView(time)
        val dur = r.ev.end - r.ev.start
        if (dur >= 1000L) {
            val d = Ui.text(this, "⏱ " + RecTime.clock(dur), 13f, Ui.MUTED)
            d.textDirection = View.TEXT_DIRECTION_LTR
            end.addView(d)
        }
        card.addView(end)

        var desc = EventKinds.label(this, kind) + t("، ", ", ") + r.cam.name + t("، الساعة ", ", at ") + hms
        if (dur >= 1000L) desc += t("، المدة ", ", lasting ") + RecTime.spoken(this, dur)
        card.contentDescription = desc + t("، اضغط لمشاهدة التسجيل", ", tap to watch the recording")
        card.setOnClickListener { open(r) }
        card.setOnFocusChangeListener { _, has -> if (has) focusKey = r.key }
        return card
    }

    // the recording, from 5 seconds before the event
    private fun open(r: Row) {
        try {
            startActivity(PlaybackActivity.intent(this, r.cam.id, r.ev.start - 5_000L))
        } catch (_: Exception) {
        }
    }

    private fun small(label: String, desc: String?, onClick: () -> Unit): TextView {
        val b = Ui.button(this, label, false, onClick)
        b.textSize = 15f * Ui.scale(this)
        b.minHeight = Ui.dp(this, 48)
        b.minWidth = Ui.dp(this, 48)
        b.isSingleLine = true
        val p = Ui.dp(this, 8)
        b.setPadding(p * 2, p, p * 2, p)
        if (desc != null) b.contentDescription = desc
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = Ui.dp(this, 6)
        b.layoutParams = lp
        return b
    }

    private fun exec(task: () -> Unit) {
        try {
            pool.execute { task() }
        } catch (_: Exception) {
        }
    }
}

/** Kinds of events (Hik.Event.kind): colour on the day line, icon and name. */
internal object EventKinds {
    /** The order of the legend. */
    val ORDER = listOf("human", "vehicle", "motion", "line", "intrusion", "other")

    fun color(kind: String): Int = when (kind) {
        "human" -> 0xFF5BC0EB.toInt()
        "vehicle" -> 0xFFC792EA.toInt()
        "motion" -> Ui.TEXT
        "line" -> 0xFFFFA45C.toInt()
        "intrusion" -> Ui.RED
        else -> Ui.MUTED
    }

    fun icon(kind: String): String = when (kind) {
        "human" -> "🚶"
        "vehicle" -> "🚗"
        "motion" -> "〰️"
        "line" -> "🚧"
        "intrusion" -> "⚠️"
        else -> "🔔"
    }

    fun label(c: Context, kind: String): String = when (kind) {
        "human" -> L.t(c, "شخص", "Person")
        "vehicle" -> L.t(c, "سيارة", "Vehicle")
        "motion" -> L.t(c, "حركة", "Motion")
        "line" -> L.t(c, "عبور خط", "Line crossing")
        "intrusion" -> L.t(c, "تسلّل إلى منطقة", "Intrusion")
        else -> L.t(c, "حدث", "Event")
    }
}
