package com.dromaia.ipcam

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
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
import android.view.accessibility.AccessibilityNodeInfo
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
        private const val PENDING = "pendingVersion"
    }

    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView
    private var updateSection: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pollTask = Runnable { poll() }
    private var downloadId = -1L     // the update being downloaded (also kept in Prefs "updId")
    private var pendingVersion = 0   // asked to install it, waiting for «allow installing apps»
    private var resumed = false
    private var info: Update.Info? = null
    private var updateStatus: TextView? = null
    private var updateButtons: LinearLayout? = null
    private var focusTag: String? = null // the option just chosen: the TV remote / TalkBack go back to it after a rebuild

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        pendingVersion = saved?.getInt(PENDING, 0) ?: 0
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

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(PENDING, pendingVersion)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        downloadId = Prefs.updId(this)
        build()
        var toUpdate = intent.getBooleanExtra(UPDATE, false)
        if (toUpdate) intent.removeExtra(UPDATE)
        if (downloadId >= 0) {
            poll() // a download started before: show its progress, or install it if it finished meanwhile
        } else if (pendingVersion > 0 && canInstall()) {
            // he allowed installing updates and came back: the update starts by itself
            val v = pendingVersion
            pendingVersion = 0
            download(v)
            toUpdate = true
        }
        if (toUpdate) scroll.post { updateSection?.let { scroll.smoothScrollTo(0, it.top) } }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onStop() {
        handler.removeCallbacks(pollTask) // polling starts again in onResume
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun build() {
        col.removeAllViews()
        col.addView(Ui.heading(Ui.text(this, t("الإعدادات", "Settings"), 28f, Ui.TEXT, true)))

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
            choice(listOf("list" to t("قائمة الكاميرات", "Camera list"), "all" to t("كل الكاميرات مباشرة", "Straight to all cameras")),
                if (Prefs.openAll(this)) "all" else "list") { v -> Prefs.put(this, "openAll", v == "all"); build() },
        )

        // tour
        val tour = section(t("الجولة التلقائية", "Automatic tour"))
        tour.addView(note(t("مدة بقاء كل كاميرا على الشاشة.", "How many seconds each camera stays on screen.")))
        tour.addView(
            choice(listOf(10, 15, 30, 60).map { it.toString() to seconds(it) }, Prefs.tourSec(this).toString()) { v ->
                Prefs.put(this, "tourSec", v.toInt()); build()
            },
            Ui.below(this, 8),
        )

        // all cameras: how many on one page (this device), and the order of the cameras
        val grid = section(t("كل الكاميرات", "All cameras"))
        grid.addView(note(t("عدد الكاميرات في الصفحة الواحدة على هذا الجهاز، وترتيب الكاميرات.",
            "How many cameras on one page on this device, and the order of the cameras.")))
        val per = Prefs.perPage(this)
        val perBtn = Ui.button(this, t("عدد الكاميرات في الصفحة: ", "Cameras per page: ") + per + "  ▾") { pickPerPage() }
        perBtn.tag = "perPage"
        grid.addView(perBtn, Ui.below(this, 8))
        if (per > 6) {
            grid.addView(note(t("إذا بقيت بعض المربعات سوداء أو ظهرت فيها رسالة خطأ، اختر عددًا أصغر: بعض الأجهزة لا تتحمّل فيديوهات كثيرة معًا.",
                "If some squares stay black or show an error, choose fewer: some devices cannot play many videos at once.")), Ui.below(this, 6))
        }
        grid.addView(Ui.button(this, t("☰ ترتيب الكاميرات", "☰ Arrange cameras")) {
            startActivity(Intent(this, ArrangeActivity::class.java))
        }, Ui.below(this, 8))

        buildAlerts()

        // lock
        val lock = section(t("قفل التطبيق برمز", "App lock (PIN)"))
        lock.addView(note(t("اختياري: يُطلب الرمز عند فتح التطبيق وبعد الخروج منه لأكثر من 30 ثانية.",
            "Optional: the PIN is asked for when the app opens and after it has been in the background for more than 30 seconds.")))
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
        move.addView(note(t("إلى التلفزيون أو إلى جوال أحد أفراد العائلة، بدون كتابة العناوين وكلمات المرور.",
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
            cam.addView(note(t("حوّل جوالًا قديمًا إلى كاميرا مراقبة تظهر في التطبيق على باقي الأجهزة، ويمكنك التحدّث من خلاله.",
                "Turn an old phone into a camera the other devices see in the app, and talk through it.")))
            cam.addView(Ui.button(this, t("اجعل هذا الجوال كاميرا", "Make this phone a camera")) {
                startActivity(Intent(this, CameraModeActivity::class.java))
            }, Ui.below(this, 8))
        }

        buildAway()
        buildUpdate()

        // back on the option just chosen (the rebuild removed the button the remote / TalkBack was on)
        val tag = focusTag
        focusTag = null
        if (tag != null) col.post {
            val v: View? = col.findViewWithTag(tag)
            if (v != null) {
                v.requestFocus()
                v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
        }
    }

    // «15 دقيقة» / "15 minutes"
    private fun minutes(n: Int) = t(Ui.count(n, "دقيقة واحدة", "دقيقتان", "دقائق", "دقيقة"), if (n == 1) "1 minute" else "$n minutes")

    // the city of the prayer times (Riyadh by default)
    private fun pickCity() {
        val all = Prayer.CITIES
        AlertDialog.Builder(this)
            .setTitle(t("المدينة", "City"))
            .setSingleChoiceItems(all.map { it.label(this) }.toTypedArray(), all.indexOf(Prayer.city(this))) { d, which ->
                d.dismiss()
                Prefs.put(this, "prayerCity", all[which].key)
                focusTag = "prayerCity"
                build()
            }
            .setNegativeButton(t("إلغاء", "Cancel"), null)
            .show()
    }

    // 1 … 16 cameras on one page of «all cameras»
    private fun pickPerPage() {
        val labels = Array(16) { Ui.cameras(this, it + 1) }
        AlertDialog.Builder(this)
            .setTitle(t("عدد الكاميرات في الصفحة", "Cameras per page"))
            .setSingleChoiceItems(labels, Prefs.perPage(this) - 1) { d, which ->
                d.dismiss()
                Prefs.put(this, "perPage", which + 1)
                focusTag = "perPage"
                build()
            }
            .setNegativeButton(t("إلغاء", "Cancel"), null)
            .show()
    }

    // «10 ثوانٍ», «15 ثانية» / "10 seconds"
    private fun seconds(n: Int) = t(Ui.count(n, "ثانية واحدة", "ثانيتان", "ثوانٍ", "ثانية"), if (n == 1) "1 second" else "$n seconds")

    // ---------------------------------------------------------------- alerts
    private fun buildAlerts() {
        val s = section(t("تنبيهات الحركة", "Motion alerts"))
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

        // notifications blocked for this app: the alerts cannot appear on the phone
        if (!tv && Prefs.alertsOn(this) && !notificationsOn()) {
            s.addView(note(t("الإشعارات متوقفة لهذا التطبيق، فلن تظهر التنبيهات.",
                "Notifications are off for this app, so the alerts will not appear.")), Ui.below(this, 12))
            s.addView(Ui.button(this, t("تشغيل الإشعارات", "Turn on notifications")) { openNotificationSettings() }, Ui.below(this, 8))
        }

        // the cameras to watch: only the makes that can send motion events (ONVIF)
        s.addView(Ui.heading(Ui.text(this, t("الكاميرات المراقَبة", "Watched cameras"), 15f, Ui.TEXT, true)), Ui.below(this, 14))
        val able = Store.all(this).filter { it.brand.onvif }
        if (able.isEmpty()) {
            s.addView(note(t("لا توجد بعد كاميرا تدعم تنبيه الحركة (ONVIF).", "No camera that supports motion alerts (ONVIF) yet.")), Ui.below(this, 6))
        }
        for (cam in able) {
            val box = Ui.check(this, cam.name.ifBlank { cam.where() }, cam.alert)
            box.setOnCheckedChangeListener { _, on ->
                // read it again: only the alert changes
                val fresh = Store.get(this, cam.id)
                if (fresh != null) {
                    fresh.alert = on
                    Store.put(this, fresh)
                }
            }
            s.addView(box, Ui.below(this, 2))
        }

        // hours
        s.addView(Ui.heading(Ui.text(this, t("الأوقات", "Hours"), 15f, Ui.TEXT, true)), Ui.below(this, 14))
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

        // prayer time: for some minutes from each adhan the alert comes silently (phone) / no pop-up (TV)
        s.addView(Ui.heading(Ui.text(this, t("وقت الصلاة", "Prayer time"), 15f, Ui.TEXT, true)), Ui.below(this, 14))
        s.addView(note(
            if (tv) t("لا تظهر الكاميرا فوق التطبيقات لعدة دقائق من كل أذان.",
                "The camera does not pop up over other apps for some minutes from each adhan.")
            else t("يصل التنبيه بصمت، بدون صوت، لعدة دقائق من كل أذان، وتجده في الإشعارات.",
                "The alert comes silently, without sound, for some minutes from each adhan; you find it in the notifications.")
        ), Ui.below(this, 4))
        val quietOn = Prefs.prayerQuiet(this)
        s.addView(
            choice(listOf("pq_on" to t("تشغيل", "On"), "pq_off" to t("إيقاف", "Off")), if (quietOn) "pq_on" else "pq_off") { v ->
                Prefs.put(this, "prayerQuiet", v == "pq_on")
                build()
            },
            Ui.below(this, 8),
        )
        if (quietOn) {
            s.addView(
                choice(listOf(15, 20, 30, 45).map { it.toString() to minutes(it) }, Prefs.prayerMin(this).toString()) { v ->
                    Prefs.put(this, "prayerMin", v.toInt())
                    build()
                },
                Ui.below(this, 8),
            )
            s.addView(note(t("ويوم الجمعة ساعة على الأقل، لخطبة الجمعة والصلاة.", "On Friday at least an hour, for the sermon and the prayer.")), Ui.below(this, 6))
            val cityBtn = Ui.button(this, t("المدينة: ", "City: ") + Prayer.city(this).label(this) + "  ▾") { pickCity() }
            cityBtn.tag = "prayerCity"
            s.addView(cityBtn, Ui.below(this, 8))
            val today = Prayer.today(this)
            if (today.isNotEmpty()) s.addView(note(t("أذان اليوم: ", "Today's adhans: ") + today), Ui.below(this, 6))
        }

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

    private fun notificationsOn(): Boolean = try {
        getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: true
    } catch (_: Exception) {
        true
    }

    // the system page where he can turn this app's notifications on
    private fun openNotificationSettings() {
        val i = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        try {
            startActivity(i)
        } catch (_: Exception) {
        }
    }

    private fun pickHours() {
        if (tv) {
            pickHoursTv()
            return
        }
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

    // on the TV: two short lists of whole hours, easy with the remote (from, then until)
    private fun pickHoursTv() {
        val hours = Array(24) { hhmm(it * 60) }
        AlertDialog.Builder(this)
            .setTitle(t("من الساعة", "From"))
            .setItems(hours) { _, f ->
                AlertDialog.Builder(this)
                    .setTitle(t("إلى الساعة", "Until"))
                    .setItems(hours) { _, u ->
                        Prefs.put(this, "alertFrom", f * 60)
                        Prefs.put(this, "alertTo", u * 60)
                        Alerts.refresh(this)
                        build()
                    }
                    .show()
            }
            .show()
    }

    private fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    // ---------------------------------------------------------------- updates
    // watching a camera or a recorder in another house (or while travelling)
    private fun buildAway() {
        val s = section(t("المشاهدة من خارج البيت", "Watching from outside the home"))
        s.addView(note(t(
            "التطبيق يعمل داخل شبكة الكاميرا مباشرة. لمشاهدتها من بعيد (مثل مسجّل إربد) اكتب لها «العنوان من خارج البيت» في شاشة تعديلها، والتطبيق يختار وحده.\n\n" +
                "الطريقة الأأمن: Tailscale (VPN مجاني): ثبّته على جهاز يبقى شغّالًا هناك (تلفزيون أندرويد أو جوال قديم على الشاحن) وفعّل فيه «Subnet router» لشبكة المسجّل، وثبّته على جوالك وتلفزيونك بنفس الحساب. بعدها يعمل عنوان المسجّل العادي من أي مكان، بشرط ألّا تكون شبكتا البيتين بنفس الأرقام (مثل 192.168.1.x في الاثنين).\n\n" +
                "الطريقة الأسهل والأقل أمانًا: فتح المنافذ في راوتر ذلك البيت (RTSP 554 والويب 80) مع اسم DDNS وكلمة مرور قوية للمسجّل.",
            "The app works directly on the camera's own network. To watch it from far away (e.g. the Irbid recorder) type its “Address from outside” in its edit screen; the app chooses by itself.\n\n" +
                "Safest: Tailscale (free VPN): install it on a device that stays on there (an Android TV or an old phone on its charger), enable “Subnet router” for the recorder's network, install it on your phone and TV with the same account. The recorder's normal address then works from anywhere, as long as the two houses do not use the same network numbers (e.g. 192.168.1.x in both).\n\n" +
                "Easiest but less safe: forward ports on that house's router (RTSP 554 and web 80) with a DDNS name and a strong recorder password.",
        )))
    }

    private fun buildUpdate() {
        val s = section(t("التحديث", "Updates"))
        updateSection = s
        val mine = Update.versionNumber(this)
        s.addView(Ui.text(this, t("نسختك: $mine", "Your version: $mine"), 17f, Ui.TEXT, true))
        val status = note(t("جارٍ التحقق من GitHub…", "Checking GitHub…"))
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
        if (downloadId >= 0) {
            // downloading: poll() keeps the progress on this line
        } else if (i.latest == 0) {
            status.text = t("تعذّر الوصول إلى GitHub. تأكد من الإنترنت.", "Could not reach GitHub. Check the internet.")
        } else {
            val stableText = if (i.stable > 0) t("النسخة المستقرة: ${i.stable}", "Stable version: ${i.stable}")
            else t("لا توجد نسخة مستقرة بعد", "No stable version yet")
            status.text = t("أحدث نسخة: ${i.latest}", "Newest version: ${i.latest}") + "\n" + stableText +
                (if (i.latest <= mine) t("\n✓ عندك أحدث نسخة", "\n✓ You have the newest version") else "")
        }
        if (i.latest > mine) buttons.addView(Ui.button(this, t("⬆ تثبيت أحدث نسخة (${i.latest})", "⬆ Install newest version (${i.latest})"), primary = true) {
            download(i.latest)
        }, Ui.below(this, 8))
        if (i.stable > mine && i.stable != i.latest) buttons.addView(Ui.button(this, t("تثبيت النسخة المستقرة (${i.stable})", "Install stable version (${i.stable})")) {
            download(i.stable)
        }, Ui.below(this, 8))
        buttons.addView(Ui.button(this, t("إعادة الفحص", "Check again")) {
            info = null
            status.text = t("جارٍ التحقق من GitHub…", "Checking GitHub…")
            thread {
                val n = Update.check()
                info = n
                runOnUiThread { if (!isFinishing) showUpdate(n) }
            }
        }, Ui.below(this, 8))
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun download(version: Int) {
        if (downloadId >= 0) return
        when (val id = Update.start(this, version)) {
            Update.NEEDS_PERMISSION -> {
                // Update.start opened the system page; back here, onResume starts the download
                pendingVersion = version
                toast(t("اسمح بتثبيت التحديثات ثم ارجع، وسيبدأ التحديث وحده", "Allow installing updates, then come back: the update starts by itself"))
            }
            Update.FAILED -> toast(t("تعذّر بدء التنزيل.", "Could not start the download."))
            else -> {
                downloadId = id
                Prefs.putLong(this, "updId", id)
                poll()
            }
        }
    }

    private fun poll() {
        handler.removeCallbacks(pollTask)
        val id = downloadId
        if (id < 0) return
        val pct = Update.progress(this, id)
        if (pct < 0) {
            forgetDownload()
            updateStatus?.text = t("تعذّر تنزيل التحديث. تأكد من الإنترنت.", "The download failed. Check the internet.")
        } else if (pct >= 100) {
            // the installer opens only while this screen is in front; otherwise when he comes back (onResume)
            if (resumed) {
                forgetDownload()
                Update.install(this, id)
            }
        } else {
            updateStatus?.text = t("جارٍ التنزيل… ${pct}٪", "Downloading… ${pct}%")
            handler.postDelayed(pollTask, 700)
        }
    }

    private fun forgetDownload() {
        downloadId = -1L
        Prefs.putLong(this, "updId", -1L)
    }

    // ---------------------------------------------------------------- helpers
    private fun section(title: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 16)
        box.setPadding(p, p, p, p)
        box.background = Ui.round(Ui.CARD, Ui.dp(this, 16).toFloat())
        box.addView(Ui.heading(Ui.text(this, title, 18f, Ui.GOLD, true)))
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
        val keys = options.joinToString { it.first }
        for ((key, label) in options) {
            val on = key == current
            val tag = "$keys:$key" // the same option has the same tag after a rebuild
            val b = Ui.button(this, (if (on) "✓ " else "") + label, primary = on) {
                focusTag = tag
                onPick(key)
            }
            b.tag = tag
            b.textSize = 15f * Ui.scale(this)
            b.contentDescription = label + if (on) t("، مختار", ", selected") else ""
            row.addView(b, Ui.share(this, 4))
        }
        return row
    }

    private fun setPin() = startActivity(Intent(this, LockActivity::class.java).putExtra(LockActivity.SET, true))

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
