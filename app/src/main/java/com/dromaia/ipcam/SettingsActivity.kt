package com.dromaia.ipcam

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Settings, in sections (same idea as the prayer-times app and Mani): language, opening, tour, alerts, lock,
 *  moving cameras, phone as a camera, updates (this / newest / stable version). Extra UPDATE = open at updates. */
class SettingsActivity : Base() {
    companion object {
        const val UPDATE = "update"
    }

    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView
    private var updateSection: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var downloadId = -1L
    private var info: Update.Info? = null
    private var updateStatus: TextView? = null
    private var updateButtons: LinearLayout? = null

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.isFillViewport = true
        col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, if (tv) 48 else 20)
        col.setPadding(p, p, p, p)
        scroll.addView(col)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
        if (intent.getBooleanExtra(UPDATE, false)) {
            intent.removeExtra(UPDATE)
            scroll.post { updateSection?.let { scroll.smoothScrollTo(0, it.top) } }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun build() {
        col.removeAllViews()
        col.addView(Ui.text(this, t("الإعدادات", "Settings"), 28f, Ui.TEXT, true))

        // language
        section(t("اللغة", "Language")).addView(
            choice(listOf("ar" to "العربية", "en" to "English"), Prefs.lang(this)) { v ->
                if (v != Prefs.lang(this)) {
                    Prefs.put(this, "lang", v)
                    // rebuild every screen in the new direction
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                }
            },
        )

        // opening
        section(t("عند فتح التطبيق", "When the app opens")).addView(
            choice(listOf("list" to t("قائمة الكاميرات", "Camera list"), "all" to t("كل الكاميرات مباشرة", "All cameras at once")),
                if (Prefs.openAll(this)) "all" else "list") { v -> Prefs.put(this, "openAll", v == "all"); build() },
        )

        // tour
        val tour = section(t("الجولة التلقائية", "Automatic tour"))
        tour.addView(note(t("كم ثانية تبقى كل كاميرا على الشاشة.", "How many seconds each camera stays on screen.")))
        tour.addView(
            choice(listOf(10, 15, 30, 60).map { it.toString() to t("$it ث", "$it s") }, Prefs.tourSec(this).toString()) { v ->
                Prefs.put(this, "tourSec", v.toInt()); build()
            },
            Ui.below(this, 8),
        )

        buildAlerts()

        // lock
        val lock = section(t("قفل التطبيق برمز", "App lock (PIN)"))
        lock.addView(note(t("اختياري: يُطلب الرمز عند فتح التطبيق وبعد الخروج منه أكثر من 30 ثانية.",
            "Optional: the PIN is asked when the app opens and after it was left for more than 30 seconds.")))
        val lockRow = LinearLayout(this)
        if (Lock.enabled(this)) {
            lockRow.addView(Ui.button(this, t("تغيير الرمز", "Change PIN")) { setPin() }, Ui.share(this, 4))
            lockRow.addView(Ui.button(this, t("إيقاف القفل", "Turn lock off")) { Lock.set(this, null); build() }, Ui.share(this, 4))
        } else {
            lockRow.addView(Ui.button(this, t("تفعيل القفل", "Turn lock on")) { setPin() }, Ui.share(this, 4))
        }
        lock.addView(lockRow, Ui.below(this, 8))

        // moving cameras
        val move = section(t("نقل الكاميرات (نفس الواي فاي)", "Move cameras (same Wi-Fi)"))
        move.addView(note(t("إلى التلفزيون أو إلى جوال أحد العائلة، بدون كتابة العناوين وكلمات المرور.",
            "To the TV or a family phone, without typing addresses and passwords.")))
        val moveRow = LinearLayout(this)
        moveRow.addView(Ui.button(this, t("إرسال الكاميرات", "Send cameras")) {
            startActivity(Intent(this, TransferActivity::class.java).putExtra(TransferActivity.MODE, "send"))
        }, Ui.share(this, 4))
        moveRow.addView(Ui.button(this, t("استقبال الكاميرات", "Receive cameras")) {
            startActivity(Intent(this, TransferActivity::class.java).putExtra(TransferActivity.MODE, "receive"))
        }, Ui.share(this, 4))
        move.addView(moveRow, Ui.below(this, 8))

        // phone as a camera
        if (!tv) {
            val cam = section(t("الجوال كاميرا", "Phone as a camera"))
            cam.addView(note(t("حوّل جوالًا قديمًا إلى كاميرا مراقبة تظهر في التطبيق على باقي الأجهزة، وتقدر تكلّمه.",
                "Turn an old phone into a camera the other devices see in the app, and talk through it.")))
            cam.addView(Ui.button(this, t("اجعل هذا الجوال كاميرا", "Make this phone a camera")) {
                startActivity(Intent(this, CameraModeActivity::class.java))
            }, Ui.below(this, 8))
        }

        buildUpdate()
    }

    // ---------------------------------------------------------------- alerts
    private fun buildAlerts() {
        val s = section(t("تنبيهات الحركة", "Motion alerts"))
        val watched = Store.all(this).count { it.alert }
        s.addView(note(
            if (tv) t("عند الحركة تظهر الكاميرا في زاوية الشاشة فوق أي تطبيق (يوتيوب…) لمدة 30 ثانية.",
                "On motion the camera appears in a corner over any app (YouTube…) for 30 seconds.")
            else t("إشعار على الجوال مع صورة من الكاميرا عند الحركة (والجوال على نفس الواي فاي).",
                "A notification with a picture from the camera on motion (while the phone is on the home Wi-Fi).")
        ))
        s.addView(
            choice(listOf("on" to t("تشغيل", "On"), "off" to t("إيقاف", "Off")), if (Prefs.alertsOn(this)) "on" else "off") { v ->
                Prefs.put(this, "alertsOn", v == "on")
                if (v == "on") askAlertPermissions()
                Alerts.refresh(this)
                build()
            },
            Ui.below(this, 8),
        )
        s.addView(note(
            if (watched == 0) t("لا توجد كاميرا عليها تنبيه بعد: فعّل «تنبيه الحركة» من شاشة تعديل الكاميرا.",
                "No camera has alerts yet: turn on “Motion alert” in the camera's edit screen.")
            else t("الكاميرات المراقَبة: ", "Watched cameras: ") + Ui.cameras(this, watched)
        ), Ui.below(this, 8))

        // hours
        s.addView(Ui.text(this, t("الأوقات", "Hours"), 15f, Ui.TEXT, true), Ui.below(this, 14))
        val from = Prefs.alertFrom(this)
        val to = Prefs.alertTo(this)
        val mode = when {
            from == to -> "all"
            from == 22 * 60 && to == 6 * 60 -> "night"
            else -> "custom"
        }
        s.addView(
            choice(listOf("all" to t("طوال اليوم", "All day"), "night" to t("الليل (10م–6ص)", "Night (10pm–6am)"),
                "custom" to t("مخصص…", "Custom…")), mode) { v ->
                when (v) {
                    "all" -> { Prefs.put(this, "alertFrom", 0); Prefs.put(this, "alertTo", 0); build() }
                    "night" -> { Prefs.put(this, "alertFrom", 22 * 60); Prefs.put(this, "alertTo", 6 * 60); build() }
                    else -> pickHours()
                }
            },
            Ui.below(this, 8),
        )
        if (mode == "custom") s.addView(note(t("من ", "From ") + hhmm(from) + t(" إلى ", " to ") + hhmm(to)), Ui.below(this, 6))

        if (tv && !Alerts.canOverlay(this)) {
            s.addView(note(t("يحتاج التلفزيون إذنًا لإظهار الكاميرا فوق التطبيقات.", "The TV needs permission to show the camera over other apps.")), Ui.below(this, 12))
            s.addView(Ui.button(this, t("السماح بالظهور فوق التطبيقات", "Allow showing over other apps")) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    toast(t("صفحة الإذن غير موجودة في هذا التلفزيون. من كمبيوتر: adb shell appops set com.dromaia.ipcam SYSTEM_ALERT_WINDOW allow",
                        "This TV has no permission page. From a computer: adb shell appops set com.dromaia.ipcam SYSTEM_ALERT_WINDOW allow"))
                }
            }, Ui.below(this, 8))
        }
        if (!tv && Build.VERSION.SDK_INT >= 23) {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName) && Prefs.alertsOn(this)) {
                s.addView(note(t("حتى لا يوقف الجوال المراقبة لتوفير البطارية:", "So the phone does not stop watching to save battery:")), Ui.below(this, 12))
                s.addView(Ui.button(this, t("السماح بالعمل في الخلفية", "Allow running in the background")) {
                    try {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                    }
                }, Ui.below(this, 8))
            }
        }
    }

    private fun askAlertPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    private fun pickHours() {
        val from = Prefs.alertFrom(this)
        TimePickerDialog(this, { _, h, m ->
            val f = h * 60 + m
            val to = Prefs.alertTo(this)
            TimePickerDialog(this, { _, h2, m2 ->
                Prefs.put(this, "alertFrom", f)
                Prefs.put(this, "alertTo", h2 * 60 + m2)
                Alerts.refresh(this)
                build()
            }, to / 60, to % 60, true).also { it.setTitle(t("إلى الساعة", "Until")) }.show()
        }, from / 60, from % 60, true).also { it.setTitle(t("من الساعة", "From")) }.show()
    }

    private fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    // ---------------------------------------------------------------- updates
    private fun buildUpdate() {
        val s = section(t("التحديث", "Updates"))
        updateSection = s
        val mine = Update.versionNumber(this)
        s.addView(Ui.text(this, t("نسختك: $mine", "Your version: $mine"), 17f, Ui.TEXT, true))
        val status = note(t("أفحص GitHub…", "Checking GitHub…"))
        updateStatus = status
        s.addView(status, Ui.below(this, 6))
        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.VERTICAL
        updateButtons = buttons
        s.addView(buttons, Ui.below(this, 4))
        s.addView(note(t("«المستقرة» = آخر نسخة جرّبتها واعتمدتها. الجهاز لا يقبل نسخة أقدم من المثبّتة.",
            "“Stable” = the last version you tested and approved. Android does not install an older version over a newer one.")), Ui.below(this, 10))
        val known = info
        if (known != null) showUpdate(known) else thread {
            val i = Update.check()
            info = i
            runOnUiThread { if (!isFinishing) showUpdate(i) }
        }
    }

    private fun showUpdate(i: Update.Info) {
        val status = updateStatus ?: return
        val buttons = updateButtons ?: return
        val mine = Update.versionNumber(this)
        buttons.removeAllViews()
        if (i.latest == 0) {
            status.text = t("تعذّر الوصول إلى GitHub. تأكد من الإنترنت.", "Could not reach GitHub. Check the internet.")
        } else {
            val stableText = if (i.stable > 0) t("النسخة المستقرة: ${i.stable}", "Stable version: ${i.stable}")
            else t("لا توجد نسخة مستقرة بعد", "No stable version yet")
            status.text = t("أحدث نسخة: ${i.latest}", "Newest version: ${i.latest}") + "\n" + stableText +
                (if (i.latest <= mine) t("\n✓ عندك أحدث نسخة", "\n✓ You have the newest version") else "")
        }
        if (i.latest > mine) buttons.addView(Ui.button(this, t("⬆ تثبيت الأحدث (${i.latest})", "⬆ Install the newest (${i.latest})"), primary = true) {
            download(i.latest)
        }, Ui.below(this, 8))
        if (i.stable > mine && i.stable != i.latest) buttons.addView(Ui.button(this, t("تثبيت المستقرة (${i.stable})", "Install the stable one (${i.stable})")) {
            download(i.stable)
        }, Ui.below(this, 8))
        buttons.addView(Ui.button(this, t("فحص مرة ثانية", "Check again")) {
            info = null
            status.text = t("أفحص GitHub…", "Checking GitHub…")
            thread {
                val n = Update.check()
                info = n
                runOnUiThread { if (!isFinishing) showUpdate(n) }
            }
        }, Ui.below(this, 8))
    }

    private fun download(version: Int) {
        if (downloadId >= 0) return
        when (val id = Update.start(this, version)) {
            Update.NEEDS_PERMISSION -> toast(t("اسمح للتطبيق بتثبيت التحديثات، ثم اضغط مرة ثانية.", "Allow the app to install updates, then tap again."))
            Update.FAILED -> toast(t("تعذّر بدء التنزيل.", "Could not start the download."))
            else -> {
                downloadId = id
                poll()
            }
        }
    }

    private fun poll() {
        val pct = Update.progress(this, downloadId)
        when {
            pct < 0 -> {
                downloadId = -1L
                updateStatus?.text = t("تعذّر تنزيل التحديث. تأكد من الإنترنت.", "The download failed. Check the internet.")
            }
            pct >= 100 -> {
                Update.install(this, downloadId)
                downloadId = -1L
            }
            else -> {
                updateStatus?.text = t("جاري التنزيل… ${pct}٪", "Downloading… ${pct}%")
                handler.postDelayed({ poll() }, 700)
            }
        }
    }

    // ---------------------------------------------------------------- helpers
    private fun section(title: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 16)
        box.setPadding(p, p, p, p)
        box.background = Ui.round(Ui.CARD, Ui.dp(this, 16).toFloat())
        box.addView(Ui.text(this, title, 18f, Ui.GOLD, true))
        col.addView(box, Ui.below(this, 16))
        return box
    }

    private fun note(s: String): TextView {
        val n = Ui.text(this, s, 14f, Ui.MUTED)
        n.setLineSpacing(0f, 1.25f)
        return n
    }

    /** A row of buttons, the chosen one in gold. */
    private fun choice(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        for ((key, label) in options) {
            val on = key == current
            val b = Ui.button(this, (if (on) "✓ " else "") + label, primary = on) { onPick(key) }
            b.textSize = 15f * Ui.scale(this)
            b.contentDescription = label + if (on) t("، مختار", ", selected") else ""
            row.addView(b, Ui.share(this, 4))
        }
        return row
    }

    private fun setPin() = startActivity(Intent(this, LockActivity::class.java).putExtra(LockActivity.SET, true))

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
