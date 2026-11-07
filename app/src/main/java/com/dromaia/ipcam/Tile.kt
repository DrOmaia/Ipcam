package com.dromaia.ipcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/**
 * One camera on screen: its live video, its name, a small «جاري الاتصال…» line, and the reason when it cannot
 * connect (then it retries by itself every 5 seconds).
 * The picture is drawn on a TextureView (res/layout/player_texture.xml), so it can be zoomed with the fingers
 * (setZoomable), saved as a photo (snapshot) and recorded (Recorder reads [textureView]).
 * [act] is any Context (an Activity normally).
 */
class Tile(
    private val act: Context,
    val cam: Camera,
    var main: Boolean,
    showName: Boolean,
    private val onTap: () -> Unit,
) {
    val view = FrameLayout(act)
    private val video = LayoutInflater.from(act).inflate(R.layout.player_texture, view, false) as PlayerView
    private val status = Ui.text(act, "", 15f, Ui.TEXT) // why it does not play
    private val line = Ui.text(act, "", 12f, Ui.TEXT)   // «جاري الاتصال…»
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { start() }

    /** true once the first picture arrived (a photo or a recording can be taken). */
    var hasPicture = false
        private set

    var muted = true
        set(v) {
            field = v
            player?.volume = if (v) 0f else 1f
        }

    /** The view the picture is drawn on (zoom, photos and recordings use it). */
    val textureView: TextureView? get() = video.videoSurfaceView as? TextureView

    // zoom with the fingers: 1× to 5×, moved with one finger while zoomed
    private var zoomable = false
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                status.visibility = View.GONE
                line.visibility = View.GONE
            } else if (state == Player.STATE_ENDED) {
                // a live stream never ends by itself: the camera stopped sending
                retryLater(L.t(act, "توقّف البث من الكاميرا", "The camera stopped sending"))
            }
        }

        override fun onRenderedFirstFrame() {
            hasPicture = true
            status.visibility = View.GONE
            line.visibility = View.GONE
        }

        override fun onPlayerError(error: PlaybackException) {
            retryLater(reason(act, error))
        }
    }

    private val scaler = ScaleGestureDetector(act, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            view.performClick()
            return true
        }

        // double tap: 2.5× on that spot, or back to the whole picture
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.01f) resetZoom() else zoomTo(2.5f, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom > 1f && !scaler.isInProgress) {
                panX -= distanceX
                panY -= distanceY
                applyZoom()
            }
            return true
        }
    })

    private val touch = View.OnTouchListener { _, e ->
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        true
    }

    init {
        view.setBackgroundColor(Color.BLACK)
        view.isFocusable = true
        view.isClickable = true
        view.foreground = Ui.states(act, Color.TRANSPARENT, 0x33FFFFFF, 4)
        view.contentDescription = cam.name
        view.setOnClickListener { onTap() }

        video.setKeepContentOnPlayerReset(true)
        video.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        // the remote's focus stays on the tile (gold ring), never inside the player
        video.isFocusable = false
        video.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        view.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // keep the zoom inside the picture when the picture changes size (rotation, mini window)
        textureView?.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (zoom > 1f && (r - l != or2 - ol || b - t != ob - ot)) applyZoom()
        }

        status.gravity = Gravity.CENTER
        val p = Ui.dp(act, 16)
        status.setPadding(p, p, p, p)
        status.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        status.visibility = View.GONE
        view.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val q = Ui.dp(act, 6)
        line.setPadding(q * 2, q / 2, q * 2, q / 2)
        line.background = Ui.round(0x99000000.toInt(), Ui.dp(act, 8).toFloat())
        line.visibility = View.GONE
        val llp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        llp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        llp.setMargins(q, q, q, q)
        view.addView(line, llp)

        if (showName) {
            val name = Ui.text(act, cam.name, 13f, Ui.TEXT, true)
            name.setPadding(q * 2, q, q * 2, q)
            name.background = Ui.round(0x99000000.toInt(), Ui.dp(act, 8).toFloat())
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.setMargins(q, q, q, q)
            view.addView(name, lp)
        }
    }

    private fun showError(msg: String) {
        line.visibility = View.GONE
        status.text = msg
        status.visibility = View.VISIBLE
    }

    private fun showLine(msg: String) {
        status.visibility = View.GONE
        line.text = msg
        line.visibility = View.VISIBLE
    }

    private fun retryLater(msg: String) {
        showError(msg + "\n" + L.t(act, "إعادة المحاولة بعد ٥ ثوانٍ…", "Retrying in 5 seconds…"))
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, 5000)
    }

    fun start() {
        stop()
        val url = cam.rtsp(main)
        if (!url.startsWith("rtsp://", ignoreCase = true)) {
            showError(L.t(act, "رابط البث غير صحيح. عدّل الكاميرا", "The stream link is not valid. Edit the camera"))
            return
        }
        showLine(L.t(act, "جاري الاتصال…", "Connecting…"))
        // small buffer: the picture stays close to live
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(1000, 3000, 250, 500).build()
        val p = ExoPlayer.Builder(act).setLoadControl(load).build()
        p.volume = if (muted) 0f else 1f
        p.addListener(listener)
        // RTSP over TCP: steadier on home Wi-Fi than UDP
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(10_000)
            .createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()
        video.player = p
        player = p
    }

    fun stop() {
        handler.removeCallbacks(retry)
        hasPicture = false
        val p = player ?: return
        player = null
        video.player = null
        p.removeListener(listener)
        p.release()
    }

    /** Pinch / double-tap zoom and moving the zoomed picture (one camera alone). Off = back to the whole picture. */
    fun setZoomable(on: Boolean) {
        zoomable = on
        if (on) {
            view.setOnTouchListener(touch)
        } else {
            view.setOnTouchListener(null)
            resetZoom()
        }
    }

    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
        applyZoom()
    }

    // zoom to [z] keeping the spot under the fingers (fx, fy in the tile) in place
    private fun zoomTo(z: Float, fx: Float, fy: Float) {
        val tex = textureView ?: return
        if (!zoomable) return
        val nz = z.coerceIn(1f, 5f)
        val c = centreInTile(tex)
        val px = fx - c[0]
        val py = fy - c[1]
        val f = nz / zoom
        panX = px - (px - panX) * f
        panY = py - (py - panY) * f
        zoom = nz
        applyZoom()
    }

    // the picture never leaves its frame: the move is limited to what the zoom added
    private fun applyZoom() {
        val tex = textureView ?: return
        if (zoom <= 1.001f) {
            zoom = 1f
            panX = 0f
            panY = 0f
        }
        val maxX = (zoom - 1f) * tex.width / 2f
        val maxY = (zoom - 1f) * tex.height / 2f
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        tex.pivotX = tex.width / 2f
        tex.pivotY = tex.height / 2f
        tex.scaleX = zoom
        tex.scaleY = zoom
        tex.translationX = panX
        tex.translationY = panY
    }

    // centre of the (unzoomed) picture in the tile's coordinates
    private fun centreInTile(v: View): FloatArray {
        var x = v.left + v.width / 2f
        var y = v.top + v.height / 2f
        var p = v.parent
        while (p is View && p !== view) {
            val pv: View = p
            x += pv.left - pv.scrollX
            y += pv.top - pv.scrollY
            p = pv.parent
        }
        return floatArrayOf(x, y)
    }

    /** Size of the picture (width, height), at most [maxSide] on the long side (0 = as is); (0, 0) when unknown. */
    fun frameSize(maxSide: Int): Pair<Int, Int> {
        var w = 0f
        var h = 0f
        val vs = player?.videoSize
        if (vs != null && vs.width > 0 && vs.height > 0) {
            w = vs.width * (if (vs.pixelWidthHeightRatio > 0f) vs.pixelWidthHeightRatio else 1f)
            h = vs.height.toFloat()
        } else {
            val tex = textureView
            if (tex != null && tex.width > 0 && tex.height > 0) {
                w = tex.width.toFloat()
                h = tex.height.toFloat()
            }
        }
        if (w <= 0f || h <= 0f) return Pair(0, 0)
        val big = maxOf(w, h)
        if (maxSide in 1 until big.toInt()) {
            val k = maxSide / big
            w *= k
            h *= k
        }
        return Pair(w.toInt(), h.toInt())
    }

    /** The picture now (whole picture, not the zoom), at most 1920 px; null before the first picture. Main thread. */
    fun snapshot(): Bitmap? {
        val tex = textureView ?: return null
        if (!hasPicture || !tex.isAvailable) return null
        return try {
            val (w, h) = frameSize(1920)
            if (w > 0 && h > 0) tex.getBitmap(w, h) else tex.bitmap
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** The reason in plain words (Arabic or English). */
        fun reason(c: Context, e: PlaybackException): String {
            val all = StringBuilder()
            var t: Throwable? = e
            while (t != null) {
                all.append(t.message ?: "").append(' ')
                t = t.cause
            }
            val m = all.toString()
            return when {
                m.contains("401") || m.contains("unauthorized", ignoreCase = true) ->
                    L.t(c, "اسم المستخدم أو كلمة المرور غير صحيحة", "Wrong user name or password")
                m.contains("404") || m.contains("454") ->
                    L.t(
                        c,
                        "الكاميرا ردّت لكن رابط البث غير صحيح (تأكد من النوع ورقم القناة)",
                        "The camera answered but the stream link is wrong (check the make and the channel number)",
                    )
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    e.errorCode == PlaybackException.ERROR_CODE_TIMEOUT ->
                    L.t(
                        c,
                        "تعذّر الوصول إلى الكاميرا. تأكد أنها تعمل وأن الجهاز على شبكة الواي فاي نفسها",
                        "Cannot reach the camera. Make sure it is on and this device is on the same Wi-Fi",
                    )
                e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                    L.t(
                        c,
                        "الجهاز لا يدعم صيغة هذا البث. جرّب «جودة خفيفة»، أو غيّر الترميز في الكاميرا إلى H.264",
                        "This device cannot play this stream format. Try «Light quality», or set the camera's encoding to H.264",
                    )
                else -> L.t(c, "تعذّر التشغيل (${e.errorCodeName})", "Cannot play (${e.errorCodeName})")
            }
        }
    }
}
