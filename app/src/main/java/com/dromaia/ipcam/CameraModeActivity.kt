package com.dromaia.ipcam

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/**
 * «اجعل هذا الجوال كاميرا»: the picture of this phone's camera, start / stop, back or front camera, quality, sound,
 * what the other devices need (name, address, user ipcam, password), dimming the screen, and who is watching.
 * The screen turns so that the picture sent is upright when this screen reads upright.
 */
class CameraModeActivity : Base() {
    companion object {
        private const val REQ_START = 41
        private const val REQ_MIC = 42
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var body: LinearLayout
    private lateinit var previewBox: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var placeholder: TextView
    private lateinit var scroll: ScrollView
    private lateinit var col: LinearLayout
    private var status: TextView? = null
    private var startBtn: TextView? = null
    private var dimBtn: TextView? = null
    private var addrText: TextView? = null
    private var viewersBox: LinearLayout? = null
    private var dimView: View? = null
    private var dimmed = false
    private var visible = false
    private var pendingStart = false

    private val tick = object : Runnable {
        override fun run() {
            live()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (tv) {
            buildTv()
            return
        }
        lockOrientation()
        build()
    }

    override fun onStart() {
        super.onStart()
        if (tv) return
        visible = true
        CameraService.listener = { runOnUiThread { live() } }
        attachPreview()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        if (tv) return
        if (pendingStart) {
            pendingStart = false
            startNow()
        }
        live()
    }

    override fun onStop() {
        if (!tv) {
            visible = false
            CameraService.listener = null
            CameraService.setPreview(null) // the camera keeps sending, only the picture on this screen stops
            handler.removeCallbacks(tick)
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (tv || !::body.isInitialized) return
        arrange()
        texture.post { fit() }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (dimmed) dimOff() else super.onBackPressed()
    }

    // ------------------------------------------------------------------ TV

    private fun buildTv() {
        val sc = ScrollView(this)
        sc.setBackgroundColor(Ui.BG)
        sc.isFillViewport = true
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 48)
        c.setPadding(p, p, p, p)
        sc.addView(c)
        c.addView(Ui.text(this, t("الجوال كاميرا", "Phone as a camera"), 26f, Ui.TEXT, true))
        c.addView(note(t(
            "هذه الميزة للجوال وليست للتلفزيون: افتح «كاميرات البيت» على جوال قديم ← الإعدادات ← «اجعل هذا الجوال كاميرا»، ثم ابحث عنه هنا من «بحث في الشبكة».",
            "This is for phones, not the TV: open “Home Cameras” on an old phone → Settings → “Make this phone a camera”, then find it here with “Search the network”.",
        )), Ui.below(this, 16))
        val back = Ui.button(this, t("رجوع", "Back"), primary = true) { finish() }
        c.addView(back, Ui.below(this, 24))
        setContentView(sc)
        back.requestFocus()
    }

    // ------------------------------------------------------------------ layout

    private fun build() {
        root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        body = LinearLayout(this)
        root.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))

        previewBox = FrameLayout(this)
        previewBox.setBackgroundColor(Color.BLACK)
        texture = TextureView(this)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                attachPreview()
                fit()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                fit()
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                // released only after the camera stopped drawing into it
                CameraService.setPreview(null) {
                    try { st.release() } catch (_: Exception) {}
                }
                return false
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        previewBox.addView(texture, FrameLayout.LayoutParams(MATCH, MATCH))
        placeholder = Ui.text(this, "", 15f, Ui.MUTED)
        placeholder.gravity = Gravity.CENTER
        val pp = Ui.dp(this, 16)
        placeholder.setPadding(pp, pp, pp, pp)
        previewBox.addView(placeholder, FrameLayout.LayoutParams(MATCH, MATCH))

        scroll = ScrollView(this)
        scroll.isFillViewport = true
        col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 20)
        col.setPadding(p, p, p, p)
        scroll.addView(col)

        body.addView(previewBox)
        body.addView(scroll)
        arrange()
        buildPanel()
        setContentView(root)
    }

    /** Side by side when the phone lies sideways, one above the other when it stands. */
    private fun arrange() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        body.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (land) {
            previewBox.layoutParams = LinearLayout.LayoutParams(0, MATCH, 1.1f)
            scroll.layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
        } else {
            previewBox.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 0.8f)
            scroll.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
    }

    private fun buildPanel() {
        col.removeAllViews()
        col.addView(Ui.text(this, t("اجعل هذا الجوال كاميرا", "Make this phone a camera"), 24f, Ui.TEXT, true))
        val st = Ui.text(this, "", 15f, Ui.MUTED)
        st.setLineSpacing(0f, 1.2f)
        status = st
        col.addView(st, Ui.below(this, 6))
        val sb = Ui.button(this, "", primary = true) { toggle() }
        startBtn = sb
        col.addView(sb, Ui.below(this, 14))

        // which camera
        val front = Prefs.bool(this, CameraService.K_FRONT, false)
        if (CameraService.hasSide(this, false) && CameraService.hasSide(this, true)) {
            col.addView(label(t("الكاميرا", "Camera")), Ui.below(this, 18))
            col.addView(choice(listOf("back" to t("الخلفية", "Back"), "front" to t("الأمامية", "Front")), if (front) "front" else "back") { v ->
                setFront(v == "front")
            }, Ui.below(this, 8))
        }

        // quality
        col.addView(label(t("الجودة", "Quality")), Ui.below(this, 18))
        val q = Prefs.int(this, CameraService.K_QUALITY, 720)
        col.addView(choice(listOf("480" to "480p", "720" to "720p", "1080" to "1080p"), q.toString()) { v ->
            setQuality(v.toInt())
        }, Ui.below(this, 8))
        col.addView(note(t("720p تناسب أغلب البيوت. 1080p أوضح لكنها تحتاج واي فاي أقوى.", "720p suits most homes. 1080p is sharper but needs stronger Wi-Fi.")), Ui.below(this, 6))

        // sound
        col.addView(label(t("الصوت", "Sound")), Ui.below(this, 18))
        val sound = Prefs.bool(this, CameraService.K_SOUND, true)
        col.addView(choice(listOf("on" to t("يعمل", "On"), "off" to t("مغلق", "Off")), if (sound) "on" else "off") { v ->
            setSound(v == "on")
        }, Ui.below(this, 8))

        // what the other devices need
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val cp = Ui.dp(this, 16)
        card.setPadding(cp, cp, cp, cp)
        card.background = Ui.round(Ui.CARD, Ui.dp(this, 14).toFloat())
        card.addView(label(t("للأجهزة الأخرى", "For the other devices")))
        card.addView(row(t("اسم الجوال", "Phone name"), Device.name(this)), Ui.below(this, 8))
        val a = row(t("العنوان", "Address"), "")
        addrText = a.getChildAt(1) as? TextView
        card.addView(a, Ui.below(this, 6))
        card.addView(row(t("اسم المستخدم", "User name"), CameraService.USER), Ui.below(this, 6))
        card.addView(row(t("كلمة المرور", "Password"), CameraService.password(this)), Ui.below(this, 6))
        val change = Ui.button(this, t("تغيير كلمة المرور", "Change password")) { changePassword() }
        change.textSize = 14f * Ui.scale(this)
        card.addView(change, Ui.below(this, 10))
        col.addView(card, Ui.below(this, 18))

        col.addView(note(t(
            "افتح «كاميرات البيت» على جوالك أو التلفزيون ← بحث في الشبكة، وسيظهر هذا الجوال.",
            "Open “Home Cameras” on your phone or TV → Search the network, and this phone will appear.",
        )), Ui.below(this, 14))
        col.addView(note(t(
            "ثبّت الجوال بحيث تُقرأ هذه الشاشة معتدلة، فتصل الصورة معتدلة كما تراها هنا. وأبقِه موصولًا بالشاحن.",
            "Mount the phone so this screen reads upright; the picture then arrives upright as you see it here. Keep it on the charger.",
        )), Ui.below(this, 8))

        val db = Ui.button(this, t("تعتيم الشاشة", "Dim the screen")) { dimOn() }
        dimBtn = db
        col.addView(db, Ui.below(this, 14))
        col.addView(note(t("تبقى الكاميرا تعمل والشاشة سوداء. المس الشاشة للرجوع.", "The camera keeps working with a black screen. Touch the screen to come back.")), Ui.below(this, 4))

        col.addView(label(t("من يشاهد الآن", "Who is watching")), Ui.below(this, 18))
        val vb = LinearLayout(this)
        vb.orientation = LinearLayout.VERTICAL
        viewersBox = vb
        col.addView(vb, Ui.below(this, 6))

        live()
    }

    private fun label(s: String): TextView = Ui.text(this, s, 15f, Ui.GOLD, true)

    private fun note(s: String): TextView {
        val v = Ui.text(this, s, 14f, Ui.MUTED)
        v.setLineSpacing(0f, 1.25f)
        return v
    }

    /** "Name:  value" on one line. */
    private fun row(name: String, value: String): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.addView(Ui.text(this, "$name:  ", 15f, Ui.MUTED))
        val v = Ui.text(this, value, 16f, Ui.TEXT, true)
        v.textDirection = View.TEXT_DIRECTION_LTR // addresses and passwords read left to right
        v.setTextIsSelectable(true)
        r.addView(v)
        return r
    }

    private fun choice(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        for ((key, text) in options) {
            val on = key == current
            val b = Ui.button(this, (if (on) "✓ " else "") + text, primary = on) { if (key != current) onPick(key) }
            b.textSize = 15f * Ui.scale(this)
            b.contentDescription = text + if (on) t("، مختار", ", selected") else ""
            r.addView(b, Ui.share(this, 4))
        }
        return r
    }

    /** The parts that change while the screen is open: state, address, viewers. */
    private fun live() {
        if (tv) return
        val st = status ?: return
        val on = CameraService.running
        val ok = on && CameraService.streaming
        val problem = CameraService.problem
        var s = when {
            ok -> t("الكاميرا تعمل، ويمكن للأجهزة الأخرى مشاهدتها.", "The camera is on; the other devices can watch it.")
            problem.isNotEmpty() -> problem
            on -> t("جارٍ تشغيل الكاميرا…", "Starting the camera…")
            else -> t("وضع الكاميرا متوقف.", "Camera mode is off.")
        }
        if (ok && Prefs.bool(this, CameraService.K_SOUND, true) && !CameraService.soundNow) {
            s += "\n" + t("تعذّر تشغيل الميكروفون، فالبث بلا صوت.", "The microphone could not start, so the stream has no sound.")
        }
        st.text = s
        st.setTextColor(when {
            ok -> Ui.GREEN
            problem.isNotEmpty() -> Ui.RED
            else -> Ui.MUTED
        })
        startBtn?.text = if (on) t("إيقاف وضع الكاميرا", "Stop camera mode") else t("تشغيل وضع الكاميرا", "Start camera mode")
        dimBtn?.visibility = if (on) View.VISIBLE else View.GONE

        placeholder.visibility = if (ok) View.GONE else View.VISIBLE
        placeholder.text = if (on) t("جارٍ تشغيل الكاميرا…", "Starting the camera…")
        else t("تظهر صورة الكاميرا هنا بعد التشغيل.", "The camera picture appears here once started.")

        val ip = PhoneCams.localIp()
        val port = if (CameraService.port > 0) CameraService.port else 8554
        addrText?.text = if (ip == null) t("غير متصل بالواي فاي", "Not connected to Wi-Fi") else "$ip:$port"

        val box = viewersBox ?: return
        box.removeAllViews()
        val talker = CameraService.talker
        val list = CameraService.viewers().distinctBy { it.ip }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)
        for (v in list) {
            val who = if (v.name.isNotEmpty()) "${v.name} (${v.ip})" else v.ip
            val since = time.format(Date(v.since))
            var line = "• $who  —  " + t("منذ الساعة $since", "since $since")
            if (v.ip == talker) line += "  —  " + t("يتكلم الآن", "talking now")
            box.addView(Ui.text(this, line, 15f, if (v.ip == talker) Ui.GOLD else Ui.TEXT), Ui.below(this, 4))
        }
        if (talker.isNotEmpty() && list.none { it.ip == talker }) {
            box.addView(Ui.text(this, "• $talker  —  " + t("يتكلم الآن", "talking now"), 15f, Ui.GOLD), Ui.below(this, 4))
        }
        if (list.isEmpty() && talker.isEmpty()) {
            box.addView(note(if (on) t("لا أحد يشاهد الآن.", "Nobody is watching now.") else "—"))
        }
        fit()
    }

    // ------------------------------------------------------------------ actions

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun toggle() {
        if (CameraService.running) {
            CameraService.stop(this)
            handler.postDelayed({ live() }, 300)
            return
        }
        val sound = Prefs.bool(this, CameraService.K_SOUND, true)
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (sound && !granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (need.isEmpty()) {
            startNow()
            return
        }
        // the camera mode notification (Android 13+) is asked together, so it is one visit to the permissions
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val msg = if (sound) t(
            "حتى يعمل هذا الجوال كاميرا يحتاج التطبيق إذن الكاميرا، وإذن الميكروفون لنقل الصوت. تبقى الصورة داخل شبكة البيت، ولا يشاهدها إلا جهاز يعرف كلمة المرور.",
            "To work as a camera, the app needs the camera, and the microphone to send sound. The picture stays inside your home network, and only a device that knows the password can watch it.",
        ) else t(
            "حتى يعمل هذا الجوال كاميرا يحتاج التطبيق إذن الكاميرا. تبقى الصورة داخل شبكة البيت، ولا يشاهدها إلا جهاز يعرف كلمة المرور.",
            "To work as a camera, the app needs the camera. The picture stays inside your home network, and only a device that knows the password can watch it.",
        )
        AlertDialog.Builder(this)
            .setTitle(t("أذونات وضع الكاميرا", "Camera mode permissions"))
            .setMessage(msg)
            .setPositiveButton(t("متابعة", "Continue")) { _, _ -> requestPermissions(need.toTypedArray(), REQ_START) }
            .setNegativeButton(t("إلغاء", "Cancel"), null)
            .show()
    }

    private fun startNow() {
        if (!granted(Manifest.permission.CAMERA)) return
        CameraService.password(this)
        if (!CameraService.start(this)) toast(t("تعذّر تشغيل وضع الكاميرا. حاول مرة أخرى.", "Could not start camera mode. Try again."))
        attachPreview()
        handler.postDelayed({ live() }, 400)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_START -> {
                // started in onResume: Android allows the camera only while this screen is in front
                if (granted(Manifest.permission.CAMERA)) pendingStart = true
                else refused(Manifest.permission.CAMERA, t("الكاميرا", "Camera"))
            }
            REQ_MIC -> {
                if (granted(Manifest.permission.RECORD_AUDIO)) {
                    Prefs.put(this, CameraService.K_SOUND, true)
                    buildPanel()
                    CameraService.restart(this)
                } else {
                    refused(Manifest.permission.RECORD_AUDIO, t("الميكروفون", "Microphone"))
                }
            }
        }
    }

    /** A permission was refused: explain, and if Android will not ask again, offer the app's settings. */
    private fun refused(perm: String, name: String) {
        if (shouldShowRequestPermissionRationale(perm)) {
            toast(t("بدون إذن «$name» لا يعمل وضع الكاميرا.", "Camera mode needs the “$name” permission."))
            return
        }
        AlertDialog.Builder(this)
            .setMessage(t("إذن «$name» مرفوض. افتح إعدادات التطبيق ← الأذونات، واسمح به.", "The “$name” permission is refused. Open the app settings → Permissions, and allow it."))
            .setPositiveButton(t("فتح الإعدادات", "Open settings")) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                } catch (_: Exception) {
                }
            }
            .setNegativeButton(t("إلغاء", "Cancel"), null)
            .show()
    }

    private fun setFront(front: Boolean) {
        Prefs.put(this, CameraService.K_FRONT, front)
        lockOrientation()
        buildPanel()
        CameraService.restart(this)
    }

    private fun setQuality(q: Int) {
        Prefs.put(this, CameraService.K_QUALITY, q)
        buildPanel()
        CameraService.restart(this)
    }

    private fun setSound(on: Boolean) {
        if (on && !granted(Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this)
                .setTitle(t("الصوت", "Sound"))
                .setMessage(t("لنقل الصوت يحتاج التطبيق إذن الميكروفون. يسمعه فقط من يشاهد هذه الكاميرا داخل البيت.",
                    "To send sound, the app needs the microphone. Only those watching this camera at home hear it."))
                .setPositiveButton(t("متابعة", "Continue")) { _, _ -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) }
                .setNegativeButton(t("إلغاء", "Cancel"), null)
                .show()
            return
        }
        Prefs.put(this, CameraService.K_SOUND, on)
        buildPanel()
        CameraService.restart(this)
    }

    private fun changePassword() {
        val f = Ui.field(this, t("حروف إنجليزية أو أرقام، 4 على الأقل", "Letters or digits, at least 4"),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        f.setText(CameraService.newPassword())
        f.textDirection = View.TEXT_DIRECTION_LTR
        val box = FrameLayout(this)
        val p = Ui.dp(this, 20)
        box.setPadding(p, Ui.dp(this, 8), p, 0)
        box.addView(f)
        AlertDialog.Builder(this)
            .setTitle(t("كلمة مرور جديدة", "New password"))
            .setMessage(t("بعد التغيير، اكتب كلمة المرور الجديدة لهذه الكاميرا على الأجهزة الأخرى («تعديل الكاميرا»).",
                "After changing it, type the new password for this camera on the other devices (“Edit camera”)."))
            .setView(box)
            .setPositiveButton(t("حفظ", "Save")) { _, _ ->
                val v = f.text.toString().trim()
                if (v.length < 4 || v.length > 32 || !v.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }) {
                    toast(t("كلمة المرور: 4 حروف إنجليزية أو أرقام على الأقل، بلا مسافات.", "Password: at least 4 letters or digits, no spaces."))
                } else {
                    Prefs.put(this, CameraService.K_PASS, v)
                    buildPanel()
                }
            }
            .setNegativeButton(t("إلغاء", "Cancel"), null)
            .show()
    }

    // ------------------------------------------------------------------ dimming

    @Suppress("DEPRECATION")
    private fun dimOn() {
        if (dimmed) return
        dimmed = true
        val d = FrameLayout(this)
        d.setBackgroundColor(Color.BLACK)
        d.isClickable = true
        d.isFocusable = true
        d.contentDescription = t("المس الشاشة للرجوع", "Touch the screen to come back")
        val hint = Ui.text(this, t("المس الشاشة للرجوع", "Touch the screen to come back"), 14f, 0xFF3A3F4A.toInt())
        hint.gravity = Gravity.CENTER
        d.addView(hint, FrameLayout.LayoutParams(MATCH, MATCH))
        d.setOnClickListener { dimOff() }
        root.addView(d, FrameLayout.LayoutParams(MATCH, MATCH))
        dimView = d
        handler.postDelayed({ hint.visibility = View.GONE }, 4000)
        val lp = window.attributes
        lp.screenBrightness = 0.01f
        window.attributes = lp
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        d.requestFocus()
        CameraService.setPreview(null) // nothing to draw on a black screen
    }

    @Suppress("DEPRECATION")
    private fun dimOff() {
        if (!dimmed) return
        dimmed = false
        dimView?.let { root.removeView(it) }
        dimView = null
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = 0
        attachPreview()
        live()
    }

    // ------------------------------------------------------------------ the picture on this screen

    private fun attachPreview() {
        if (tv || dimmed || !visible || !::texture.isInitialized) return
        val st = texture.surfaceTexture ?: return
        if (texture.isAvailable) CameraService.setPreview(st)
    }

    @Suppress("DEPRECATION")
    private fun rotation(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /**
     * The phone sends the camera's picture as the sensor sees it. Turn this screen so that the picture is upright
     * when the screen reads upright: then what is shown here is what the others see.
     */
    private fun lockOrientation() {
        val lens = CameraService.lens(this, Prefs.bool(this, CameraService.K_FRONT, false)) ?: return
        val need = if (lens.front) (360 - lens.sensor % 360) % 360 else lens.sensor % 360
        val rot = rotation()
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val naturalPortrait = if (rot % 180 == 0) !land else land
        requestedOrientation = if (naturalPortrait) {
            when (need) {
                90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        } else {
            when (need) {
                90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                270 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
    }

    /** Shows the camera picture upright and whole (not stretched) inside the preview, as the others see it. */
    private fun fit() {
        if (tv || !::texture.isInitialized) return
        val vw = texture.width.toFloat()
        val vh = texture.height.toFloat()
        val bw = CameraService.width
        val bh = CameraService.height
        if (vw <= 0f || vh <= 0f || bw <= 0 || bh <= 0) return
        val rot = rotation()
        // the picture as the camera gives it to the screen: upright for the phone held in its natural position
        var nw = bw.toFloat()
        var nh = bh.toFloat()
        if (CameraService.sensor % 180 == 90) {
            nw = bh.toFloat()
            nh = bw.toFloat()
        }
        val side = rot % 180 == 90
        val uw = if (side) nh else nw
        val uh = if (side) nw else nh
        val s = minOf(vw / uw, vh / uh)
        val fw = uw * s
        val fh = uh * s
        val cx = vw / 2f
        val cy = vh / 2f
        val m = Matrix()
        if (side) m.setScale(fh / vw, fw / vh, cx, cy) else m.setScale(fw / vw, fh / vh, cx, cy)
        if (rot != 0) m.postRotate(-rot.toFloat(), cx, cy)
        if (CameraService.isFront) m.postScale(-1f, 1f, cx, cy) // the front camera's preview is a mirror; the stream is not
        texture.setTransform(m)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
