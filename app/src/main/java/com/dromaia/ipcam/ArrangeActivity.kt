package com.dromaia.ipcam

import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** «ترتيب الكاميرات»: the order of the cameras inside each group, and the order of the groups, as on the home screen
 *  and in «all cameras». Phone: drag a camera by its ☰ (a group by its title). TV: OK on a camera (or a group title)
 *  picks it up in gold, ▲ ▼ move it, OK puts it down. TalkBack: «move up» / «move down» actions. Saved at once. */
class ArrangeActivity : Base() {
    private lateinit var scroll: ScrollView
    private lateinit var blocks: LinearLayout // one block per group: its title, then a box of its cameras

    private var picked: View? = null   // TV: the camera row / group block being moved with ▲ ▼
    private var dragging: View? = null // phone: the row / block under the finger
    private var lastY = 0f

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, if (tv) 48 else 20)
        col.setPadding(p, p, p, p)
        scroll.addView(col)
        col.addView(Ui.heading(Ui.text(this, t("ترتيب الكاميرات", "Arrange cameras"), 26f, Ui.TEXT, true)))
        val how = Ui.text(this,
            if (tv) t("اضغط OK على كاميرا لتحملها، ثم ▲ ▼ لتحريكها، ثم OK لتضعها. وكذلك عنوان المجموعة لتحريك المجموعة كلها.",
                "Press OK on a camera to pick it up, ▲ ▼ to move it, then OK to put it down. A group's title moves the whole group.")
            else t("اسحب الكاميرا من ☰ إلى مكانها الجديد، واسحب عنوان المجموعة لتحريك المجموعة كلها.",
                "Drag a camera by ☰ to its new place; drag a group's title to move the whole group."),
            14f, Ui.MUTED)
        how.setLineSpacing(0f, 1.25f)
        col.addView(how, Ui.below(this, 8))
        blocks = LinearLayout(this)
        blocks.orientation = LinearLayout.VERTICAL
        col.addView(blocks, Ui.below(this, 8))
        col.addView(Ui.button(this, t("تم", "Done"), primary = true) { finish() }, Ui.below(this, 20))
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    // the cameras by group, in the home screen's order (a camera with no group comes last, under «no group»)
    // [focus]: the camera id (or "g:" + group) the remote goes back to after a rebuild
    private fun build(focus: String? = null) {
        picked = null
        dragging = null
        blocks.removeAllViews()
        val cams = Store.all(this)
        if (cams.isEmpty()) {
            blocks.addView(Ui.text(this, t("لا توجد كاميرات بعد.", "No cameras yet."), 15f, Ui.MUTED), Ui.below(this, 12))
            return
        }
        val order = cams.map { it.group.trim() }.distinct().sortedBy { it.isEmpty() }
        val named = order.count { it.isNotEmpty() }
        var first: View? = null
        val keys = HashMap<String, View>()
        for (g in order) {
            val block = LinearLayout(this)
            block.orientation = LinearLayout.VERTICAL
            block.tag = g
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            if (g.isNotEmpty() || order.size > 1) {
                val movable = g.isNotEmpty() && named > 1 // «no group» always stays last, as on the home screen
                val title = Ui.text(this, (if (movable) "☰  " else "") + g.ifEmpty { t("بدون مجموعة", "No group") }, 18f, Ui.GOLD, true)
                Ui.heading(title)
                val pad = Ui.dp(this, 10)
                title.setPadding(pad, pad, pad, pad)
                title.minHeight = Ui.dp(this, 48)
                title.gravity = Gravity.CENTER_VERTICAL
                if (movable) {
                    title.background = Ui.states(this, Ui.BG, Ui.CARD_DOWN, 10)
                    title.contentDescription = t("مجموعة $g، اسحبها لتغيير ترتيب المجموعات", "Group $g, drag to change the order of the groups")
                    if (tv) {
                        title.isFocusable = true
                        title.setOnClickListener { pick(block, title) }
                        keys["g:$g"] = title
                    } else {
                        title.setOnTouchListener { v, e -> drag(block, blocks, v, e) }
                    }
                    moveActions(title, block, blocks)
                }
                block.addView(title, Ui.below(this, 14))
            }
            for (cam in cams.filter { it.group.trim() == g }) {
                val row = row(cam, box)
                box.addView(row, Ui.below(this, 6))
                keys[cam.id] = row
                if (first == null) first = row
            }
            block.addView(box)
            blocks.addView(block)
        }
        if (tv) (focus?.let { keys[it] } ?: first)?.let { f -> f.post { f.requestFocus() } }
    }

    // one camera: its name (and address), and ☰ to drag it on a phone
    private fun row(cam: Camera, box: LinearLayout): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.tag = cam.id
        row.minimumHeight = Ui.dp(this, 56)
        val pad = Ui.dp(this, 12)
        row.setPaddingRelative(pad, Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6))
        row.background = Ui.states(this, Ui.CARD, Ui.CARD_DOWN)
        val name = Ui.text(this, cam.name.ifBlank { cam.where() }, 16f, Ui.TEXT, true)
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.contentDescription = cam.name.ifBlank { cam.where() }
        if (tv) {
            row.isFocusable = true
            row.setOnClickListener { pick(row, name) }
        } else {
            val handle = Ui.text(this, "☰", 22f, Ui.GOLD, true)
            handle.gravity = Gravity.CENTER
            handle.contentDescription = t("اسحب لتغيير مكان ${row.contentDescription}", "Drag to move ${row.contentDescription}")
            handle.setOnTouchListener { v, e -> drag(row, box, v, e) }
            val size = Ui.dp(this, 52)
            row.addView(handle, LinearLayout.LayoutParams(size, size))
        }
        moveActions(row, row, box)
        return row
    }

    // ---------------------------------------------------------------- phone: dragging with the finger

    // [item] follows the finger inside [box]; it changes place when it passes half of a neighbour. The neighbour is
    // moved, never the item itself (removing the view under the finger would end the touch).
    private fun drag(item: View, box: LinearLayout, handle: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = item
                lastY = e.rawY
                handle.parent?.requestDisallowInterceptTouchEvent(true) // the page does not scroll under the finger
                item.elevation = Ui.dp(this, 8).toFloat()
                item.alpha = 0.92f
                item.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging !== item) return false
                item.translationY += e.rawY - lastY
                lastY = e.rawY
                edgeScroll(item, e.rawY)
                while (true) {
                    val i = box.indexOfChild(item)
                    val up = item.translationY < 0
                    val other = box.getChildAt(if (up) i - 1 else i + 1) ?: break
                    if (!movable(other)) break
                    val step = other.height + gapOf(other)
                    if (kotlin.math.abs(item.translationY) < step / 2f) break
                    swapWith(box, item, other, up)
                    item.translationY += if (up) step else -step
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging !== item) return false
                dragging = null
                item.animate().translationY(0f).setDuration(120).start()
                item.elevation = 0f
                item.alpha = 1f
                save()
            }
        }
        return true
    }

    // near the top or the bottom of the screen the page scrolls; the item stays under the finger
    private fun edgeScroll(item: View, y: Float) {
        val loc = IntArray(2)
        scroll.getLocationOnScreen(loc)
        val zone = Ui.dp(this, 72)
        val d = when {
            y < loc[1] + zone -> -Ui.dp(this, 14)
            y > loc[1] + scroll.height - zone -> Ui.dp(this, 14)
            else -> 0
        }
        if (d == 0) return
        val before = scroll.scrollY
        scroll.scrollBy(0, d)
        item.translationY += (scroll.scrollY - before).toFloat()
    }

    // ---------------------------------------------------------------- TV: OK picks up, ▲ ▼ move, OK puts down

    private fun pick(item: View, label: TextView) {
        if (picked != null) { // OK again: put it down (the rebuild makes new rows)
            drop()
            return
        }
        picked = item
        val face = if (item is LinearLayout && item.tag is String && item.parent === blocks) label else item
        face.background = Ui.round(Ui.GOLD, Ui.dp(this, 14).toFloat())
        label.setTextColor(Ui.BG)
        item.announceForAccessibility(t("حملتها، استعمل ▲ ▼ ثم OK", "Picked up, use ▲ ▼ then OK"))
    }

    private fun drop() {
        val p = picked ?: return
        picked = null
        save()
        // back to the normal colours, the remote still on the camera (or group) just moved
        build(if (p.parent === blocks) "g:" + (p.tag as? String ?: "") else p.tag as? String)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = picked
        if (p != null && event.action == KeyEvent.ACTION_DOWN) {
            val box = p.parent as? LinearLayout
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (box != null) step(p, box, event.keyCode == KeyEvent.KEYCODE_DPAD_UP)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true // stay on it while carrying
                KeyEvent.KEYCODE_BACK -> {
                    drop()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** One place up / down inside its box (also TalkBack's «move up» / «move down»). false = already at the end. */
    private fun step(item: View, box: LinearLayout, up: Boolean): Boolean {
        val i = box.indexOfChild(item)
        val other = box.getChildAt(if (up) i - 1 else i + 1) ?: return false
        if (!movable(other)) return false
        swapWith(box, item, other, up)
        item.post { item.requestRectangleOnScreen(android.graphics.Rect(0, 0, item.width, item.height), false) }
        return true
    }

    // ---------------------------------------------------------------- TalkBack

    private fun moveActions(target: View, item: View, box: LinearLayout) {
        val upId = View.generateViewId()
        val downId = View.generateViewId()
        target.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(upId, t("تحريك لأعلى", "Move up")))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(downId, t("تحريك لأسفل", "Move down")))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == upId || action == downId) {
                    val moved = step(item, box, action == upId)
                    if (moved) save()
                    host.announceForAccessibility(
                        if (moved) t("تم التحريك", "Moved") else t("لا يمكن التحريك أكثر", "Cannot move further"),
                    )
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    // a camera row, or a group block with a name («no group» stays last)
    private fun movable(v: View): Boolean = v.parent !== blocks || (v.tag as? String)?.isNotEmpty() == true

    private fun gapOf(v: View): Int = (v.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0

    // the neighbour goes to the other side of [item] (the item itself is never taken out)
    private fun swapWith(box: LinearLayout, item: View, other: View, up: Boolean) {
        val lp = other.layoutParams
        box.removeView(other)
        val i = box.indexOfChild(item)
        box.addView(other, if (up) i + 1 else i, lp)
        if (picked === item) item.requestFocus()
    }

    // the cameras in the order now on screen: groups from top to bottom, cameras inside each group
    private fun save() {
        val ids = ArrayList<String>()
        for (b in 0 until blocks.childCount) {
            val block = blocks.getChildAt(b) as? LinearLayout ?: continue
            val box = block.getChildAt(block.childCount - 1) as? LinearLayout ?: continue
            for (r in 0 until box.childCount) (box.getChildAt(r).tag as? String)?.let { ids.add(it) }
        }
        if (ids.isNotEmpty()) Store.reorder(this, ids)
    }
}
