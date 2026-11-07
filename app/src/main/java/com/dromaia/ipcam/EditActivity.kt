package com.dromaia.ipcam

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Add a camera or change one. Extras: "id" (change) or "host" / "brand" / "name" / "port" (from the network search). */
class EditActivity : Base() {
    private lateinit var cam: Camera
    private var isNew = true
    private var brand = Brand.HIKVISION

    private lateinit var nameField: EditText
    private lateinit var groupField: EditText
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var channelField: EditText
    private lateinit var urlField: EditText
    private lateinit var onvifPortField: EditText
    private lateinit var onvifUserField: EditText
    private lateinit var onvifPassField: EditText
    private lateinit var ptzBox: CheckBox
    private lateinit var alertBox: CheckBox
    private lateinit var popBox: CheckBox
    private lateinit var hint: TextView
    private lateinit var preview: TextView
    private lateinit var advanced: LinearLayout
    private val brandButtons = HashMap<Brand, TextView>()
    private val hostRows = ArrayList<View>()    // address, port, user, password: not for «RTSP link»
    private val channelRows = ArrayList<View>() // channel: Hikvision and Dahua only
    private val urlRows = ArrayList<View>()     // only for «RTSP link»
    private val onvifRows = ArrayList<View>()   // ONVIF options: makes that speak ONVIF

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)

        val existing = intent.getStringExtra("id")?.let { Store.get(this, it) }
        isNew = existing == null
        cam = existing ?: Camera().also { c ->
            c.host = intent.getStringExtra("host") ?: ""
            c.name = intent.getStringExtra("name") ?: ""
            c.brand = Brand.of(intent.getStringExtra("brand")) ?: Brand.HIKVISION
            c.port = intent.getIntExtra("port", 0).let { if (it > 0) it else c.brand.port }
            if (c.brand == Brand.PHONE) c.user = "ipcam"
            if (c.brand == Brand.IPWEBCAM) c.user = ""
        }
        brand = cam.brand

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, if (tv) 48 else 20)
        col.setPadding(p, p, p, p)
        scroll.addView(col)

        col.addView(Ui.text(this, if (isNew) t("إضافة كاميرا", "Add camera") else t("تعديل الكاميرا", "Edit camera"), 26f, Ui.TEXT, true))
        if (tv && isNew) {
            col.addView(note(t("أسهل: أرسل الكاميرات من جوالك (الإعدادات ← إرسال الكاميرات) بدل الكتابة بالريموت.",
                "Easier: send the cameras from your phone (Settings → Send cameras) instead of typing with the remote.")), Ui.below(this, 8))
        }

        // make of the camera: 6 buttons, 2 per row
        col.addView(label(t("نوع الكاميرا", "Camera type")), Ui.below(this, 18))
        var row: LinearLayout? = null
        Brand.values().forEachIndexed { i, b ->
            if (i % 2 == 0) {
                val r = LinearLayout(this)
                row = r
                col.addView(r, Ui.below(this, 8))
            }
            val btn = Ui.button(this, b.label(this)) { choose(b) }
            btn.textSize = 14f * Ui.scale(this)
            brandButtons[b] = btn
            row?.addView(btn, Ui.share(this, 4))
        }

        hint = note("")
        col.addView(hint, Ui.below(this, 12))

        nameField = Ui.field(this, t("مثل: الباب الأمامي", "e.g. Front door"))
        nameField.setText(cam.name)
        addRow(col, t("الاسم", "Name"), nameField, null)

        groupField = Ui.field(this, t("مثل: داخل البيت، الحوش (اختياري)", "e.g. Inside, Yard (optional)"))
        groupField.setText(cam.group)
        addRow(col, t("المجموعة", "Group"), groupField, null)
        val groups = Store.groups(this)
        if (groups.isNotEmpty()) {
            val chips = LinearLayout(this)
            for (g in groups.take(4)) {
                val b = Ui.button(this, g) { groupField.setText(g) }
                b.textSize = 13f * Ui.scale(this)
                b.minHeight = Ui.dp(this, 44)
                chips.addView(b, Ui.share(this, 3))
            }
            col.addView(chips, Ui.below(this, 6))
        }

        hostField = Ui.field(this, t("مثل: 192.168.1.64", "e.g. 192.168.1.64"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        hostField.setText(cam.host)
        addRow(col, t("عنوان الكاميرا (IP)", "Camera address (IP)"), hostField, hostRows)

        portField = Ui.field(this, "554", InputType.TYPE_CLASS_NUMBER)
        portField.setText(cam.port.toString())
        addRow(col, t("المنفذ", "Port"), portField, hostRows)

        userField = Ui.field(this, "admin")
        userField.setText(cam.user)
        addRow(col, t("اسم المستخدم", "User name"), userField, hostRows)

        passField = Ui.field(this, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        passField.setText(cam.pass)
        addRow(col, t("كلمة المرور", "Password"), passField, hostRows)
        val showPass = Ui.text(this, t("إظهار كلمة المرور", "Show password"), 14f, Ui.GOLD)
        showPass.isFocusable = true
        showPass.isClickable = true
        showPass.minHeight = Ui.dp(this, 44)
        showPass.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10))
        var passShown = false
        showPass.setOnClickListener {
            passShown = !passShown
            passField.inputType = InputType.TYPE_CLASS_TEXT or
                (if (passShown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            passField.setSelection(passField.text.length)
            showPass.text = if (passShown) t("إخفاء كلمة المرور", "Hide password") else t("إظهار كلمة المرور", "Show password")
        }
        col.addView(showPass, Ui.below(this, 0))
        hostRows.add(showPass)

        channelField = Ui.field(this, "1", InputType.TYPE_CLASS_NUMBER)
        channelField.setText(cam.channel.toString())
        addRow(col, t("رقم القناة (1 للكاميرا المفردة، أو رقم الكاميرا في المسجّل NVR)", "Channel (1 for a single camera, or the camera number on the NVR)"), channelField, channelRows)

        urlField = Ui.field(this, "rtsp://user:pass@192.168.1.50:554/stream", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        urlField.setText(cam.url)
        addRow(col, t("رابط البث RTSP كامل", "Full RTSP link"), urlField, urlRows)

        // alerts and ONVIF: in a part that opens
        alertBox = check(t("تنبيه الحركة لهذه الكاميرا", "Motion alert for this camera"), cam.alert)
        popBox = check(t("على التلفزيون: أظهرها فوق التطبيقات عند الحركة", "On the TV: show it over other apps on motion"), cam.popTv)
        ptzBox = check(t("كاميرا متحركة (تدور يمين ويسار وأعلى وأسفل)", "Moving camera (pan / tilt / zoom)"), cam.ptz)
        col.addView(alertBox, Ui.below(this, 18))
        col.addView(popBox, Ui.below(this, 2))
        col.addView(ptzBox, Ui.below(this, 2))
        onvifRows.add(alertBox); onvifRows.add(popBox); onvifRows.add(ptzBox)

        advanced = LinearLayout(this)
        advanced.orientation = LinearLayout.VERTICAL
        advanced.visibility = View.GONE
        val more = Ui.button(this, t("خيارات ONVIF (للتنبيه والتحريك) ▾", "ONVIF options (alerts and PTZ) ▾")) {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        more.textSize = 14f * Ui.scale(this)
        col.addView(more, Ui.below(this, 10))
        col.addView(advanced, Ui.below(this, 0))
        onvifRows.add(more); onvifRows.add(advanced)
        advanced.addView(note(t(
            "التنبيه والتحريك يعملان عبر ONVIF. Hikvision: فعّل ONVIF من صفحة الكاميرا (الشبكة ← متقدم ← Integration Protocol) وأنشئ مستخدم ONVIF واكتبه هنا، وفي إعدادات «كشف الحركة» فعّل «Notify Surveillance Center». Tapo: المنفذ 2020 وحساب الكاميرا نفسه.",
            "Alerts and PTZ use ONVIF. Hikvision: enable ONVIF in the camera's web page (Network → Advanced → Integration Protocol), create an ONVIF user and type it here, and in “Motion detection” turn on “Notify Surveillance Center”. Tapo: port 2020 with the same camera account.",
        )), Ui.below(this, 8))
        onvifPortField = Ui.field(this, cam.brand.onvifPort.toString(), InputType.TYPE_CLASS_NUMBER)
        if (cam.onvifPort > 0) onvifPortField.setText(cam.onvifPort.toString())
        addRow(advanced, t("منفذ ONVIF (اتركه فارغًا للافتراضي)", "ONVIF port (empty = default)"), onvifPortField, null)
        onvifUserField = Ui.field(this, t("فارغ = نفس اسم المستخدم", "empty = same user"))
        onvifUserField.setText(cam.onvifUser)
        addRow(advanced, t("مستخدم ONVIF", "ONVIF user"), onvifUserField, null)
        onvifPassField = Ui.field(this, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        onvifPassField.setText(cam.onvifPass)
        addRow(advanced, t("كلمة مرور ONVIF", "ONVIF password"), onvifPassField, null)

        preview = Ui.text(this, "", 12f, Ui.MUTED)
        preview.textDirection = View.TEXT_DIRECTION_LTR
        col.addView(preview, Ui.below(this, 16))

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updatePreview()
        }
        listOf(hostField, portField, userField, passField, channelField, urlField).forEach { it.addTextChangedListener(watcher) }

        val actions = LinearLayout(this)
        actions.addView(Ui.button(this, t("حفظ ومشاهدة", "Save and watch"), primary = true) { save(watch = true) }, Ui.share(this, 4))
        actions.addView(Ui.button(this, t("حفظ", "Save")) { save(watch = false) }, Ui.share(this, 4))
        col.addView(actions, Ui.below(this, 24))

        if (!isNew) {
            val del = Ui.button(this, t("حذف الكاميرا", "Delete camera")) { askDelete() }
            del.setTextColor(Ui.RED)
            col.addView(del, Ui.below(this, 12))
        }

        setContentView(scroll)
        choose(brand, first = true)
    }

    private fun label(s: String) = Ui.text(this, s, 15f, Ui.TEXT, true)

    private fun note(s: String): TextView {
        val n = Ui.text(this, s, 14f, Ui.MUTED)
        n.setLineSpacing(0f, 1.25f)
        return n
    }

    private fun check(s: String, on: Boolean): CheckBox {
        val c = CheckBox(this)
        c.text = s
        c.textSize = 15f * Ui.scale(this)
        c.setTextColor(Ui.TEXT)
        c.isChecked = on
        c.minHeight = Ui.dp(this, 48)
        return c
    }

    private fun addRow(col: LinearLayout, title: String, field: EditText, group: ArrayList<View>?) {
        val l = label(title)
        l.labelFor = View.generateViewId().also { field.id = it }
        col.addView(l, Ui.below(this, 16))
        col.addView(field, Ui.below(this, 6))
        group?.add(l)
        group?.add(field)
    }

    private fun choose(b: Brand, first: Boolean = false) {
        // a different make: its usual port (unless the user typed another one)
        if (!first && ::portField.isInitialized) {
            val typed = portField.text.toString().toIntOrNull()
            if (typed == null || typed == brand.port) portField.setText(b.port.toString())
            if (b == Brand.PHONE && userField.text.isBlank()) userField.setText("ipcam")
            if (b != Brand.IPWEBCAM && b != Brand.PHONE && userField.text.isBlank()) userField.setText("admin")
        }
        brand = b
        brandButtons.forEach { (k, btn) ->
            val on = k == b
            btn.background = if (on) Ui.states(this, Ui.GOLD, Ui.GOLD_DOWN) else Ui.states(this, Ui.CARD, Ui.CARD_DOWN)
            btn.setTextColor(if (on) Ui.BG else Ui.TEXT)
            btn.contentDescription = k.label(this) + if (on) t("، مختار", ", selected") else ""
        }
        val custom = b == Brand.CUSTOM
        hostRows.forEach { it.visibility = if (custom) View.GONE else View.VISIBLE }
        channelRows.forEach { it.visibility = if (b == Brand.HIKVISION || b == Brand.DAHUA) View.VISIBLE else View.GONE }
        urlRows.forEach { it.visibility = if (custom) View.VISIBLE else View.GONE }
        onvifRows.forEach { it.visibility = if (b.onvif) View.VISIBLE else View.GONE }
        if (b.onvif) advanced.visibility = View.GONE
        popBox.visibility = if (b.onvif) View.VISIBLE else View.GONE
        if (::onvifPortField.isInitialized) onvifPortField.hint = b.onvifPort.toString()
        hint.text = when (b) {
            Brand.HIKVISION -> t(
                "اسم المستخدم عادةً admin، وكلمة المرور هي كلمة مرور الجهاز نفسه.\nإذا كانت الكاميرات موصولة على مسجّل NVR: أضف عنوان المسجّل، واكتب رقم القناة لكل كاميرا.",
                "The user is usually admin, and the password is the device's own password.\nIf the cameras are on an NVR: add the NVR's address and type the channel of each camera.",
            )
            Brand.DAHUA -> t(
                "Dahua: اسم المستخدم admin وكلمة مرور الجهاز.\nImou: اسم المستخدم admin، وكلمة المرور هي Safety Code المكتوب على ملصق الكاميرا (ما لم تغيّرها).",
                "Dahua: user admin and the device password.\nImou: user admin, and the password is the Safety Code on the camera's label (unless you changed it).",
            )
            Brand.TAPO -> t(
                "مهم: لا تستخدم حساب تطبيق Tapo. أنشئ «حساب الكاميرا» من تطبيق Tapo: الكاميرا ← الإعدادات ← الإعدادات المتقدمة ← حساب الكاميرا، ثم اكتبه هنا.",
                "Important: not your Tapo app account. Create a “Camera Account” in the Tapo app: camera → Settings → Advanced settings → Camera Account, then type it here.",
            )
            Brand.IPWEBCAM -> t(
                "على الجوال القديم: ثبّت تطبيق IP Webcam من Google Play ← «Start server». العنوان يظهر أسفل شاشته، والمنفذ 8080. اترك المستخدم وكلمة المرور فارغين إذا لم تضعهما في IP Webcam.",
                "On the old phone: install IP Webcam from Google Play → “Start server”. The address is shown at the bottom of its screen, port 8080. Leave user and password empty unless you set them in IP Webcam.",
            )
            Brand.PHONE -> t(
                "جوال فيه «كاميرات البيت» في وضع الكاميرا (الإعدادات ← اجعل هذا الجوال كاميرا). الأسهل: «بحث في الشبكة» يجده وحده. المستخدم ipcam وكلمة المرور الظاهرة على ذلك الجوال.",
                "A phone running Home Cameras in camera mode (Settings → Make this phone a camera). Easiest: “Search network” finds it. User ipcam and the password shown on that phone.",
            )
            Brand.CUSTOM -> t(
                "لأي كاميرا أخرى: اكتب رابط RTSP كامل كما في دليل الكاميرا، ومعه اسم المستخدم وكلمة المرور.",
                "For any other camera: type the full RTSP link from the camera's manual, with the user and password.",
            )
        }
        updatePreview()
    }

    private fun readInto(c: Camera) {
        c.brand = brand
        c.host = hostField.text.toString().trim()
        c.port = portField.text.toString().trim().toIntOrNull() ?: brand.port
        c.user = userField.text.toString().trim()
        c.pass = passField.text.toString()
        c.channel = channelField.text.toString().trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
        c.url = urlField.text.toString().trim()
        c.name = nameField.text.toString().trim()
        c.group = groupField.text.toString().trim()
        c.ptz = ptzBox.isChecked && brand.onvif
        c.alert = alertBox.isChecked && brand.onvif
        c.popTv = popBox.isChecked
        c.onvifPort = onvifPortField.text.toString().trim().toIntOrNull() ?: 0
        c.onvifUser = onvifUserField.text.toString().trim()
        c.onvifPass = onvifPassField.text.toString()
    }

    private fun updatePreview() {
        if (!::preview.isInitialized) return
        val c = Camera()
        readInto(c)
        val ok = if (brand == Brand.CUSTOM) c.url.isNotEmpty() else c.host.isNotEmpty()
        preview.text = if (ok) t("رابط البث: ", "Stream link: ") + c.shown(true) else ""
    }

    private fun save(watch: Boolean) {
        readInto(cam)
        if (brand == Brand.CUSTOM) {
            if (!cam.url.startsWith("rtsp://", ignoreCase = true)) {
                toast(t("اكتب رابطًا يبدأ بـ rtsp://", "Type a link that starts with rtsp://"))
                return
            }
        } else if (cam.host.isEmpty()) {
            toast(t("اكتب عنوان الكاميرا (IP)", "Type the camera's address (IP)"))
            return
        }
        if (cam.name.isEmpty()) cam.name = cam.where().ifEmpty { brand.label(this) }
        Store.put(this, cam)
        if (cam.alert && !Prefs.alertsOn(this)) {
            toast(t("فعّل «تنبيهات الحركة» من الإعدادات حتى تصل التنبيهات.", "Turn on “Motion alerts” in Settings to receive alerts."))
        }
        if (watch) startActivity(Watch.one(this, cam.id))
        finish()
    }

    private fun askDelete() {
        AlertDialog.Builder(this)
            .setTitle(t("حذف «${cam.name}»؟", "Delete “${cam.name}”?"))
            .setMessage(t("تُحذف من هذا الجهاز فقط، ولا يتغيّر شيء في الكاميرا نفسها.", "It is removed from this device only; nothing changes on the camera itself."))
            .setPositiveButton(t("حذف", "Delete")) { _, _ ->
                Store.remove(this, cam.id)
                finish()
            }
            .setNegativeButton(t("إلغاء", "Cancel"), null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
