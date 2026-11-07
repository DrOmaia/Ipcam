package com.dromaia.ipcam

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
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
    private lateinit var remoteHostField: EditText
    private lateinit var remotePortField: EditText
    private lateinit var remoteHttpField: EditText
    private lateinit var awayPanel: LinearLayout
    private lateinit var awayToggle: TextView
    private var alertWhat = "any"
    private val alertWhatButtons = HashMap<String, TextView>()
    private val hikRows = ArrayList<View>()     // «add all the recorder's cameras»: Hikvision only
    private lateinit var more: TextView // opens / closes the ONVIF options
    private lateinit var brandButton: TextView
    private val hostRows = ArrayList<View>()    // address, port, user, password: not for «RTSP link»
    private val channelRows = ArrayList<View>() // channel: makes with recorders (Brand.channels)
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
            // admin for most; ipcam for a phone; empty where the user is made in the camera's own app (Tapo «Camera Account»…)
            c.user = c.brand.defaultUser
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

        col.addView(Ui.heading(Ui.text(this, if (isNew) t("إضافة كاميرا", "Add camera") else t("تعديل الكاميرا", "Edit camera"), 26f, Ui.TEXT, true)))
        if (tv && isNew) {
            col.addView(note(t("الأسهل: أرسل الكاميرات من جوالك (الإعدادات ← إرسال الكاميرات) بدل الكتابة بالريموت.",
                "Easiest: send the cameras from your phone (Settings → Send cameras) instead of typing with the remote.")), Ui.below(this, 8))
        }

        // make of the camera: one button that opens the list of makes (easy with the remote and with touch)
        col.addView(label(t("نوع الكاميرا", "Camera type")), Ui.below(this, 18))
        brandButton = Ui.button(this, "") { pickBrand() }
        col.addView(brandButton, Ui.below(this, 8))

        // «how to add it»: the steps of the chosen make
        col.addView(label(t("طريقة الإضافة", "How to add it")), Ui.below(this, 14))
        hint = note("")
        hint.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE // read out when the make changes
        col.addView(hint, Ui.below(this, 6))

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
                b.minHeight = Ui.dp(this, 48)
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
        addRow(col, t("اسم المستخدم", "Username"), userField, hostRows)

        passField = Ui.field(this, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        passField.setText(cam.pass)
        if (tv) {
            // TV: «Done» on the keyboard saves and opens the camera (no walking down with the remote)
            passField.imeOptions = EditorInfo.IME_ACTION_DONE
            passField.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    save(watch = true)
                    true
                } else {
                    false
                }
            }
        }
        addRow(col, t("كلمة المرور", "Password"), passField, hostRows)
        val showPass = Ui.text(this, t("إظهار كلمة المرور", "Show password"), 14f, Ui.GOLD)
        showPass.isFocusable = true
        showPass.isClickable = true
        showPass.minHeight = Ui.dp(this, 48)
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

        // a Hikvision recorder (DVR / NVR): all its cameras in one go, with the names set in the recorder
        val addAll = Ui.button(this, t("＋ إضافة كل كاميرات المسجّل", "＋ Add all the recorder's cameras")) { addAllChannels() }
        col.addView(addAll, Ui.below(this, 10))
        val addAllNote = note(t("للمسجّل (DVR / NVR): اكتب عنوانه وكلمة مروره مرة واحدة، فتُضاف كاميراته كلها بأسمائها.",
            "For a recorder (DVR / NVR): type its address and password once and all its cameras are added with their names."))
        col.addView(addAllNote, Ui.below(this, 4))
        hikRows.add(addAll); hikRows.add(addAllNote)

        urlField = Ui.field(this, "rtsp://user:pass@192.168.1.50:554/stream", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        urlField.setText(cam.url)
        addRow(col, t("رابط البث الكامل (RTSP)", "Full RTSP link"), urlField, urlRows)

        // alerts and ONVIF: in a part that opens
        alertBox = Ui.check(this, t("تنبيه الحركة لهذه الكاميرا", "Motion alert for this camera"), cam.alert)
        popBox = Ui.check(this, t("على التلفزيون: أظهرها فوق التطبيقات عند الحركة", "On the TV: show it over other apps on motion"), cam.popTv)
        ptzBox = Ui.check(this, t("كاميرا متحركة (تدور يمينًا ويسارًا وأعلى وأسفل، وتقرّب وتبعّد)", "Moving camera (pan / tilt / zoom)"), cam.ptz)
        col.addView(alertBox, Ui.below(this, 18))
        alertWhat = cam.alertWhat
        val whatRow = LinearLayout(this)
        for ((k, label) in listOf("any" to t("أي حركة", "Any motion"), "human" to t("شخص فقط", "Person only"),
            "humanVehicle" to t("شخص أو سيارة", "Person or vehicle"), "vehicle" to t("سيارة فقط", "Vehicle only"))) {
            val b = Ui.button(this, label) { pickWhat(k) }
            b.textSize = 13f * Ui.scale(this)
            b.minHeight = Ui.dp(this, 48)
            alertWhatButtons[k] = b
            whatRow.addView(b, Ui.share(this, 3))
        }
        val whatScroll = android.widget.HorizontalScrollView(this)
        whatScroll.isHorizontalScrollBarEnabled = false
        whatScroll.addView(whatRow)
        col.addView(whatScroll, Ui.below(this, 6))
        onvifRows.add(whatScroll)
        pickWhat(alertWhat)
        col.addView(popBox, Ui.below(this, 2))
        col.addView(ptzBox, Ui.below(this, 2))
        onvifRows.add(alertBox); onvifRows.add(popBox); onvifRows.add(ptzBox)

        advanced = LinearLayout(this)
        advanced.orientation = LinearLayout.VERTICAL
        advanced.visibility = View.GONE
        more = Ui.button(this, "") { showAdvanced(advanced.visibility != View.VISIBLE) }
        more.textSize = 14f * Ui.scale(this)
        showAdvanced(false)
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

        // watching from outside the home (another house, travelling): an outside address for this camera / recorder
        awayPanel = LinearLayout(this)
        awayPanel.orientation = LinearLayout.VERTICAL
        awayToggle = Ui.button(this, "") { showAway(awayPanel.visibility != View.VISIBLE) }
        awayToggle.textSize = 14f * Ui.scale(this)
        col.addView(awayToggle, Ui.below(this, 10))
        col.addView(awayPanel, Ui.below(this, 0))
        awayPanel.addView(note(t(
            "لمشاهدة هذه الكاميرا وأنت بعيد (بيت آخر أو سفر). داخل شبكتها يستعمل التطبيق العنوان العادي، ومن بعيد هذا العنوان، ويختار وحده.\n" +
                "يكون العنوان واحدًا من: عنوان شبكة VPN مثل Tailscale (الأأمن)، أو اسم DDNS / عنوان الإنترنت للراوتر مع فتح المنافذ.",
            "To watch this camera while away (another house, travelling). On its own network the app uses the normal address, away this one; it chooses by itself.\n" +
                "The address is one of: a VPN address such as Tailscale (safest), or the router's DDNS name / internet address with forwarded ports.",
        )), Ui.below(this, 8))
        remoteHostField = Ui.field(this, t("مثل: 100.101.12.34 أو irbid.ddns.net", "e.g. 100.101.12.34 or irbid.ddns.net"),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        remoteHostField.setText(cam.remoteHost)
        addRow(awayPanel, t("العنوان من خارج البيت", "Address from outside"), remoteHostField, null)
        remotePortField = Ui.field(this, t("فارغ = نفس المنفذ", "empty = same port"), InputType.TYPE_CLASS_NUMBER)
        if (cam.remotePort > 0) remotePortField.setText(cam.remotePort.toString())
        addRow(awayPanel, t("منفذ البث من الخارج (RTSP)", "Stream port from outside (RTSP)"), remotePortField, null)
        remoteHttpField = Ui.field(this, t("فارغ = نفس المنفذ", "empty = same port"), InputType.TYPE_CLASS_NUMBER)
        if (cam.remoteHttp > 0) remoteHttpField.setText(cam.remoteHttp.toString())
        addRow(awayPanel, t("منفذ الويب من الخارج (للتسجيلات والتنبيهات)", "Web port from outside (recordings and alerts)"), remoteHttpField, null)
        showAway(cam.hasRemote())

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
        // TV, a new camera found by the search: straight to the field still to fill (the remote is slow to walk)
        if (tv && isNew && brand != Brand.CUSTOM && hostField.text.isNotBlank()) {
            (if (brand.defaultUser.isEmpty() && brand != Brand.IPWEBCAM && userField.text.isBlank()) userField else passField).requestFocus()
        }
    }

    private fun label(s: String) = Ui.text(this, s, 15f, Ui.TEXT, true)

    private fun note(s: String): TextView {
        val n = Ui.text(this, s, 14f, Ui.MUTED)
        n.setLineSpacing(0f, 1.25f)
        return n
    }

    // the ONVIF part: ▾ closed / ▴ open, and TalkBack says which
    private fun showAdvanced(open: Boolean) {
        advanced.visibility = if (open) View.VISIBLE else View.GONE
        val label = t("خيارات ONVIF (للتنبيه والتحريك)", "ONVIF options (alerts and PTZ)")
        more.text = label + if (open) " ▴" else " ▾"
        more.contentDescription = label + if (open) t("، مفتوحة", ", expanded") else t("، مغلقة", ", collapsed")
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
            val user = userField.text.toString()
            // the usual user of the new make, unless the user typed his own
            val usual = Brand.values().any { it.defaultUser == user && it.defaultUser.isNotEmpty() }
            if (user.isBlank() || (usual && (isNew || b.defaultUser.isNotEmpty()))) userField.setText(b.defaultUser)
        }
        brand = b
        brandButton.text = b.label(this) + "  ▾"
        brandButton.contentDescription = t("نوع الكاميرا: ", "Camera type: ") + b.label(this) + t("، اضغط للتغيير", ", press to change")
        val custom = b == Brand.CUSTOM
        hostRows.forEach { it.visibility = if (custom) View.GONE else View.VISIBLE }
        channelRows.forEach { it.visibility = if (b.channels) View.VISIBLE else View.GONE }
        urlRows.forEach { it.visibility = if (custom) View.VISIBLE else View.GONE }
        onvifRows.forEach { it.visibility = if (b.onvif) View.VISIBLE else View.GONE }
        hikRows.forEach { it.visibility = if (b == Brand.HIKVISION) View.VISIBLE else View.GONE }
        if (b.onvif) showAdvanced(false)
        popBox.visibility = if (b.onvif) View.VISIBLE else View.GONE
        if (::onvifPortField.isInitialized) onvifPortField.hint = b.onvifPort.toString()
        userField.hint = when {
            b == Brand.TAPO -> t("اسم «حساب الكاميرا»", "Camera Account user")
            b.defaultUser.isEmpty() -> t("الاسم الذي وضعته في تطبيق الكاميرا", "The user you set in the camera's app")
            else -> b.defaultUser
        }
        hint.text = Guides.text(this, b)
        updatePreview()
    }

    // the list of makes: one choice, then the form shows that make's fields and steps
    private fun pickBrand() {
        val all = Brand.values()
        AlertDialog.Builder(this)
            .setTitle(t("نوع الكاميرا", "Camera type"))
            .setSingleChoiceItems(all.map { it.label(this) }.toTypedArray(), all.indexOf(brand)) { d, which ->
                d.dismiss()
                if (all[which] != brand) choose(all[which])
            }
            .setNegativeButton(t("إلغاء", "Cancel"), null)
            .show()
    }

    private fun showAway(open: Boolean) {
        awayPanel.visibility = if (open) View.VISIBLE else View.GONE
        awayToggle.text = t("من خارج البيت (اختياري) ", "From outside the home (optional) ") + if (open) "▴" else "▾"
        awayToggle.contentDescription = t("من خارج البيت، ", "From outside the home, ") +
            if (open) t("مفتوحة", "expanded") else t("مغلقة", "collapsed")
    }

    private fun pickWhat(k: String) {
        alertWhat = k
        alertWhatButtons.forEach { (key, b) ->
            val on = key == k
            b.background = if (on) Ui.states(this, Ui.GOLD, Ui.GOLD_DOWN) else Ui.states(this, Ui.CARD, Ui.CARD_DOWN)
            b.setTextColor(if (on) Ui.BG else Ui.TEXT)
            b.contentDescription = b.text.toString() + if (on) t("، مختار", ", selected") else ""
        }
    }

    // reads the recorder's channels (with their names) and adds the chosen ones as cameras of one group
    private fun addAllChannels() {
        val probe = Camera()
        readInto(probe)
        if (probe.host.isEmpty()) {
            hostField.error = t("اكتب عنوان المسجّل", "Type the recorder's address")
            hostField.requestFocus()
            return
        }
        Toast.makeText(this, t("جارٍ قراءة كاميرات المسجّل…", "Reading the recorder's cameras…"), Toast.LENGTH_SHORT).show()
        Thread {
            val chans = try { Hik.channels(probe) } catch (_: Exception) { null }
            val devName = try { Hik.deviceName(probe) } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (chans == null) {
                    toast(t("تعذّر قراءة المسجّل. تأكد من العنوان وكلمة المرور ومنفذ الويب (ONVIF/HTTP، عادةً 80).",
                        "Could not read the recorder. Check the address, password and web port (ONVIF/HTTP, usually 80)."))
                } else if (chans.isEmpty()) {
                    toast(t("لم يُرجع المسجّل أي كاميرا.", "The recorder returned no cameras."))
                } else {
                    chooseChannels(probe, chans, devName)
                }
            }
        }.start()
    }

    private fun chooseChannels(probe: Camera, chans: List<Hik.Channel>, devName: String?) {
        val existing = Store.all(this).filter { it.sameDevice(probe) }.map { it.channel }.toSet()
        val labels = chans.map { ch ->
            val n = ch.name.ifBlank { t("كاميرا ${ch.no}", "Camera ${ch.no}") }
            "${ch.no}. $n" + (if (!ch.online) t(" (غير متصلة)", " (offline)") else "") +
                (if (ch.no in existing) t(" ✓ مضافة", " ✓ added") else "")
        }.toTypedArray()
        val picked = BooleanArray(chans.size) { chans[it].no !in existing }
        val group = probe.group.ifBlank { devName?.trim().orEmpty() }
        android.app.AlertDialog.Builder(this)
            .setTitle(t("كاميرات «${devName ?: probe.host}»", "Cameras of “${devName ?: probe.host}”"))
            .setMultiChoiceItems(labels, picked) { _, i, on -> picked[i] = on }
            .setPositiveButton(t("إضافة", "Add")) { _, _ ->
                val adds = ArrayList<Camera>()
                chans.forEachIndexed { i, ch ->
                    if (!picked[i] || ch.no in existing) return@forEachIndexed
                    val c = Camera()
                    readInto(c)
                    c.channel = ch.no
                    c.group = group
                    c.name = ch.name.ifBlank { (group.ifBlank { t("كاميرا", "Camera") }) + " " + ch.no }
                    adds.add(c)
                }
                if (adds.isEmpty()) return@setPositiveButton
                Store.merge(this, adds)
                toast(t("أُضيفت ", "Added ") + Ui.cameras(this, adds.size))
                finish()
            }
            .setNegativeButton(t("إلغاء", "Cancel"), null)
            .show()
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
        c.remoteHost = remoteHostField.text.toString().trim()
        c.remotePort = remotePortField.text.toString().trim().toIntOrNull() ?: 0
        c.remoteHttp = remoteHttpField.text.toString().trim().toIntOrNull() ?: 0
        c.alertWhat = alertWhat
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
                fieldError(urlField, t("اكتب رابطًا يبدأ بـ rtsp://", "Type a link that starts with rtsp://"))
                return
            }
        } else if (cam.host.isEmpty()) {
            fieldError(hostField, t("اكتب عنوان الكاميرا (IP)", "Type the camera's address (IP)"))
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

    // the message next to the field itself, and the cursor (or the TV remote) goes there
    private fun fieldError(f: EditText, msg: String) {
        f.error = msg
        f.requestFocus()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
