package com.dromaia.ipcam

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Live view.
 *  - with extra "id": one camera, full screen, full quality.
 *  - without: all cameras, four per page (light stream), tap one to open it alone.
 */
class WatchActivity : Activity() {
    private lateinit var cams: List<Camera>
    private var single = false
    private var page = 0
    private var hq = true
    private var muted = true
    private val tiles = ArrayList<Tile>()
    private lateinit var grid: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var title: TextView
    private lateinit var pageText: TextView

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Ui.rtl(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getStringExtra("id")
        val all = Store.all(this)
        single = id != null
        cams = if (id != null) all.filter { it.id == id } else all
        if (cams.isEmpty()) {
            finish()
            return
        }
        page = saved?.getInt("page") ?: 0
        hq = saved?.getBoolean("hq", true) ?: true
        muted = saved?.getBoolean("muted", true) ?: true

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        root.addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        bar = makeBar()
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP
        root.addView(bar, lp)
        setContentView(root)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("page", page)
        out.putBoolean("hq", hq)
        out.putBoolean("muted", muted)
    }

    // the video plays only while the screen is in front (no data used in the background)
    override fun onStart() {
        super.onStart()
        hideSystemBars()
        build()
    }

    override fun onStop() {
        releaseAll()
        super.onStop()
    }

    private fun pages(): Int = if (single) 1 else (cams.size + 3) / 4

    private fun makeBar(): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.HORIZONTAL
        b.gravity = Gravity.CENTER_VERTICAL
        b.setBackgroundColor(0xB3000000.toInt())
        val p = Ui.dp(this, 8)
        b.setPadding(p, p, p, p)

        b.addView(small("→ رجوع") { finish() })
        title = Ui.text(this, "", 17f, Ui.TEXT, true)
        title.setPadding(p * 2, 0, p * 2, 0)
        title.isSingleLine = true
        b.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (single) {
            val quality = small(qualityLabel()) {}
            quality.setOnClickListener {
                hq = !hq
                quality.text = qualityLabel()
                tiles.forEach { t -> t.main = hq; t.start() }
            }
            b.addView(quality)
            val sound = small(soundLabel()) {}
            sound.setOnClickListener {
                muted = !muted
                sound.text = soundLabel()
                tiles.forEach { t -> t.muted = muted }
            }
            b.addView(sound)
        } else if (pages() > 1) {
            b.addView(small("‹ السابق") { go(-1) })
            pageText = Ui.text(this, "", 15f, Ui.TEXT)
            pageText.setPadding(p, 0, p, 0)
            b.addView(pageText)
            b.addView(small("التالي ›") { go(1) })
        }
        return b
    }

    private fun qualityLabel() = if (hq) "جودة عالية" else "جودة خفيفة"
    private fun soundLabel() = if (muted) "🔇 الصوت" else "🔊 الصوت"

    private fun small(label: String, onClick: () -> Unit): TextView {
        val t = Ui.button(this, label, false, onClick)
        t.textSize = 14f
        t.minHeight = Ui.dp(this, 40)
        val p = Ui.dp(this, 8)
        t.setPadding(p * 2, p, p * 2, p)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.marginStart = Ui.dp(this, 4)
        t.layoutParams = lp
        return t
    }

    private fun go(step: Int) {
        val n = pages()
        page = ((page + step) % n + n) % n
        build()
    }

    private fun build() {
        releaseAll()
        grid.removeAllViews()
        if (page >= pages()) page = 0
        val shown = if (single) cams else cams.subList(page * 4, minOf(cams.size, page * 4 + 4))
        title.text = if (single) cams[0].name else "كل الكاميرات"
        if (!single && pages() > 1) pageText.text = "${page + 1} / ${pages()}"

        for (cam in shown) {
            val tile = Tile(this, cam, main = single && hq, showName = !single) {
                if (single) toggleBar()
                else startActivity(Intent(this, WatchActivity::class.java).putExtra("id", cam.id))
            }
            tile.muted = muted
            tiles.add(tile)
        }
        layoutTiles(tiles.map { it.view })
        tiles.forEach { it.start() }
        if (!single) bar.visibility = View.VISIBLE
    }

    // 1 → whole screen; 2 → side by side (landscape) or one above the other (portrait); 3–4 → 2 × 2
    private fun layoutTiles(views: List<View>) {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val rows: List<List<View>> = when (views.size) {
            1 -> listOf(views)
            2 -> if (land) listOf(views) else views.map { listOf(it) }
            else -> views.chunked(2)
        }
        val gap = if (views.size > 1) Ui.dp(this, 1) else 0
        val topSpace = if (single) 0 else Ui.dp(this, 56) // room for the bar above the cameras
        grid.setPadding(0, topSpace, 0, 0)
        for (r in rows) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (v in r) {
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                lp.setMargins(gap, gap, gap, gap)
                row.addView(v, lp)
            }
            // a third camera alone keeps the same size as the others
            if (r.size == 1 && views.size == 3) row.addView(View(this), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun toggleBar() {
        bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun releaseAll() {
        tiles.forEach { it.stop() }
        tiles.clear()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }
}
