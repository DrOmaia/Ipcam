package com.dromaia.ipcam

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Settings of one live camera widget: opened by the launcher when the widget is added, and later by its ⚙ button
 * (or the launcher's «reconfigure»). He picks one or several cameras, how often the picture is refreshed
 * (1 / 3 / 5 / 10 s) and, with several cameras, how often the next one comes (10 / 15 / 30 / 60 s).
 * Saving shows the widget and starts its pictures; leaving the first time without saving removes the new widget.
 */
class WidgetSetupActivity : Base() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val checks = ArrayList<Pair<Camera, CheckBox>>()
    private val snapChips = ArrayList<Pair<Int, TextView>>()
    private val switchChips = ArrayList<Pair<Int, TextView>>()
    private var snapSec = 3
    private var switchSec = 15
    private var switchBox: LinearLayout? = null

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // leaving without saving: the launcher does not add the new widget (a widget already there stays as it was)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || !ours(widgetId)) {
            finish()
            return
        }
        val old = LiveWidget.conf(this, widgetId)
        snapSec = old?.snapSec ?: 3
        switchSec = old?.switchSec ?: 15
        var chosen: Set<String>? = old?.cams?.toSet()
        if (saved != null) {
            snapSec = saved.getInt("snap", snapSec)
            switchSec = saved.getInt("switch", switchSec)
            saved.getStringArray("chosen")?.let { chosen = it.toSet() }
        }
        build(chosen)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("snap", snapSec)
        out.putInt("switch", switchSec)
        out.putStringArray("chosen", checks.filter { it.second.isChecked }.map { it.first.id }.toTypedArray())
    }

    // not a widget of another app (another app could open this screen with any number)
    private fun ours(id: Int): Boolean = try {
        val info = AppWidgetManager.getInstance(this)?.getAppWidgetInfo(id)
        info == null || info.provider == ComponentName(this, LiveWidget::class.java)
    } catch (_: Exception) {
        true
    }

    private fun build(chosen: Set<String>?) {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.isFillViewport = true
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(this, 20)
        box.setPadding(pad, pad, pad, pad)
        scroll.addView(box)

        val title = Ui.text(this, t("كاميرا حيّة", "Live camera"), 24f, Ui.GOLD, true)
        Ui.heading(title)
        box.addView(title)
        box.addView(
            Ui.text(
                this,
                t(
                    "صورة من الكاميرا تتجدّد على الشاشة الرئيسية، وتتوقف وحدها بعد 5 دقائق.",
                    "A picture of the camera that refreshes on the home screen and stops by itself after 5 minutes.",
                ),
                15f, Ui.MUTED,
            ),
            Ui.below(this, 6),
        )

        val cams = Store.all(this)
        if (cams.isEmpty()) {
            box.addView(
                Ui.text(this, t("لا توجد كاميرات بعد. أضف كاميرا في التطبيق أولًا.", "No cameras yet. Add a camera in the app first."), 17f),
                Ui.below(this, 24),
            )
            box.addView(
                Ui.button(this, t("فتح التطبيق", "Open the app"), true) {
                    try {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                    }
                    finish()
                },
                Ui.below(this, 16),
            )
            setContentView(scroll)
            return
        }

        box.addView(section(t("الكاميرات", "Cameras")), Ui.below(this, 24))
        box.addView(Ui.text(this, t("اختر كاميرا واحدة أو أكثر", "Choose one camera or more"), 14f, Ui.MUTED), Ui.below(this, 2))
        val first = cams.first().id
        for (cam in cams) {
            val label = if (cam.group.isBlank()) cam.name else "${cam.name} · ${cam.group.trim()}"
            val on = chosen?.contains(cam.id) ?: (cam.id == first)
            val cb = Ui.check(this, label, on)
            cb.setOnCheckedChangeListener { _, _ -> showSwitch() }
            checks.add(Pair(cam, cb))
            box.addView(cb, Ui.below(this, 4))
        }

        box.addView(section(t("تحديث الصورة كل", "Refresh the picture every")), Ui.below(this, 24))
        box.addView(chips(LiveConf.SNAPS, snapChips, { snapSec }) { snapSec = it }, Ui.below(this, 8))

        val sw = LinearLayout(this)
        sw.orientation = LinearLayout.VERTICAL
        sw.addView(section(t("التبديل بين الكاميرات كل", "Switch cameras every")), Ui.below(this, 24))
        sw.addView(chips(LiveConf.SWITCHES, switchChips, { switchSec }) { switchSec = it }, Ui.below(this, 8))
        switchBox = sw
        box.addView(sw)
        showSwitch()

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(Ui.button(this, t("حفظ", "Save"), true) { save() }, Ui.share(this, 4))
        row.addView(Ui.button(this, t("إلغاء", "Cancel")) { finish() }, Ui.share(this, 4))
        box.addView(row, Ui.below(this, 32))

        setContentView(scroll)
    }

    private fun section(s: String): TextView {
        val v = Ui.text(this, s, 18f, Ui.TEXT, true)
        Ui.heading(v)
        return v
    }

    // a row of choices (seconds); the chosen one is gold, and TalkBack says «selected»
    private fun chips(values: IntArray, into: ArrayList<Pair<Int, TextView>>, now: () -> Int, pick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (v in values) {
            val b = Ui.button(this, seconds(v)) {
                pick(v)
                paint(into, v)
            }
            b.textSize = 15f * Ui.scale(this)
            b.isSingleLine = true
            val p = Ui.dp(this, 6)
            b.setPadding(p, p, p, p)
            into.add(Pair(v, b))
            row.addView(b, Ui.share(this, 3))
        }
        paint(into, now())
        return row
    }

    private fun paint(chips: List<Pair<Int, TextView>>, chosen: Int) {
        for ((v, b) in chips) {
            val on = v == chosen
            b.background = if (on) Ui.states(this, Ui.GOLD, Ui.GOLD_DOWN) else Ui.states(this, Ui.CARD, Ui.CARD_DOWN)
            b.setTextColor(if (on) Ui.BG else Ui.TEXT)
            b.isSelected = on
        }
    }

    // 1 → «ثانية واحدة», 3 → «3 ثوانٍ», 15 → «15 ثانية», 60 → «دقيقة»
    private fun seconds(n: Int): String = when {
        n == 60 -> t("دقيقة", "1 minute")
        L.en(this) -> if (n == 1) "1 second" else "$n seconds"
        else -> Ui.count(n, "ثانية واحدة", "ثانيتان", "ثوانٍ", "ثانية")
    }

    // the switch interval matters only with two cameras or more
    private fun showSwitch() {
        val n = checks.count { it.second.isChecked }
        switchBox?.visibility = if (n > 1) View.VISIBLE else View.GONE
    }

    private fun save() {
        val ids = checks.filter { it.second.isChecked }.map { it.first.id }
        if (ids.isEmpty()) {
            try {
                Toast.makeText(applicationContext, t("اختر كاميرا واحدة على الأقل", "Choose at least one camera"), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
            return
        }
        val old = LiveWidget.conf(this, widgetId)
        // the camera shown now stays shown when it is still chosen; the kept picture stays with its camera
        val curId = if (old == null) null else old.cams.getOrNull(old.index)
        val index = if (curId == null) 0 else maxOf(0, ids.indexOf(curId))
        val conf = LiveConf(ids, snapSec, switchSec, index, old?.at ?: 0L, old?.picCam ?: "")
        LiveWidget.saveConf(this, widgetId, conf)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        LiveWidget.changed(this, widgetId)
        finish()
    }
}
