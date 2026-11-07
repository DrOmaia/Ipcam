package com.dromaia.ipcam

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
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
        col.addView(Ui.heading(Ui.text(this, t("استقبال الكاميرات", "Receive cameras"), 26f, Ui.TEXT, true)))
        val codeView = Ui.text(this, "", 56f, Ui.GOLD, true)
        codeView.gravity = Gravity.CENTER
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
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        col.addView(status, Ui.below(this, 28))
        // while waiting: «Cancel»; once cameras arrive it becomes the gold «Done» and the remote goes to it
        val done = Ui.button(this, t("إلغاء", "Cancel")) { finish() }
        col.addView(done, Ui.below(this, 24))

        val r = Transfer.Receiver(this, Transfer.newCode()) { from, n ->
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                status.text = t("✓ وصلت ", "✓ Received ") + Ui.cameras(this, n) +
                    (if (from.isNotEmpty()) t(" من «$from»", " from “$from”") else "")
                status.setTextColor(Ui.GREEN)
                done.text = t("تم", "Done")
                done.background = Ui.states(this, Ui.GOLD, Ui.GOLD_DOWN)
                done.setTextColor(Ui.BG)
                done.requestFocus()
            }
        }
        r.onCode = { code, wrong ->
            runOnUiThread {
                if (!isFinishing) {
                    if (r.isStopped) {
                        codeView.setTextColor(Ui.MUTED)
                        status.text = t("توقّف الاستقبال بعد 5 محاولات برمز غير صحيح. افتح هذه الشاشة من جديد.",
                            "Receiving stopped after 5 tries with a wrong code. Open this screen again.")
                        status.setTextColor(Ui.RED)
                    } else {
                        showCode(codeView, code)
                        if (wrong) {
                            status.text = t("كُتب رمز غير صحيح على الجهاز الآخر. اكتب الرمز الجديد.",
                                "A wrong code was typed on the other device. Type the new code.")
                            status.setTextColor(Ui.TEXT)
                        }
                    }
                }
            }
        }
        showCode(codeView, r.code)
        if (!r.start()) status.text = t("تعذّر بدء الاستقبال. تأكد من الاتصال بالواي فاي.", "Could not start receiving. Check the Wi-Fi connection.")
        receiver = r
        done.requestFocus()
    }

    private fun showCode(v: TextView, code: String) {
        v.text = code.toCharArray().joinToString("  ")
        v.contentDescription = t("الرمز ", "Code ") + code.toCharArray().joinToString(" ")
    }

    // ---------- send
    private lateinit var peerList: LinearLayout
    private lateinit var searchStatus: TextView
    private lateinit var progress: ProgressBar
    private val checks = ArrayList<Pair<CheckBox, Camera>>()

    private fun buildSend() {
        col.addView(Ui.heading(Ui.text(this, t("إرسال الكاميرات", "Send cameras"), 26f, Ui.TEXT, true)))
        col.addView(
            Ui.text(
                this,
                t(
                    "على الجهاز الآخر (التلفزيون أو جوال أحد أفراد العائلة): الإعدادات ← «استقبال الكاميرات». يجب أن يكون الجهازان على نفس الواي فاي.",
                    "On the other device (the TV or a family phone): Settings → “Receive cameras”. Both must be on the same Wi-Fi.",
                ),
                15f, Ui.MUTED,
            ).also { it.setLineSpacing(0f, 1.3f) },
            Ui.below(this, 8),
        )

        // the receiving devices first: the camera list can be long
        col.addView(Ui.heading(Ui.text(this, t("الأجهزة المستقبِلة", "Receiving devices"), 17f, Ui.TEXT, true)), Ui.below(this, 20))
        searchStatus = Ui.text(this, "", 15f, Ui.MUTED)
        searchStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        col.addView(searchStatus, Ui.below(this, 6))
        progress = ProgressBar(this)
        col.addView(progress, LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)).also { it.topMargin = Ui.dp(this, 6) })
        peerList = LinearLayout(this)
        peerList.orientation = LinearLayout.VERTICAL
        col.addView(peerList, Ui.below(this, 6))
        col.addView(Ui.button(this, t("إعادة البحث", "Search again")) { search() }, Ui.below(this, 16))

        col.addView(Ui.heading(Ui.text(this, t("الكاميرات التي تُرسل", "Cameras to send"), 17f, Ui.TEXT, true)), Ui.below(this, 24))
        val cams = Store.all(this)
        if (cams.isEmpty()) {
            col.addView(Ui.text(this, t("لا توجد كاميرات في هذا الجهاز.", "There are no cameras on this device."), 15f, Ui.MUTED), Ui.below(this, 6))
        }
        for (cam in cams) {
            val cb = Ui.check(this, cam.name + (if (cam.group.isNotEmpty()) "  ·  " + cam.group else ""), true)
            checks.add(Pair(cb, cam))
            col.addView(cb, Ui.below(this, 2))
        }
        search()
    }

    private fun search() {
        if (searching) return
        searching = true
        peers.clear()
        peerList.removeAllViews()
        progress.visibility = View.VISIBLE
        searchStatus.text = t("جارٍ البحث عن الأجهزة على الواي فاي…", "Looking for devices on the Wi-Fi…")
        thread {
            Transfer.discover(this, 8000) { peer -> runOnUiThread { addPeer(peer) } }
            runOnUiThread {
                searching = false
                if (isFinishing) return@runOnUiThread
                progress.visibility = View.GONE
                searchStatus.text = if (peers.isEmpty())
                    t("لم أجد جهازًا. افتح «استقبال الكاميرات» على الجهاز الآخر ثم «إعادة البحث».",
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

    /** The code dialog; [again] = the last code was wrong (the other device now shows a new one). */
    private fun askCode(peer: Transfer.Peer, again: Boolean = false) {
        val chosen = checks.filter { it.first.isChecked }.map { it.second }
        if (chosen.isEmpty()) {
            toast(t("اختر كاميرا واحدة على الأقل.", "Choose at least one camera."))
            return
        }
        val field = Ui.field(this, t("الرمز الظاهر على الجهاز الآخر", "The code shown on the other device"), InputType.TYPE_CLASS_NUMBER)
        field.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(4))
        val box = LinearLayout(this)
        val p = Ui.dp(this, 20)
        box.setPadding(p, p / 2, p, 0)
        box.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val b = AlertDialog.Builder(this)
            .setTitle(t("إرسال إلى «${peer.name}»", "Send to “${peer.name}”"))
            .setView(box)
            .setPositiveButton(t("إرسال", "Send")) { _, _ -> send(peer, field.text.toString().trim(), chosen) }
            .setNegativeButton(t("إلغاء", "Cancel"), null)
        if (again) b.setMessage(t("الرمز غير صحيح. اكتب الرمز الظاهر الآن على الجهاز الآخر.", "Wrong code. Type the code now shown on the other device."))
        val dialog = b.create()
        // the keyboard opens with the dialog (also on the TV)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        field.requestFocus()
    }

    private fun send(peer: Transfer.Peer, code: String, chosen: List<Camera>) {
        thread {
            val r = Transfer.send(this, peer, code, chosen)
            runOnUiThread {
                if (!isFinishing) {
                    when (r) {
                        "ok" -> toast(t("✓ أُرسلت ", "✓ Sent ") + Ui.cameras(this, chosen.size))
                        "bad" -> askCode(peer, again = true)
                        else -> toast(t("تعذّر الإرسال. تأكد أن «استقبال الكاميرات» مفتوح على الجهاز الآخر.",
                            "Could not send. Make sure “Receive cameras” is open on the other device."))
                    }
                }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
