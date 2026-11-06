package com.dromaia.ipcam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicInteger

/** Searches the home network for cameras; tap one to add it. */
class ScanActivity : Activity() {
    private val found = LinkedHashMap<String, Found>()
    @Volatile private var cancelled = false
    private var running = false
    private var lock: WifiManager.MulticastLock? = null
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: LinearLayout
    private lateinit var again: TextView
    private lateinit var tips: TextView

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Ui.rtl(this)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 20)
        col.setPadding(p, p, p, p)
        scroll.addView(col)

        col.addView(Ui.text(this, "البحث في الشبكة", 26f, Ui.TEXT, true))
        status = Ui.text(this, "", 15f, Ui.MUTED)
        col.addView(status, Ui.below(this, 8))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 254
        col.addView(progress, Ui.below(this, 8))

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        col.addView(list, Ui.below(this, 8))

        again = Ui.button(this, "إعادة البحث") { scan() }
        col.addView(again, Ui.below(this, 16))

        tips = Ui.text(
            this,
            "إذا لم تظهر الكاميرا:\n" +
                "• تأكد أن الجهاز على نفس الواي فاي الذي عليه الكاميرات.\n" +
                "• إذا كانت الكاميرات موصولة على مسجّل (NVR/DVR) فسيظهر المسجّل وحده: أضفه واختر رقم القناة لكل كاميرا.\n" +
                "• تقدر دائمًا تضيفها يدويًا من «إضافة» إذا كنت تعرف عنوانها (من تطبيق الكاميرا أو صفحة الراوتر).",
            14f, Ui.MUTED,
        )
        tips.setLineSpacing(0f, 1.25f)
        col.addView(tips, Ui.below(this, 20))

        setContentView(scroll)
        scan()
    }

    override fun onResume() {
        super.onResume()
        if (!running) render() // "مضافة" marks after adding one
    }

    override fun onDestroy() {
        cancelled = true
        releaseLock()
        super.onDestroy()
    }

    private fun scan() {
        if (running) return
        val local = Discovery.localIPv4()
        if (local == null) {
            status.text = "الجهاز غير متصل بشبكة البيت. اتصل بالواي فاي ثم أعد البحث."
            progress.visibility = View.GONE
            return
        }
        running = true
        cancelled = false
        found.clear()
        render()
        val ip = local.hostAddress ?: ""
        status.text = "جاري البحث في الشبكة ${ip.substringBeforeLast('.')}.x …"
        progress.progress = 0
        progress.visibility = View.VISIBLE
        again.visibility = View.GONE
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("ipcam").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        } catch (_: Exception) {
        }

        val left = AtomicInteger(2)
        val finished: () -> Unit = {
            if (left.decrementAndGet() == 0) runOnUiThread { done() }
        }
        Thread {
            try {
                Discovery.onvif(local, 3500) { f -> runOnUiThread { add(f) } }
            } catch (_: Exception) {
            }
            finished()
        }.start()
        Thread {
            try {
                Discovery.scanRtsp(
                    local,
                    { cancelled },
                    { f -> runOnUiThread { add(f) } },
                    { n -> runOnUiThread { progress.progress = n } },
                )
            } catch (_: Exception) {
            }
            finished()
        }.start()
    }

    private fun add(f: Found) {
        if (isFinishing) return
        val old = found[f.ip]
        if (old == null) found[f.ip] = f else old.merge(f)
        render()
    }

    private fun done() {
        running = false
        releaseLock()
        if (isFinishing) return
        progress.visibility = View.GONE
        again.visibility = View.VISIBLE
        status.text = if (found.isEmpty()) "انتهى البحث: لم أجد أي كاميرا." else "انتهى البحث: " + Ui.devices(found.size) + ". اضغط على الجهاز لإضافته."
    }

    private fun releaseLock() {
        try {
            lock?.release()
        } catch (_: Exception) {
        }
        lock = null
    }

    private fun render() {
        list.removeAllViews()
        val saved = Store.all(this)
        for (f in found.values.sortedBy { it.lastPart() }) {
            val mine = saved.firstOrNull { it.host == f.ip }
            val card = Ui.card(this)
            card.addView(Ui.text(this, (f.brand?.label ?: "جهاز فيديو") + "  —  " + f.ip, 17f, Ui.TEXT, true))
            val details = ArrayList<String>()
            if (f.model.isNotEmpty()) details.add(f.model)
            if (f.onvif) details.add("ONVIF")
            if (f.rtsp) details.add("RTSP")
            if (details.isNotEmpty()) card.addView(Ui.text(this, details.joinToString(" · "), 13f, Ui.MUTED), Ui.below(this, 4))
            if (mine != null) card.addView(Ui.text(this, "✓ مضافة باسم «${mine.name}»", 13f, Ui.GREEN), Ui.below(this, 4))
            card.setOnClickListener {
                val i = Intent(this, EditActivity::class.java)
                if (mine != null) {
                    i.putExtra("id", mine.id)
                } else {
                    i.putExtra("host", f.ip)
                    f.brand?.let { b -> i.putExtra("brand", b.key) }
                }
                startActivity(i)
            }
            list.addView(card, Ui.below(this, 10))
        }
    }
}
