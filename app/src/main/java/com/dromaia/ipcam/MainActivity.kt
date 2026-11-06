package com.dromaia.ipcam

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Home: the saved cameras, and buttons to add, search the network and watch all of them. */
class MainActivity : Activity() {
    private lateinit var count: TextView
    private lateinit var list: LinearLayout
    private lateinit var watchAll: TextView
    private lateinit var updateBtn: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var updateId = -1L

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Ui.rtl(this)
        Update.cleanAfterUpdate(this)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 20)
        col.setPadding(p, p, p, p)
        scroll.addView(col)

        col.addView(Ui.text(this, getString(R.string.app_name), 28f, Ui.TEXT, true))
        count = Ui.text(this, "", 15f, Ui.MUTED)
        col.addView(count, Ui.below(this, 4))

        watchAll = Ui.button(this, "عرض كل الكاميرات", primary = true) {
            startActivity(Intent(this, WatchActivity::class.java))
        }
        col.addView(watchAll, Ui.below(this, 18))

        val actions = LinearLayout(this)
        actions.addView(Ui.button(this, "＋ إضافة كاميرا") {
            startActivity(Intent(this, EditActivity::class.java))
        }, Ui.share(this, 4))
        actions.addView(Ui.button(this, "بحث في الشبكة") {
            startActivity(Intent(this, ScanActivity::class.java))
        }, Ui.share(this, 4))
        col.addView(actions, Ui.below(this, 10))

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        col.addView(list, Ui.below(this, 10))

        // footer: version and update
        val v = Update.version(this)
        col.addView(Ui.text(this, if (v.isEmpty()) "" else "الإصدار $v", 13f, Ui.MUTED), Ui.below(this, 28))
        updateBtn = Ui.button(this, "تحديث التطبيق") { update() }
        updateBtn.textSize = 14f
        col.addView(updateBtn, Ui.below(this, 6))

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refresh() {
        val cams = Store.all(this)
        count.text = Ui.cameras(cams.size)
        watchAll.visibility = if (cams.size > 1) android.view.View.VISIBLE else android.view.View.GONE
        list.removeAllViews()

        if (cams.isEmpty()) {
            val empty = Ui.text(
                this,
                "ابدأ بـ«بحث في الشبكة» ليجد التطبيق الكاميرات الموجودة على الواي فاي، أو «إضافة كاميرا» إذا كنت تعرف عنوانها.",
                16f, Ui.MUTED,
            )
            empty.setLineSpacing(0f, 1.3f)
            list.addView(empty, Ui.below(this, 24))
            return
        }

        for (cam in cams) {
            val card = Ui.card(this)
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = android.view.Gravity.CENTER_VERTICAL

            val texts = LinearLayout(this)
            texts.orientation = LinearLayout.VERTICAL
            texts.addView(Ui.text(this, cam.name, 18f, Ui.TEXT, true))
            texts.addView(Ui.text(this, cam.brand.label + "  ·  " + cam.where(), 13f, Ui.MUTED), Ui.below(this, 4))
            card.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val edit = Ui.button(this, "تعديل") { openEdit(cam.id) }
            edit.textSize = 14f
            edit.minHeight = Ui.dp(this, 40)
            card.addView(edit)

            card.setOnClickListener {
                startActivity(Intent(this, WatchActivity::class.java).putExtra("id", cam.id))
            }
            card.setOnLongClickListener {
                openEdit(cam.id)
                true
            }
            list.addView(card, Ui.below(this, 10))
        }
    }

    private fun openEdit(id: String) {
        startActivity(Intent(this, EditActivity::class.java).putExtra("id", id))
    }

    private fun update() {
        if (updateId >= 0) return // already downloading
        val id = Update.start(this)
        when (id) {
            Update.NEEDS_PERMISSION -> toast("اسمح للتطبيق بتثبيت التحديثات، ثم اضغط «تحديث التطبيق» مرة ثانية.")
            Update.FAILED -> toast("تعذّر بدء التنزيل.")
            else -> {
                updateId = id
                poll()
            }
        }
    }

    private fun poll() {
        val pct = Update.progress(this, updateId)
        when {
            pct < 0 -> {
                updateId = -1L
                updateBtn.text = "تحديث التطبيق"
                toast("تعذّر تنزيل التحديث. تأكد من الإنترنت، وأن مستودع GitHub عام (Public).")
            }
            pct >= 100 -> {
                Update.install(this, updateId)
                updateId = -1L
                updateBtn.text = "تحديث التطبيق"
            }
            else -> {
                updateBtn.text = "جاري التنزيل… ${pct}٪"
                handler.postDelayed({ poll() }, 700)
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
