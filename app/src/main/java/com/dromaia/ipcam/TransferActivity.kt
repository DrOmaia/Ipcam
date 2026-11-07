package com.dromaia.ipcam

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Move cameras between the family's devices on the same Wi-Fi.
 * Extra "mode": "receive" (this device shows a code and waits) or "send" (choose cameras, find the device, type its code).
 */
class TransferActivity : Base() {
    companion object {
        const val MODE = "mode"
    }

    private var receiver: Transfer.Receiver? = null
    @Volatile private var searching = false
    private val peers = ArrayList<Transfer.Peer>()
    private lateinit var col: LinearLayout

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.isFillViewport = true
        col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, if (tv) 48 else 20)
        col.setPadding(p, p, p, p)
        scroll.addView(col)
        setContentView(scroll)
        if (intent.getStringExtra(MODE) == "send") buildSend() else buildReceive()
    }

    override fun onDestroy() {
        searching = false
        receiver?.stop()
        receiver = null
        super.onDestroy()
    }

    // ---------- receive
    private fun buildReceive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(Ui.text(this, t("استقبال الكاميرات", "Receive cameras"), 26f, Ui.TEXT, true))
        val code = (1000..9999).random().toString()
        val codeView = Ui.text(this, code.toCharArray().joinToString("  "), 56f, Ui.GOLD, true)
        codeView.gravity = Gravity.CENTER
        codeView.contentDescription = t("الرمز ", "Code ") + code.toCharArray().joinToString(" ")
        col.addView(codeView, Ui.below(this, 24))
        val how = Ui.text(
            this,
            t(
                "على الجوال الذي فيه الكاميرات: الإعدادات ← «إرسال الكاميرات»، اختر «${Device.name(this)}» واكتب هذا الرمز.\nابقَ على هذه الشاشة حتى تصل الكاميرات.",
                "On the phone that has the cameras: Settings → “Send cameras”, choose “${Device.name(this)}” and type this code.\nStay on this screen until the cameras arrive.",
            ),
            16f, Ui.MUTED,
        )
        how.gravity = Gravity.CENTER
        how.setLineSpacing(0f, 1.3f)
        col.addView(how, Ui.below(this, 20))
        val status = Ui.text(this, t("في الانتظار…", "Waiting…"), 18f, Ui.TEXT)
        status.gravity = Gravity.CENTER
        col.addView(status, Ui.below(this, 28))
        val done = Ui.button(this, t("تم", "Done"), primary = true) { finish() }
        col.addView(done, Ui.below(this, 24))

        val r = Transfer.Receiver(this, code) { from, n ->
            runOnUiThread {
                status.text = t("✓ وصلت ", "✓ Received ") + Ui.cameras(this, n) +
                    (if (from.isNotEmpty()) t(" من «$from»", " from “$from”") else "")
                status.setTextColor(Ui.GREEN)
                done.requestFocus()
            }
        }
        if (!r.start()) status.text = t("تعذّر بدء الاستقبال. تأكد من الاتصال بالواي فاي.", "Could not start. Check the Wi-Fi connection.")
        receiver = r
        done.requestFocus()
    }

    // ---------- send
    private lateinit var peerList: LinearLayout
    private lateinit var searchStatus: TextView
    private lateinit var progress: ProgressBar
    private val checks = ArrayList<Pair<CheckBox, Camera>>()

    private fun buildSend() {
        col.addView(Ui.text(this, t("إرسال الكاميرات", "Send cameras"), 26f, Ui.TEXT, true))
        col.addView(
            Ui.text(
                this,
                t(
                    "على الجهاز الآخر (التلفزيون أو جوال أحد العائلة): الإعدادات ← «استقبال الكاميرات». يجب أن يكون الجهازان على نفس الواي فاي.",
                    "On the other device (the TV or a family phone): Settings → “Receive cameras”. Both must be on the same Wi-Fi.",
                ),
                15f, Ui.MUTED,
            ).also { it.setLineSpacing(0f, 1.3f) },
            Ui.below(this, 8),
        )

        col.addView(Ui.text(this, t("الكاميرات التي تُرسل", "Cameras to send"), 17f, Ui.TEXT, true), Ui.below(this, 20))
        val cams = Store.all(this)
        if (cams.isEmpty()) {
            col.addView(Ui.text(this, t("لا توجد كاميرات في هذا الجهاز.", "There are no cameras on this device."), 15f, Ui.MUTED), Ui.below(this, 6))
        }
        for (cam in cams) {
            val cb = CheckBox(this)
            cb.text = cam.name + (if (cam.group.isNotEmpty()) "  ·  " + cam.group else "")
            cb.textSize = 16f * Ui.scale(this)
            cb.setTextColor(Ui.TEXT)
            cb.isChecked = true
            cb.minHeight = Ui.dp(this, 48)
            checks.add(Pair(cb, cam))
            col.addView(cb, Ui.below(this, 2))
        }

        col.addView(Ui.text(this, t("الأجهزة المستقبِلة", "Receiving devices"), 17f, Ui.TEXT, true), Ui.below(this, 24))
        searchStatus = Ui.text(this, "", 15f, Ui.MUTED)
        col.addView(searchStatus, Ui.below(this, 6))
        progress = ProgressBar(this)
        col.addView(progress, LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)).also { it.topMargin = Ui.dp(this, 6) })
        peerList = LinearLayout(this)
        peerList.orientation = LinearLayout.VERTICAL
        col.addView(peerList, Ui.below(this, 6))
        col.addView(Ui.button(this, t("بحث مرة ثانية", "Search again")) { search() }, Ui.below(this, 16))
        search()
    }

    private fun search() {
        if (searching) return
        searching = true
        peers.clear()
        peerList.removeAllViews()
        progress.visibility = View.VISIBLE
        searchStatus.text = t("أبحث عن الأجهزة على الواي فاي…", "Looking for devices on the Wi-Fi…")
        thread {
            Transfer.discover(this, 8000) { peer -> runOnUiThread { addPeer(peer) } }
            runOnUiThread {
                searching = false
                if (isFinishing) return@runOnUiThread
                progress.visibility = View.GONE
                searchStatus.text = if (peers.isEmpty())
                    t("لم أجد جهازًا. افتح «استقبال الكاميرات» على الجهاز الآخر ثم «بحث مرة ثانية».",
                        "No device found. Open “Receive cameras” on the other device, then “Search again”.")
                else t("اختر الجهاز:", "Choose the device:")
            }
        }
    }

    private fun addPeer(peer: Transfer.Peer) {
        if (isFinishing) return
        peers.add(peer)
        val card = Ui.card(this)
        card.addView(Ui.text(this, peer.name, 18f, Ui.TEXT, true))
        card.addView(Ui.text(this, peer.host, 13f, Ui.MUTED), Ui.below(this, 2))
        card.setOnClickListener { askCode(peer) }
        peerList.addView(card, Ui.below(this, 8))
        if (peers.size == 1) searchStatus.text = t("اختر الجهاز:", "Choose the device:")
    }

    private fun askCode(peer: Transfer.Peer) {
        val chosen = checks.filter { it.first.isChecked }.map { it.second }
        if (chosen.isEmpty()) {
            toast(t("اختر كاميرا واحدة على الأقل.", "Choose at least one camera."))
            return
        }
        val field = Ui.field(this, t("الرمز الظاهر على الجهاز", "The code shown on the device"), InputType.TYPE_CLASS_NUMBER)
        val box = LinearLayout(this)
        val p = Ui.dp(this, 20)
        box.setPadding(p, p / 2, p, 0)
        box.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle(t("إرسال إلى «${peer.name}»", "Send to “${peer.name}”"))
            .setView(box)
            .setPositiveButton(t("إرسال", "Send")) { _, _ ->
                val code = field.text.toString().trim()
                thread {
                    val r = Transfer.send(this, peer, code, chosen)
                    runOnUiThread {
                        toast(
                            when (r) {
                                "ok" -> t("✓ أُرسلت ", "✓ Sent ") + Ui.cameras(this, chosen.size)
                                "bad" -> t("الرمز غير صحيح. انظر إلى الرمز على الجهاز الآخر.", "Wrong code. Check the code on the other device.")
                                else -> t("تعذّر الإرسال. تأكد أن «استقبال الكاميرات» مفتوح على الجهاز الآخر.", "Could not send. Make sure “Receive cameras” is open on the other device.")
                            }
                        )
                    }
                }
            }
            .setNegativeButton(t("إلغاء", "Cancel"), null)
            .show()
        field.requestFocus()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
