package com.dromaia.ipcam

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// Small helpers for the screens (built in code, no layout files). Night colours like the prayer-times app.
// On a TV every text is 1.3× bigger (read from the sofa) and every button shows a gold ring when the remote is on it.
object Ui {
    const val BG = 0xFF0E1424.toInt()
    const val CARD = 0xFF18213A.toInt()
    const val CARD_DOWN = 0xFF223052.toInt()
    const val GOLD = 0xFFD4A94F.toInt()
    const val GOLD_DOWN = 0xFFB98F3A.toInt()
    const val TEXT = 0xFFF2EFE8.toInt()
    const val MUTED = 0xFFA9B1C2.toInt()
    const val RED = 0xFFE87A72.toInt()
    const val GREEN = 0xFF6FCF97.toInt()

    private var scaleCache: Float? = null

    /** Text scale: 1.3 on a TV, 1 on a phone. */
    fun scale(c: Context): Float = scaleCache ?: (if (Device.isTv(c)) 1.3f else 1f).also { scaleCache = it }

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    /** Right to left in Arabic, left to right in English (Base does this for every screen). */
    fun rtl(a: Activity) {
        a.window.decorView.layoutDirection = if (L.en(a)) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
    }

    fun text(c: Context, s: String, size: Float = 16f, color: Int = TEXT, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.textSize = size * scale(c)
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    fun round(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radius
        if (strokeWidth > 0) d.setStroke(strokeWidth, strokeColor)
        return d
    }

    // background with a gold ring when chosen with the TV remote, darker while pressed
    fun states(c: Context, normal: Int, pressed: Int, radiusDp: Int = 14): StateListDrawable {
        val r = dp(c, radiusDp).toFloat()
        val s = StateListDrawable()
        s.addState(intArrayOf(android.R.attr.state_pressed), round(pressed, r))
        s.addState(intArrayOf(android.R.attr.state_focused), round(normal, r, dp(c, 3), GOLD))
        s.addState(IntArray(0), round(normal, r))
        return s
    }

    fun button(c: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView {
        val b = text(c, label, 16f, if (primary) BG else TEXT, true)
        b.gravity = Gravity.CENTER
        val p = dp(c, 12)
        b.setPadding(p, p, p, p)
        b.minHeight = dp(c, 52)
        b.background = if (primary) states(c, GOLD, GOLD_DOWN) else states(c, CARD, CARD_DOWN)
        b.isFocusable = true
        b.isClickable = true
        b.setOnClickListener { onClick() }
        return b
    }

    fun card(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        val p = dp(c, 16)
        l.setPadding(p, p, p, p)
        l.background = states(c, CARD, CARD_DOWN)
        l.isFocusable = true
        l.isClickable = true
        return l
    }

    fun field(c: Context, hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val e = EditText(c)
        e.hint = hint
        e.inputType = type
        e.isSingleLine = true
        e.textSize = 17f * scale(c)
        e.setTextColor(TEXT)
        e.setHintTextColor(MUTED)
        val p = dp(c, 12)
        e.setPadding(p, p, p, p)
        e.background = states(c, CARD, CARD, 12)
        e.minHeight = dp(c, 52)
        return e
    }

    // margins for a child of a vertical LinearLayout
    fun below(c: Context, top: Int = 12, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
        lp.topMargin = dp(c, top)
        return lp
    }

    // equal share of a horizontal LinearLayout, with a gap between items
    fun share(c: Context, gap: Int = 6): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginStart = dp(c, gap)
        lp.marginEnd = dp(c, gap)
        return lp
    }

    // correct Arabic number agreement: كاميرا واحدة، كاميرتان، 3 كاميرات، 11 كاميرا
    fun count(n: Int, one: String, two: String, few: String, many: String): String = when {
        n == 1 -> one
        n == 2 -> two
        n % 100 in 3..10 -> "$n $few"
        else -> "$n $many"
    }

    fun cameras(c: Context, n: Int): String = if (L.en(c)) {
        when (n) { 0 -> "No cameras yet"; 1 -> "1 camera"; else -> "$n cameras" }
    } else {
        if (n == 0) "لا توجد كاميرات بعد" else count(n, "كاميرا واحدة", "كاميرتان", "كاميرات", "كاميرا")
    }

    fun devices(c: Context, n: Int): String = if (L.en(c)) {
        when (n) { 0 -> "No device found"; 1 -> "1 device"; else -> "$n devices" }
    } else {
        if (n == 0) "لم أجد أي جهاز" else count(n, "جهاز واحد", "جهازان", "أجهزة", "جهازًا")
    }
}
