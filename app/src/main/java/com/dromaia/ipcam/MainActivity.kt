package com.dromaia.ipcam

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Home. One app, two looks:
 *  - phone: a list of cameras by group, big buttons, touch;
 *  - TV (TCL Google TV): rows of large cards per group like the Google TV home, moved with the remote.
 */
class MainActivity : Base() {
    companion object {
        @Volatile private var checkedUpdate = false
        @Volatile private var newest = 0
    }

    private lateinit var body: LinearLayout
    private val dots = HashMap<String, TextView>()
    private val pool = Executors.newFixedThreadPool(6)
    private var updateBanner: TextView? = null

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Update.cleanAfterUpdate(this)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.isFillViewport = true
        body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, if (tv) 48 else 20)
        body.setPadding(p, Ui.dp(this, if (tv) 32 else 20), p, p)
        scroll.addView(body)
        setContentView(scroll)

        // open straight on all cameras (setting; on by default on the TV)
        if (saved == null && intent?.action == Intent.ACTION_MAIN && Prefs.openAll(this) && Store.all(this).isNotEmpty()) {
            startActivity(Watch.all(this))
        }
    }

    override fun onResume() {
        super.onResume()
        build()
        checkUpdate()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun build() {
        body.removeAllViews()
        dots.clear()
        val cams = Store.all(this)
        if (tv) buildTv(cams) else buildPhone(cams)
        checkStatus(cams)
        updateShortcuts(cams)
    }

    // ---------------------------------------------------------------- phone
    private fun buildPhone(cams: List<Camera>) {
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(Ui.text(this, getString(R.string.app_name).let { t(it, "Home Cameras") }, 28f, Ui.TEXT, true))
        titles.addView(Ui.text(this, Ui.cameras(this, cams.size), 15f, Ui.MUTED))
        head.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val settings = Ui.button(this, "⚙") { startActivity(Intent(this, SettingsActivity::class.java)) }
        settings.contentDescription = t("الإعدادات", "Settings")
        settings.textSize = 22f
        head.addView(settings, LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)))
        body.addView(head)

        addUpdateBanner()

        if (cams.isNotEmpty()) {
            val watch = LinearLayout(this)
            if (cams.size > 1) {
                watch.addView(Ui.button(this, t("▦ عرض الكل", "▦ Watch all"), primary = true) { startActivity(Watch.all(this)) }, Ui.share(this, 4))
                watch.addView(Ui.button(this, t("⟳ جولة", "⟳ Tour")) { startActivity(Watch.tour(this)) }, Ui.share(this, 4))
                body.addView(watch, Ui.below(this, 16))
            }
        }
        val actions = LinearLayout(this)
        actions.addView(Ui.button(this, t("＋ إضافة كاميرا", "＋ Add camera")) { startActivity(Intent(this, EditActivity::class.java)) }, Ui.share(this, 4))
        actions.addView(Ui.button(this, t("بحث في الشبكة", "Search network")) { startActivity(Intent(this, ScanActivity::class.java)) }, Ui.share(this, 4))
        body.addView(actions, Ui.below(this, 10))

        if (cams.isEmpty()) {
            val empty = Ui.text(
                this,
                t(
                    "ابدأ بـ«بحث في الشبكة» ليجد التطبيق الكاميرات الموجودة على الواي فاي، أو «إضافة كاميرا» إذا كنت تعرف عنوانها، أو استقبلها من جهاز آخر من الإعدادات.",
                    "Start with “Search network” to find the cameras on your Wi-Fi, “Add camera” if you know its address, or receive them from another device in Settings.",
                ),
                16f, Ui.MUTED,
            )
            empty.setLineSpacing(0f, 1.3f)
            body.addView(empty, Ui.below(this, 24))
        }

        for ((group, list) in grouped(cams)) {
            if (group.isNotEmpty() || grouped(cams).size > 1) {
                val gh = LinearLayout(this)
                gh.gravity = Gravity.CENTER_VERTICAL
                gh.addView(
                    Ui.text(this, group.ifEmpty { t("بدون مجموعة", "No group") }, 18f, Ui.GOLD, true),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                if (group.isNotEmpty() && list.size > 1) {
                    val g = Ui.button(this, t("عرض المجموعة", "Watch group")) { startActivity(Watch.all(this, group)) }
                    g.textSize = 14f
                    g.minHeight = Ui.dp(this, 44)
                    gh.addView(g)
                }
                body.addView(gh, Ui.below(this, 22))
            }
            for (cam in list) body.addView(phoneCard(cam), Ui.below(this, 10))
        }

        body.addView(Ui.text(this, versionLine(), 13f, Ui.MUTED), Ui.below(this, 28))
    }

    private fun phoneCard(cam: Camera): View {
        val card = Ui.card(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        val dot = dot(cam)
        card.addView(dot)
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0)
        texts.addView(Ui.text(this, cam.name, 18f, Ui.TEXT, true))
        texts.addView(Ui.text(this, cam.brand.label(this) + "  ·  " + cam.where(), 13f, Ui.MUTED), Ui.below(this, 4))
        card.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val more = Ui.button(this, "⋮") { menu(cam) }
        more.contentDescription = t("خيارات ${cam.name}", "Options for ${cam.name}")
        more.textSize = 20f
        card.addView(more, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        card.contentDescription = cam.name
        card.setOnClickListener { startActivity(Watch.one(this, cam.id)) }
        card.setOnLongClickListener { menu(cam); true }
        return card
    }

    // ---------------------------------------------------------------- TV
    private fun buildTv(cams: List<Camera>) {
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(Ui.text(this, t(getString(R.string.app_name), "Home Cameras"), 34f, Ui.TEXT, true))
        head.addView(Ui.text(this, "   " + Ui.cameras(this, cams.size), 16f, Ui.MUTED))
        body.addView(head)
        addUpdateBanner()

        // the action row (like the Google TV top chips)
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        var first: View? = null
        fun chip(label: String, primary: Boolean = false, go: () -> Unit) {
            val b = Ui.button(this, label, primary, go)
            b.setPadding(Ui.dp(this, 22), Ui.dp(this, 12), Ui.dp(this, 22), Ui.dp(this, 12))
            focusGrow(b, 1.06f)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = Ui.dp(this, 14)
            chips.addView(b, lp)
            if (first == null) first = b
        }
        if (cams.isNotEmpty()) {
            chip(t("▦ عرض الكل", "▦ Watch all"), primary = true) { startActivity(Watch.all(this)) }
            if (cams.size > 1) chip(t("⟳ جولة", "⟳ Tour")) { startActivity(Watch.tour(this)) }
        } else {
            chip(t("⇩ استقبال الكاميرات من الجوال", "⇩ Receive cameras from the phone"), primary = true) { receive() }
        }
        if (cams.isNotEmpty()) chip(t("⇩ استقبال من الجوال", "⇩ Receive from phone")) { receive() }
        chip(t("بحث في الشبكة", "Search network")) { startActivity(Intent(this, ScanActivity::class.java)) }
        chip(t("＋ إضافة", "＋ Add")) { startActivity(Intent(this, EditActivity::class.java)) }
        chip(t("⚙ الإعدادات", "⚙ Settings")) { startActivity(Intent(this, SettingsActivity::class.java)) }
        val chipScroll = HorizontalScrollView(this)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.clipToPadding = false
        chipScroll.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8))
        chipScroll.addView(chips)
        body.addView(chipScroll, Ui.below(this, 20))

        if (cams.isEmpty()) {
            val empty = Ui.text(
                this,
                t(
                    "أسهل طريقة: افتح «كاميرات البيت» على جوالك ← الإعدادات ← «إرسال الكاميرات»، واختر «استقبال الكاميرات من الجوال» هنا. لا حاجة للكتابة بالريموت.",
                    "Easiest: open Home Cameras on your phone → Settings → “Send cameras”, and choose “Receive cameras from the phone” here. No typing with the remote.",
                ),
                20f, Ui.MUTED,
            )
            empty.setLineSpacing(0f, 1.3f)
            body.addView(empty, Ui.below(this, 28))
        }

        // one row of big cards per group
        for ((group, list) in grouped(cams)) {
            val title = when {
                group.isNotEmpty() -> group
                grouped(cams).size > 1 -> t("بدون مجموعة", "No group")
                else -> t("الكاميرات", "Cameras")
            }
            body.addView(Ui.text(this, title, 22f, Ui.GOLD, true), Ui.below(this, 28))
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            if (group.isNotEmpty() && list.size > 1) row.addView(tvCard(null, group), tvCardParams())
            for (cam in list) row.addView(tvCard(cam, group), tvCardParams())
            val hs = HorizontalScrollView(this)
            hs.isHorizontalScrollBarEnabled = false
            hs.clipToPadding = false
            hs.clipChildren = false
            row.clipChildren = false
            hs.setPadding(Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14))
            hs.addView(row)
            body.addView(hs, Ui.below(this, 4))
        }

        body.addView(Ui.text(this, versionLine(), 14f, Ui.MUTED), Ui.below(this, 32))
        first?.requestFocus()
    }

    private fun tvCardParams(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(Ui.dp(this, 280), Ui.dp(this, 158))
        lp.marginEnd = Ui.dp(this, 20)
        return lp
    }

    /** A big card; [cam] null = «watch the whole group». */
    private fun tvCard(cam: Camera?, group: String): View {
        val card = Ui.card(this)
        card.gravity = Gravity.BOTTOM
        card.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16))
        focusGrow(card, 1.08f)
        if (cam == null) {
            card.background = Ui.states(this, Ui.GOLD, Ui.GOLD_DOWN, 18)
            card.addView(Ui.text(this, "▦", 30f, Ui.BG, true))
            card.addView(Ui.text(this, t("عرض المجموعة", "Watch the group"), 18f, Ui.BG, true), Ui.below(this, 6))
            card.contentDescription = t("عرض مجموعة $group", "Watch group $group")
            card.setOnClickListener { startActivity(Watch.all(this, group)) }
            return card
        }
        card.background = Ui.states(this, Ui.CARD, Ui.CARD_DOWN, 18)
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(dot(cam))
        top.addView(Ui.text(this, "  " + cam.brand.label(this), 13f, Ui.MUTED))
        card.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val name = Ui.text(this, cam.name, 22f, Ui.TEXT, true)
        name.maxLines = 2
        card.addView(name)
        card.contentDescription = cam.name
        card.setOnClickListener { startActivity(Watch.one(this, cam.id)) }
        card.setOnLongClickListener { menu(cam); true }
        return card
    }

    private fun focusGrow(v: View, by: Float) {
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) by else 1f).scaleY(if (has) by else 1f).setDuration(140).start()
            view.elevation = if (has) Ui.dp(this, 8).toFloat() else 0f
        }
    }

    // ---------------------------------------------------------------- shared
    private fun grouped(cams: List<Camera>): List<Pair<String, List<Camera>>> {
        val order = cams.map { it.group.trim() }.distinct().sortedBy { it.isEmpty() } // groups first, «no group» last
        return order.map { g -> Pair(g, cams.filter { it.group.trim() == g }) }
    }

    private fun dot(cam: Camera): TextView {
        val d = Ui.text(this, "●", 16f, Ui.MUTED)
        d.contentDescription = t("جارٍ الفحص", "Checking")
        dots[cam.id] = d
        return d
    }

    // green = the camera answers on the network, red = it does not
    private fun checkStatus(cams: List<Camera>) {
        for (cam in cams) {
            pool.execute {
                val ok = Discovery.reachable(cam.address(), cam.rtspPort())
                runOnUiThread {
                    dots[cam.id]?.let {
                        it.setTextColor(if (ok) Ui.GREEN else Ui.RED)
                        it.contentDescription = if (ok) t("متصلة", "Online") else t("غير متصلة", "Offline")
                    }
                }
            }
        }
    }

    private fun menu(cam: Camera) {
        val items = arrayListOf(t("مشاهدة", "Watch"), t("تعديل", "Edit"))
        val canPin = Build.VERSION.SDK_INT >= 26 && !tv
        if (canPin) items.add(t("اختصار على الشاشة الرئيسية", "Shortcut on the home screen"))
        AlertDialog.Builder(this)
            .setTitle(cam.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> startActivity(Watch.one(this, cam.id))
                    1 -> startActivity(Intent(this, EditActivity::class.java).putExtra("id", cam.id))
                    2 -> pinShortcut(cam)
                }
            }
            .show()
    }

    private fun receive() = startActivity(Intent(this, TransferActivity::class.java).putExtra(TransferActivity.MODE, "receive"))

    private fun shortcut(id: String, label: String, intent: Intent): ShortcutInfo? {
        if (Build.VERSION.SDK_INT < 25) return null
        return ShortcutInfo.Builder(this, id)
            .setShortLabel(label.take(24).ifEmpty { "Camera" })
            .setLongLabel(label.take(40).ifEmpty { "Camera" })
            .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
            .setIntent(intent.setAction(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            .build()
    }

    // long-press on the app icon: «all cameras» and the first three cameras
    private fun updateShortcuts(cams: List<Camera>) {
        if (Build.VERSION.SDK_INT < 25 || tv) return
        try {
            val sm = getSystemService(ShortcutManager::class.java) ?: return
            val list = ArrayList<ShortcutInfo>()
            if (cams.size > 1) shortcut("all", t("كل الكاميرات", "All cameras"), Watch.all(this))?.let { list.add(it) }
            for (cam in cams.take(3)) shortcut("cam-" + cam.id, cam.name, Watch.one(this, cam.id))?.let { list.add(it) }
            sm.setDynamicShortcuts(list)
        } catch (_: Exception) {
        }
    }

    private fun pinShortcut(cam: Camera) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val sm = getSystemService(ShortcutManager::class.java)
            val info = shortcut("cam-" + cam.id, cam.name, Watch.one(this, cam.id))
            if (sm != null && info != null && sm.isRequestPinShortcutSupported) sm.requestPinShortcut(info, null)
            else Toast.makeText(this, t("الشاشة الرئيسية لا تدعم الاختصارات", "This home screen does not support shortcuts"), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
        }
    }

    private fun versionLine(): String {
        val v = Update.versionNumber(this)
        return t("الإصدار $v", "Version $v")
    }

    // «a new version is available» under the title; tap → the update section of Settings
    private fun addUpdateBanner() {
        val b = Ui.button(this, "") { startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.UPDATE, true)) }
        b.textSize = 15f * Ui.scale(this)
        b.visibility = View.GONE
        updateBanner = b
        body.addView(b, Ui.below(this, 12))
        showBanner()
    }

    private fun showBanner() {
        val b = updateBanner ?: return
        val mine = Update.versionNumber(this)
        if (newest > mine) {
            b.text = t("⬆ نسخة جديدة متاحة: $newest (عندك $mine) — اضغط للتحديث", "⬆ New version $newest available (you have $mine) — tap to update")
            b.setTextColor(Ui.GOLD)
            b.visibility = View.VISIBLE
        } else {
            b.visibility = View.GONE
        }
    }

    private fun checkUpdate() {
        if (checkedUpdate) return
        checkedUpdate = true
        pool.execute {
            val info = Update.check()
            newest = info.latest
            runOnUiThread { showBanner() }
        }
    }
}
