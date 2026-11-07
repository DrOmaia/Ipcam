package com.dromaia.ipcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * One camera on screen: its live video, its name, a small «جارٍ الاتصال…» line, and the reason when it cannot
 * connect (then it retries by itself every 5 seconds, except after a wrong password: cameras lock the user after a
 * few failed logins, so then it waits for a tap / OK, see [retryOnce]).
 * TalkBack reads the reason too (the tile's description); one camera alone announces it by itself (live region).
 * The picture is drawn on a TextureView (res/layout/player_texture.xml), so it can be zoomed with the fingers
 * (setZoomable), saved as a photo (snapshot) and recorded (Recorder reads [textureView]).
 * Addresses: [Route.targets] in order (home address / outside address); when one cannot be reached the next one is
 * tried at once, and the one that plays is remembered ([Route.worked]). Through the outside address a small
 * «من خارج البيت» badge shows.
 * [urlFor] = another stream than the live one (e.g. a recording), asked again at every connection; null = live.
 * [act] is any Context (an Activity normally).
 */
class Tile(
    private val act: Context,
    val cam: Camera,
    var main: Boolean,
    showName: Boolean,
    private val urlFor: ((remote: Boolean) -> String)? = null,
    private val onTap: () -> Unit,
) {
    val view = FrameLayout(act)
    private val video = LayoutInflater.from(act).inflate(R.layout.player_texture, view, false) as PlayerView
    private val status = Ui.text(act, "", 15f, Ui.TEXT) // why it does not play
    private val line = Ui.text(act, "", 12f, Ui.TEXT)   // «جارٍ الاتصال…»
    private val awayBadge = Ui.text(act, L.t(act, "🌐 من خارج البيت", "🌐 Away"), 12f, Ui.GOLD, true)
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { start() }
    private val nextTarget = Runnable { connect() }
    private var said = "" // what TalkBack reads after the name now ("" = playing)
    private var targets: List<Route.Target> = emptyList() // the addresses to try, in order
    private var target = 0                                // the one tried now
    private var readyOnce = false                         // this connection already reported that it plays

    /** true while the picture comes through the address from outside the home. */
    var away = false
        private set

    /** Called once per connection when the picture plays (e.g. to start counting the time of a recording). */
    var onPlaying: (() -> Unit)? = null

    /** The stream ended by itself (a recording reached its end). When set, it is not reconnected by itself. */
    var onEnded: (() -> Unit)? = null

    /** A stream error that is not a wrong password: return a message to show it instead and not retry, or null. */
    var onError: ((PlaybackException) -> String?)? = null

    /** Milliseconds played since this connection started (0 when nothing plays). */
    val position: Long get() = player?.currentPosition ?: 0L

    /** The picture is moving now. */
    val isPlaying: Boolean get() = player?.isPlaying == true

    /** true after a wrong user name or password: no automatic retry until [retryOnce] (a tap / OK). */
    var authFailed = false
        private set

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
                playing()
            } else if (state == Player.STATE_ENDED) {
                val end = onEnded
                if (end != null) {
                    handler.removeCallbacks(retry)
                    end()
                } else {
                    // a live stream never ends by itself: the camera stopped sending
                    retryLater(L.t(act, "توقّف البث من الكاميرا", "The camera stopped sending"))
                }
            }
        }

        override fun onRenderedFirstFrame() {
            hasPicture = true
            status.visibility = View.GONE
            line.visibility = View.GONE
            playing()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w("ipcam", "play error " + error.errorCodeName) // the code for the log only, never on screen
            if (isAuth(error)) {
                // a wrong password is not tried again by itself (the camera would lock the user out)
                authFailed = true
                handler.removeCallbacks(retry)
                showError(
                    reason(act, error) + "\n" +
                        L.t(act, "عدّل الكاميرا (✎) وصحّح كلمة المرور", "Edit the camera (✎) and fix the password"),
                )
                return
            }
            val own = try {
                onError?.invoke(error)
            } catch (_: Exception) {
                null
            }
            if (own != null) {
                handler.removeCallbacks(retry)
                showError(own)
            } else if (isNetwork(error) && target + 1 < targets.size) {
                // not reachable at this address: the other one (home / outside) at once, then the 5-second retries
                target++
                handler.removeCallbacks(nextTarget)
                handler.post(nextTarget)
            } else if (isNetwork(error) && targets.size > 1) {
                retryLater(
                    L.t(
                        act,
                        "تعذّر الوصول إلى الكاميرا، لا من داخل البيت ولا من خارجه. تأكد أنها تعمل وأن عنوانها من الخارج صحيح",
                        "Could not reach the camera, neither at home nor from outside. Make sure it is on and its outside address is right",
                    ),
                )
            } else {
                retryLater(reason(act, error))
            }
        }
    }

    // the picture plays: remember which address worked, show the badge when it is the outside one
    private fun playing() {
        if (!readyOnce) {
            readyOnce = true
            val remote = targets.getOrNull(target)?.remote == true
            Route.worked(cam, remote)
            away = remote
            awayBadge.visibility = if (remote) View.VISIBLE else View.GONE
            say("")
            onPlaying?.invoke()
        } else {
            say("")
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
        // one camera alone: TalkBack says by itself when it cannot play (grid tiles stay quiet, four would chatter)
        if (!showName) view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

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
        val side = if (Device.isTv(act)) Ui.dp(act, 48) else p // TV: away from the screen's edges
        status.setPadding(side, p, side, p)
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

        // «من خارج البيت»: in the top corner; one camera alone on a TV keeps it away from the screen's edges
        awayBadge.setPadding(q * 2, q / 2, q * 2, q / 2)
        awayBadge.background = Ui.round(0x99000000.toInt(), Ui.dp(act, 8).toFloat())
        awayBadge.visibility = View.GONE
        val alp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        alp.gravity = Gravity.TOP or Gravity.END
        val am = if (Device.isTv(act) && !showName) Ui.dp(act, 32) else q
        alp.setMargins(am, am, am, am)
        view.addView(awayBadge, alp)

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
        say(msg)
    }

    // what TalkBack reads for this tile: the name (and «from outside the home»), and the reason while it does not play
    private fun say(msg: String) {
        said = msg
        val who = if (away) cam.name + L.t(act, "، من خارج البيت", ", away from home") else cam.name
        val d = if (msg.isEmpty()) who else "$who: $msg"
        if (view.contentDescription?.toString() != d) view.contentDescription = d
    }

    private fun showLine(msg: String) {
        status.visibility = View.GONE
        line.text = msg
        line.visibility = View.VISIBLE
    }

    private fun retryLater(msg: String) {
        showError(msg + "\n" + L.t(act, "إعادة المحاولة بعد 5 ثوانٍ…", "Retrying in 5 seconds…"))
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, 5000)
    }

    fun start() {
        stop()
        authFailed = false
        targets = Route.targets(cam) // home first at home, the outside address first away (whichever worked last)
        target = 0
        connect()
    }

    // connects to the address [target] of [targets]
    private fun connect() {
        release()
        val tg = targets.getOrNull(target)
        val url = try {
            if (tg == null) "" else urlFor?.invoke(tg.remote) ?: cam.rtsp(main, tg.remote)
        } catch (_: Exception) {
            ""
        }
        if (tg == null || !url.startsWith("rtsp://", ignoreCase = true)) {
            showError(L.t(act, "رابط البث غير صحيح. عدّل الكاميرا", "The stream link is not valid. Edit the camera"))
            return
        }
        showLine(
            if (tg.remote) L.t(act, "جارٍ الاتصال من خارج البيت…", "Connecting from outside the home…")
            else L.t(act, "جارٍ الاتصال…", "Connecting…"),
        )
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

    /** After a wrong password: try once more (a tap / OK). false = it was not waiting for that. */
    fun retryOnce(): Boolean {
        if (!authFailed) return false
        start()
        return true
    }

    fun stop() {
        handler.removeCallbacks(retry)
        hasPicture = false
        release()
    }

    /** Stops the stream but keeps its last picture on screen (a photo can still be taken). [start] goes on. */
    fun pause() {
        handler.removeCallbacks(retry)
        release()
        status.visibility = View.GONE
        line.visibility = View.GONE
    }

    // lets go of the player (and of the address badge)
    private fun release() {
        handler.removeCallbacks(nextTarget)
        readyOnce = false
        if (away) {
            away = false
            say(said)
        }
        awayBadge.visibility = View.GONE
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
        // every message of the error and its causes, to recognise the camera's answer
        private fun allText(e: PlaybackException): String {
            val all = StringBuilder()
            var t: Throwable? = e
            while (t != null) {
                all.append(t.message ?: "").append(' ')
                t = t.cause
            }
            return all.toString()
        }

        /** The camera refused the user name or password (RTSP 401). */
        fun isAuth(e: PlaybackException): Boolean {
            val m = allText(e)
            return Regex("""\b401\b""").containsMatchIn(m) || m.contains("unauthorized", ignoreCase = true)
        }

        /** The camera answered but has no such stream (RTSP 404 / 454): wrong link, or no recording at that time. */
        fun notFound(e: PlaybackException): Boolean {
            val m = allText(e)
            return m.contains("404") || m.contains("454")
        }

        private fun decoder(e: PlaybackException): Boolean =
            e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED

        /** Not reached (no answer, refused connection, timeout…): another address may work. */
        fun isNetwork(e: PlaybackException): Boolean = !isAuth(e) && !notFound(e) && !decoder(e)

        /** The reason in plain words (Arabic or English). */
        fun reason(c: Context, e: PlaybackException): String {
            return when {
                isAuth(e) ->
                    L.t(c, "اسم المستخدم أو كلمة المرور غير صحيحة", "Wrong username or password")
                notFound(e) ->
                    L.t(
                        c,
                        "ردّت الكاميرا، لكنّ رابط البث غير صحيح (تأكد من نوع الكاميرا ورقم القناة)",
                        "The camera answered, but the stream link is wrong (check the camera type and the channel number)",
                    )
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    e.errorCode == PlaybackException.ERROR_CODE_TIMEOUT ->
                    L.t(
                        c,
                        "تعذّر الوصول إلى الكاميرا. تأكد أنها تعمل وأن الجهاز على شبكة الواي فاي نفسها",
                        "Could not reach the camera. Make sure it is on and this device is on the same Wi-Fi",
                    )
                e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                    L.t(
                        c,
                        "الجهاز لا يدعم صيغة هذا البث. جرّب «جودة خفيفة» (اضغط زر الجودة)، أو غيّر الترميز في الكاميرا إلى H.264",
                        "This device cannot play this stream format. Try “Low quality” (press the quality button), or set the camera's encoding to H.264",
                    )
                else -> L.t(
                    c,
                    "تعذّر تشغيل البث. تأكد من نوع الكاميرا ورقم القناة.",
                    "Could not play the stream. Check the camera type and channel.",
                )
            }
        }
    }
}
