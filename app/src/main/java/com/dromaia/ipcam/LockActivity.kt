package com.dromaia.ipcam

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * After 5 wrong PINs in a row no PIN is accepted for a while: 30 seconds, then 2 minutes, then 10 minutes each time
 * (kept in the settings, so closing the app does not reset it).
 */
class LockActivity : Base() {
    companion object {
        const val SET = "set"
        private const val TRIES = 5
        private const val LONGEST_WAIT = 10 * 60_000L
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

        title = Ui.heading(Ui.text(this, "", 22f, Ui.TEXT, true))
        title.gravity = Gravity.CENTER
        title.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        root.addView(title)
        dots = Ui.text(this, "", 40f, Ui.GOLD, true)
        dots.gravity = Gravity.CENTER
        dots.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        root.addView(dots, Ui.below(this, 16))
        note = Ui.text(this, "", 15f, Ui.MUTED)
        note.gravity = Gravity.CENTER
        note.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
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
                b.setPadding(0, 0, 0, 0) // the big digit fills the square key (no clipping)
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
            val forgot = Ui.button(this, t("نسيت الرمز؟", "Forgot the PIN?")) {
                note.text = t(
                    "لا يمكن فتح التطبيق دون الرمز. الحل: إعدادات الجهاز ← التطبيقات ← كاميرات البيت ← مسح البيانات، ثم أضف الكاميرات من جديد (أو استقبلها من جهاز آخر).",
                    "The app cannot be opened without the PIN. The fix: device Settings → Apps → Home Cameras → Clear data, then add the cameras again (or receive them from another device).",
                )
            }
            forgot.textSize = 14f * Ui.scale(this)
            forgot.setTextColor(Ui.GOLD)
            forgot.background = Ui.states(this, Ui.BG, Ui.CARD_DOWN)
            root.addView(forgot, Ui.below(this, 12).also { it.width = LinearLayout.LayoutParams.WRAP_CONTENT })
        }
        setContentView(root)
        refresh()
        if (!setting) {
            val wait = waitLeft()
            if (wait > 0) note.text = waitText(wait)
        }
        firstKey?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // a PIN screen left behind another screen after the PIN was already typed: close it
        if (!setting && !Lock.needed(this)) finish()
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

    /** Seconds left before a PIN is accepted again (0 = now). */
    private fun waitLeft(): Long {
        val now = System.currentTimeMillis()
        var until = Prefs.pinWait(this)
        // the clock was moved back: never more than the longest wait
        if (until - now > LONGEST_WAIT) {
            until = now + LONGEST_WAIT
            Prefs.putLong(this, "pinWait", until)
        }
        val left = until - now
        return if (left <= 0) 0L else (left + 999) / 1000
    }

    private fun waitText(seconds: Long): String {
        val n = seconds.toInt()
        return t("انتظر " + Ui.count(n, "ثانية واحدة", "ثانيتين", "ثوانٍ", "ثانية"), if (n == 1) "Wait 1 second" else "Wait $n seconds")
    }

    private fun press(k: String) {
        if (!setting) {
            val wait = waitLeft()
            if (wait > 0) {
                note.text = waitText(wait)
                return
            }
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
                note.text = t("الرمزان غير متطابقين. ابدأ من جديد.", "The PINs do not match. Start again.")
            }
        } else if (Lock.check(this, pin)) {
            Prefs.put(this, "pinWrong", 0)
            Prefs.putLong(this, "pinWait", 0L)
            Lock.open()
            finish()
            return
        } else {
            val wrong = Prefs.pinWrong(this) + 1
            Prefs.put(this, "pinWrong", wrong)
            if (wrong % TRIES == 0) {
                // 5 wrong in a row: 30 seconds the first time, then 2 minutes, then 10 minutes
                val ms = when (wrong / TRIES) {
                    1 -> 30_000L
                    2 -> 120_000L
                    else -> LONGEST_WAIT
                }
                Prefs.putLong(this, "pinWait", System.currentTimeMillis() + ms)
                note.text = t("رمز غير صحيح ($TRIES من $TRIES). ", "Wrong PIN ($TRIES of $TRIES). ") + waitText(ms / 1000)
            } else {
                val n = wrong % TRIES
                note.text = t("رمز غير صحيح ($n من $TRIES)", "Wrong PIN ($n of $TRIES)")
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
