package com.dromaia.ipcam

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** One camera on screen: its live video, its name, and a message when it cannot connect (then it retries by itself). */
class Tile(
    private val act: Activity,
    val cam: Camera,
    var main: Boolean,
    showName: Boolean,
    onTap: () -> Unit,
) {
    val view = FrameLayout(act)
    private val video = PlayerView(act)
    private val status = Ui.text(act, "", 15f, Ui.TEXT)
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { start() }
    var muted = true
        set(v) {
            field = v
            player?.volume = if (v) 0f else 1f
        }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) status.visibility = View.GONE
        }

        override fun onPlayerError(error: PlaybackException) {
            show(reason(error) + "\nإعادة المحاولة بعد ٥ ثوانٍ…")
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, 5000)
        }
    }

    init {
        view.setBackgroundColor(Color.BLACK)
        view.isFocusable = true
        view.isClickable = true
        view.foreground = Ui.states(act, Color.TRANSPARENT, 0x33FFFFFF, 4)
        view.setOnClickListener { onTap() }

        video.useController = false
        video.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        video.setShutterBackgroundColor(Color.BLACK)
        video.setKeepContentOnPlayerReset(true)
        video.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        view.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        status.gravity = Gravity.CENTER
        val p = Ui.dp(act, 16)
        status.setPadding(p, p, p, p)
        view.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        if (showName) {
            val name = Ui.text(act, cam.name, 13f, Ui.TEXT, true)
            val q = Ui.dp(act, 6)
            name.setPadding(q * 2, q, q * 2, q)
            name.background = Ui.round(0x99000000.toInt(), Ui.dp(act, 8).toFloat())
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.setMargins(q, q, q, q)
            view.addView(name, lp)
        }
    }

    private fun show(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
    }

    fun start() {
        stop()
        val url = cam.rtsp(main)
        if (!url.startsWith("rtsp://", ignoreCase = true)) {
            show("رابط البث غير صحيح. عدّل الكاميرا")
            return
        }
        show("جاري الاتصال…")
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
        val p = player ?: return
        player = null
        video.player = null
        p.removeListener(listener)
        p.release()
    }

    companion object {
        /** The reason in plain Arabic. */
        fun reason(e: PlaybackException): String {
            val all = StringBuilder()
            var t: Throwable? = e
            while (t != null) {
                all.append(t.message ?: "").append(' ')
                t = t.cause
            }
            val m = all.toString()
            return when {
                m.contains("401") || m.contains("unauthorized", ignoreCase = true) ->
                    "اسم المستخدم أو كلمة المرور غير صحيحة"
                m.contains("404") || m.contains("454") ->
                    "الكاميرا ردّت لكن رابط البث غير صحيح (تأكد من النوع ورقم القناة)"
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    e.errorCode == PlaybackException.ERROR_CODE_TIMEOUT ->
                    "ما قدرت أوصل للكاميرا. تأكد أنها شغّالة وأن الجهاز على نفس الواي فاي"
                e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                    "الجهاز لا يدعم صيغة هذا البث. جرّب «جودة خفيفة»، أو غيّر الترميز في الكاميرا إلى H.264"
                else -> "تعذّر التشغيل (${e.errorCodeName})"
            }
        }
    }
}
