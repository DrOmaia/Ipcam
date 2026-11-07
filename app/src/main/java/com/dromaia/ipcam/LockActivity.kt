package com.dromaia.ipcam

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The optional 4-digit PIN.
 *  - normal: asked before the app opens (Base starts this screen); Back leaves the app.
 *  - extra "set" = true: choose a new PIN (typed twice); finishes with RESULT_OK when saved.
 * Digits come from the on-screen pad (touch, or the TV remote's arrows + OK) or the remote's number keys.
 */
class LockActivity : Base() {
    companion object {
        const val SET = "set"
        private var wrongCount = 0
        private var waitUntil = 0L
    }

    override val lockable = false
    private var setting = false
    private var first: String? = null
    private val typed = StringBuilder()
    private lateinit var title: TextView
    private lateinit var dots: TextView
    private lateinit var note: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setting = intent.getBooleanExtra(SET, false)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Ui.BG)
        val p = Ui.dp(this, 24)
        root.setPadding(p, p, p, p)

        title = Ui.text(this, "", 22f, Ui.TEXT, true)
        title.gravity = Gravity.CENTER
        root.addView(title)
        dots = Ui.text(this, "", 40f, Ui.GOLD, true)
        dots.gravity = Gravity.CENTER
        root.addView(dots, Ui.below(this, 16))
        note = Ui.text(this, "", 15f, Ui.MUTED)
        note.gravity = Gravity.CENTER
        root.addView(note, Ui.below(this, 8))

        // the pad: 1 2 3 / 4 5 6 / 7 8 9 / ⌫ 0 ✓ — always left to right like a phone keypad
        val pad = LinearLayout(this)
        pad.orientation = LinearLayout.VERTICAL
        pad.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
        var firstKey: View? = null
        for (row in keys) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            for (k in row) {
                val b = Ui.button(this, k, primary = k == "✓") { press(k) }
                b.textSize = 24f * Ui.scale(this)
                b.contentDescription = when (k) {
                    "⌫" -> t("حذف", "Delete")
                    "✓" -> t("تأكيد", "OK")
                    else -> k
                }
                val size = Ui.dp(this, if (tv) 84 else 76)
                val lp = LinearLayout.LayoutParams(size, size)
                lp.setMargins(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6))
                r.addView(b, lp)
                if (firstKey == null) firstKey = b
            }
            pad.addView(r)
        }
        root.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = Ui.dp(this, 20)
        })
        if (!setting) {
            val forgot = Ui.text(this, t("نسيت الرمز؟", "Forgot the PIN?"), 14f, Ui.GOLD)
            forgot.isFocusable = true
            forgot.isClickable = true
            forgot.setPadding(p, p / 2, p, p / 2)
            forgot.setOnClickListener {
                note.text = t(
                    "لا يمكن فتحه بدون الرمز. الحل: إعدادات الجهاز ← التطبيقات ← كاميرات البيت ← مسح البيانات، ثم أضف الكاميرات من جديد (أو استقبلها من جهاز آخر).",
                    "It cannot be opened without the PIN. The fix: device Settings → Apps → Home Cameras → Clear data, then add the cameras again (or receive them from another device).",
                )
            }
            root.addView(forgot, Ui.below(this, 12).also { it.width = LinearLayout.LayoutParams.WRAP_CONTENT })
        }
        setContentView(root)
        refresh()
        firstKey?.requestFocus()
    }

    private fun refresh() {
        title.text = when {
            !setting -> t("أدخل الرمز", "Enter the PIN")
            first == null -> t("اختر رمزًا من 4 أرقام", "Choose a 4-digit PIN")
            else -> t("أعد كتابة الرمز", "Type the PIN again")
        }
        dots.text = (0 until 4).joinToString(" ") { if (it < typed.length) "●" else "○" }
        dots.contentDescription = t("أُدخل ${typed.length} من 4", "${typed.length} of 4 entered")
    }

    private fun press(k: String) {
        val wait = waitUntil - SystemClock.elapsedRealtime()
        if (!setting && wait > 0) {
            note.text = t("انتظر ${(wait / 1000) + 1} ثانية", "Wait ${(wait / 1000) + 1} seconds")
            return
        }
        when (k) {
            "⌫" -> if (typed.isNotEmpty()) typed.deleteCharAt(typed.length - 1)
            "✓" -> submit()
            else -> if (typed.length < 4) {
                typed.append(k)
                if (typed.length == 4) handler.postDelayed({ submit() }, 150)
            }
        }
        refresh()
    }

    private fun submit() {
        if (typed.length < 4) return
        val pin = typed.toString()
        typed.setLength(0)
        if (setting) {
            if (first == null) {
                first = pin
                note.text = ""
            } else if (first == pin) {
                Lock.set(this, pin)
                setResult(RESULT_OK)
                finish()
                return
            } else {
                first = null
                note.text = t("الرمزان غير متطابقين، ابدأ من جديد", "The PINs do not match, start again")
            }
        } else if (Lock.check(this, pin)) {
            wrongCount = 0
            Lock.open()
            finish()
            return
        } else {
            wrongCount++
            if (wrongCount >= 5) {
                wrongCount = 0
                waitUntil = SystemClock.elapsedRealtime() + 30_000
                note.text = t("رمز غير صحيح 5 مرات: انتظر 30 ثانية", "Wrong PIN 5 times: wait 30 seconds")
            } else {
                note.text = t("رمز غير صحيح", "Wrong PIN")
            }
        }
        refresh()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            press((keyCode - KeyEvent.KEYCODE_0).toString())
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            press("⌫")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Back on the lock screen leaves the app (or cancels choosing a PIN)")
    override fun onBackPressed() {
        if (setting) {
            setResult(RESULT_CANCELED)
            finish()
        } else {
            moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
