package com.dromaia.ipcam

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Add a camera or change one. Extras: "id" (change) or "host" / "brand" / "name" (from the network search). */
class EditActivity : Activity() {
    private lateinit var cam: Camera
    private var isNew = true
    private var brand = Brand.HIKVISION

    private lateinit var nameField: EditText
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var channelField: EditText
    private lateinit var urlField: EditText
    private lateinit var hint: TextView
    private lateinit var preview: TextView
    private val brandButtons = HashMap<Brand, TextView>()
    private val normalRows = ArrayList<View>() // hidden for "رابط RTSP"
    private val channelRows = ArrayList<View>() // hidden for Tapo and "رابط RTSP"
    private val urlRows = ArrayList<View>() // shown only for "رابط RTSP"

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Ui.rtl(this)

        val existing = intent.getStringExtra("id")?.let { Store.get(this, it) }
        isNew = existing == null
        cam = existing ?: Camera(
            host = intent.getStringExtra("host") ?: "",
            name = intent.getStringExtra("name") ?: "",
            brand = Brand.of(intent.getStringExtra("brand")) ?: Brand.HIKVISION,
        )
        brand = cam.brand

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 20)
        col.setPadding(p, p, p, p)
        scroll.addView(col)

        col.addView(Ui.text(this, if (isNew) "إضافة كاميرا" else "تعديل الكاميرا", 26f, Ui.TEXT, true))

        // make of the camera
        col.addView(label("نوع الكاميرا"), Ui.below(this, 18))
        val rowA = LinearLayout(this)
        val rowB = LinearLayout(this)
        Brand.values().forEachIndexed { i, b ->
            val btn = Ui.button(this, b.label) { choose(b) }
            btn.textSize = 14f
            brandButtons[b] = btn
            (if (i < 2) rowA else rowB).addView(btn, Ui.share(this, 4))
        }
        col.addView(rowA, Ui.below(this, 8))
        col.addView(rowB, Ui.below(this, 8))

        hint = Ui.text(this, "", 14f, Ui.MUTED)
        hint.setLineSpacing(0f, 1.25f)
        col.addView(hint, Ui.below(this, 12))

        nameField = Ui.field(this, "مثل: الباب الأمامي")
        nameField.setText(cam.name)
        col.addView(label("الاسم"), Ui.below(this, 18))
        col.addView(nameField, Ui.below(this, 6))

        hostField = Ui.field(this, "مثل: 192.168.1.64", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        hostField.setText(cam.host)
        addRow(col, "عنوان الكاميرا (IP)", hostField, normalRows)

        portField = Ui.field(this, "554", InputType.TYPE_CLASS_NUMBER)
        portField.setText(cam.port.toString())
        addRow(col, "المنفذ (عادةً 554)", portField, normalRows)

        userField = Ui.field(this, "admin")
        userField.setText(cam.user)
        addRow(col, "اسم المستخدم", userField, normalRows)

        passField = Ui.field(this, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        passField.setText(cam.pass)
        addRow(col, "كلمة المرور", passField, normalRows)
        val showPass = Ui.text(this, "إظهار كلمة المرور", 14f, Ui.GOLD)
        showPass.isFocusable = true
        showPass.isClickable = true
        showPass.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8))
        var passShown = false
        showPass.setOnClickListener {
            passShown = !passShown
            passField.inputType = InputType.TYPE_CLASS_TEXT or
                (if (passShown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            passField.setSelection(passField.text.length)
            showPass.text = if (passShown) "إخفاء كلمة المرور" else "إظهار كلمة المرور"
        }
        col.addView(showPass, Ui.below(this, 0))
        normalRows.add(showPass)

        channelField = Ui.field(this, "1", InputType.TYPE_CLASS_NUMBER)
        channelField.setText(cam.channel.toString())
        addRow(col, "رقم القناة (1 للكاميرا المفردة، أو رقم الكاميرا في المسجّل NVR)", channelField, channelRows)

        urlField = Ui.field(this, "rtsp://user:pass@192.168.1.50:554/stream", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        urlField.setText(cam.url)
        addRow(col, "رابط البث RTSP كامل", urlField, urlRows)

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
        actions.addView(Ui.button(this, "حفظ ومشاهدة", primary = true) { save(watch = true) }, Ui.share(this, 4))
        actions.addView(Ui.button(this, "حفظ") { save(watch = false) }, Ui.share(this, 4))
        col.addView(actions, Ui.below(this, 24))

        if (!isNew) {
            val del = Ui.button(this, "حذف الكاميرا") { askDelete() }
            del.setTextColor(Ui.RED)
            col.addView(del, Ui.below(this, 12))
        }

        setContentView(scroll)
        choose(brand)
    }

    private fun label(s: String) = Ui.text(this, s, 15f, Ui.TEXT, true)

    private fun addRow(col: LinearLayout, title: String, field: EditText, group: ArrayList<View>) {
        val l = label(title)
        col.addView(l, Ui.below(this, 16))
        col.addView(field, Ui.below(this, 6))
        group.add(l)
        group.add(field)
    }

    private fun choose(b: Brand) {
        brand = b
        brandButtons.forEach { (k, btn) ->
            val on = k == b
            btn.background = if (on) Ui.states(this, Ui.GOLD, Ui.GOLD_DOWN) else Ui.states(this, Ui.CARD, Ui.CARD_DOWN)
            btn.setTextColor(if (on) Ui.BG else Ui.TEXT)
        }
        val custom = b == Brand.CUSTOM
        normalRows.forEach { it.visibility = if (custom) View.GONE else View.VISIBLE }
        channelRows.forEach { it.visibility = if (custom || b == Brand.TAPO) View.GONE else View.VISIBLE }
        urlRows.forEach { it.visibility = if (custom) View.VISIBLE else View.GONE }
        hint.text = when (b) {
            Brand.HIKVISION ->
                "اسم المستخدم عادةً admin، وكلمة المرور هي كلمة مرور الجهاز نفسه (التي وضعتها عند تفعيل الكاميرا أو المسجّل).\n" +
                    "إذا كانت الكاميرات موصولة على مسجّل NVR: أضف عنوان المسجّل، واكتب رقم القناة لكل كاميرا."
            Brand.DAHUA ->
                "Dahua: اسم المستخدم admin وكلمة مرور الجهاز.\n" +
                    "Imou: اسم المستخدم admin، وكلمة المرور هي Safety Code المكتوب على ملصق الكاميرا (ما لم تغيّرها)."
            Brand.TAPO ->
                "مهم: لا تستخدم حساب تطبيق Tapo. أنشئ «حساب الكاميرا» من تطبيق Tapo:\n" +
                    "الكاميرا ← الإعدادات ← الإعدادات المتقدمة ← حساب الكاميرا، ثم اكتب اسم المستخدم وكلمة المرور هنا."
            Brand.CUSTOM ->
                "لأي كاميرا أخرى: اكتب رابط RTSP كامل كما في دليل الكاميرا، ومعه اسم المستخدم وكلمة المرور."
        }
        updatePreview()
    }

    private fun readInto(c: Camera) {
        c.brand = brand
        c.host = hostField.text.toString().trim()
        c.port = portField.text.toString().trim().toIntOrNull() ?: 554
        c.user = userField.text.toString().trim()
        c.pass = passField.text.toString()
        c.channel = channelField.text.toString().trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
        c.url = urlField.text.toString().trim()
        c.name = nameField.text.toString().trim()
    }

    private fun updatePreview() {
        if (!::preview.isInitialized) return
        val c = Camera()
        readInto(c)
        val ok = if (brand == Brand.CUSTOM) c.url.isNotEmpty() else c.host.isNotEmpty()
        preview.text = if (ok) "رابط البث: " + c.shown(true) else ""
    }

    private fun save(watch: Boolean) {
        readInto(cam)
        if (brand == Brand.CUSTOM) {
            if (!cam.url.startsWith("rtsp://", ignoreCase = true)) {
                toast("اكتب رابطًا يبدأ بـ rtsp://")
                return
            }
        } else if (cam.host.isEmpty()) {
            toast("اكتب عنوان الكاميرا (IP)")
            return
        }
        if (cam.name.isEmpty()) cam.name = cam.where().ifEmpty { brand.label }
        Store.put(this, cam)
        if (watch) startActivity(Intent(this, WatchActivity::class.java).putExtra("id", cam.id))
        finish()
    }

    private fun askDelete() {
        AlertDialog.Builder(this)
            .setTitle("حذف «${cam.name}»؟")
            .setMessage("تُحذف من هذا الجهاز فقط، ولا يتغيّر شيء في الكاميرا نفسها.")
            .setPositiveButton("حذف") { _, _ ->
                Store.remove(this, cam.id)
                finish()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
