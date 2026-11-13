package com.dromaia.ipcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * One still picture of a camera, for the live camera widget. Blocks: call it off the main thread.
 *  - Hikvision: its own picture address (ISAPI, [Hik.snapshot]);
 *  - IP Webcam: http://address:port/shot.jpg (with the user name and password when set);
 *  - other makes that speak ONVIF: [Onvif.snapshot];
 *  - otherwise, or when that gives nothing: one frame of the light RTSP stream, decoded by a hidden player
 *    (at most 6 seconds).
 * Every way tries the home address and the outside one in [Route] order. The picture is made small for the widget:
 * at most 640 × 360, RGB_565. A wrong password stops the RTSP way for that camera for 10 minutes (cameras lock a
 * user out after a few failed sign-ins). The password goes only to the camera.
 */
object Snap {
    private const val MAX_W = 640
    private const val MAX_H = 360
    private const val RTSP_MS = 6_000L
    private const val CONNECT_MS = 3_000
    private const val READ_MS = 5_000
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val SKIP_MS = 10 * 60_000L    // a picture address that fails while the video works: skipped 10 min
    private const val REFUSED_MS = 10 * 60_000L // a refused password: no RTSP sign-in for 10 min

    private const val WAY_HIK = "hik"
    private const val WAY_SHOT = "shot"
    private const val WAY_ONVIF = "onvif"

    // "camera id|way" → until when (elapsedRealtime) that picture address is not tried
    private val skipUntil = ConcurrentHashMap<String, Long>()

    // the camera's sign-in → until when (elapsedRealtime) no RTSP sign-in is tried after a refused password
    private val refusedUntil = ConcurrentHashMap<String, Long>()

    /** Another network: what failed there is tried again. */
    fun forgetRefused() {
        skipUntil.clear()
        refusedUntil.clear()
    }

    /** A small picture of [cam] now, or null when none could be had. Never throws. */
    fun grab(c: Context, cam: Camera): Bitmap? {
        val app = c.applicationContext ?: c
        val failed = ArrayList<String>()
        for (way in ways(cam)) {
            if (skipped(cam, way)) continue
            val b = try {
                when (way) {
                    WAY_HIK -> Hik.snapshot(cam)
                    WAY_SHOT -> shot(cam)
                    else -> Onvif.snapshot(cam)
                }
            } catch (_: Throwable) {
                null
            }
            if (b != null) {
                skipUntil.remove(cam.id + "|" + way)
                return small(b)
            }
            failed.add(way)
        }
        // no picture address answered (or the make has none): one frame of the video.
        // A Hikvision password its web language refused a moment ago would be refused by the video too.
        if (cam.brand == Brand.HIKVISION && Hik.refused(cam)) return null
        val frame = try {
            rtspFrame(app, cam)
        } catch (_: Throwable) {
            null
        } ?: return null
        // the video works but the picture addresses do not: go straight to the video for a while
        val until = SystemClock.elapsedRealtime() + SKIP_MS
        for (way in failed) skipUntil[cam.id + "|" + way] = until
        return frame
    }

    /** Makes a picture fit in 640 × 360 (never larger than it was), RGB_565: small enough for a home-screen widget. */
    fun small(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val s = minOf(1f, MAX_W / w.toFloat(), MAX_H / h.toFloat())
        val ow = maxOf(1, (w * s).roundToInt())
        val oh = maxOf(1, (h * s).roundToInt())
        if (ow == w && oh == h && src.config == Bitmap.Config.RGB_565) return src
        return try {
            val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.RGB_565)
            Canvas(out).drawBitmap(src, null, Rect(0, 0, ow, oh), Paint(Paint.FILTER_BITMAP_FLAG))
            out
        } catch (_: Throwable) {
            src
        }
    }

    // the picture addresses to try for this make, in order (the video frame comes after them)
    private fun ways(cam: Camera): List<String> = when {
        cam.brand == Brand.HIKVISION -> listOf(WAY_HIK)
        cam.brand == Brand.IPWEBCAM -> listOf(WAY_SHOT)
        cam.brand == Brand.PHONE -> emptyList()
        cam.brand == Brand.CUSTOM -> if (cam.onvifPort > 0) listOf(WAY_ONVIF) else emptyList()
        cam.brand.onvif -> listOf(WAY_ONVIF)
        else -> emptyList()
    }

    private fun skipped(cam: Camera, way: String): Boolean {
        val key = cam.id + "|" + way
        val until = skipUntil[key] ?: return false
        if (SystemClock.elapsedRealtime() < until) return true
        skipUntil.remove(key, until)
        return false
    }

    // ------------------------------------------------------------------ IP Webcam: shot.jpg

    private fun shot(cam: Camera): Bitmap? {
        for (t in Route.targets(cam)) {
            if (t.host.isEmpty()) continue
            val host = if (t.host.contains(':') && !t.host.startsWith("[")) "[${t.host}]" else t.host
            // IP Webcam serves the video and the pictures on the same port (8080)
            val data = try {
                get("http://$host:${t.rtspPort}/shot.jpg", cam.user, cam.pass)
            } catch (_: Throwable) {
                null
            } ?: continue
            Route.worked(cam, t.remote)
            return decode(data)
        }
        return null
    }

    private fun get(url: String, user: String, pass: String): ByteArray? {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = CONNECT_MS
            con.readTimeout = READ_MS
            con.instanceFollowRedirects = false
            con.useCaches = false
            if (user.isNotEmpty()) {
                val token = Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                con.setRequestProperty("Authorization", "Basic $token")
            }
            if (con.responseCode !in 200..299) return null
            val out = ByteArrayOutputStream()
            con.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
            }
            return out.toByteArray().takeIf { it.isNotEmpty() }
        } finally {
            con.disconnect()
        }
    }

    // a JPEG, read at about the size needed (a phone's 1920-wide picture is read at 960)
    private fun decode(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_W && bounds.outHeight / (sample * 2) >= MAX_H) sample *= 2
        val o = BitmapFactory.Options()
        o.inSampleSize = sample
        o.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeByteArray(data, 0, data.size, o)
    }

    // ------------------------------------------------------------------ one frame of the RTSP light stream

    private class Frame(val bmp: Bitmap?, val refused: Boolean, val unreachable: Boolean)

    private fun rtspFrame(c: Context, cam: Camera): Bitmap? {
        val login = signIn(cam)
        val until = refusedUntil[login]
        if (until != null) {
            if (SystemClock.elapsedRealtime() < until) return null
            refusedUntil.remove(login, until)
        }
        for (t in Route.targets(cam)) {
            val url = try {
                cam.rtsp(false, t.remote)
            } catch (_: Exception) {
                ""
            }
            if (!url.startsWith("rtsp://", ignoreCase = true)) return null
            val f = frame(c, url, cam.brand.tcp)
            if (f.bmp != null) {
                Route.worked(cam, t.remote)
                return f.bmp
            }
            if (f.refused) {
                refusedUntil[login] = SystemClock.elapsedRealtime() + REFUSED_MS
                return null
            }
            if (!f.unreachable) return null // it answered but sent no picture: the other address would not help
        }
        return null
    }

    // the camera's sign-in (address, port, user name, password): the channels of one recorder share it. Memory only.
    private fun signIn(cam: Camera): String =
        listOf(cam.address().lowercase(), cam.rtspPort().toString(), cam.user, cam.pass).joinToString("\u0001")

    /**
     * Plays [url] with a hidden player that draws into an ImageReader (YUV, the decoders' own format) and keeps the
     * first picture. Everything of the player happens on its own thread; at most [RTSP_MS], then all is let go.
     */
    private fun frame(c: Context, url: String, tcp: Boolean): Frame {
        val thread = HandlerThread("ipcam-snap")
        thread.start()
        val h = Handler(thread.looper)
        val done = CountDownLatch(1)
        val out = AtomicReference<Bitmap?>(null)
        val refused = AtomicBoolean(false)
        val unreachable = AtomicBoolean(false)
        val player = AtomicReference<ExoPlayer?>(null)
        val reader = AtomicReference<ImageReader?>(null)
        h.post {
            try {
                // the decoder sets the real picture size; this is only the starting size of the buffers
                val r = ImageReader.newInstance(640, 360, ImageFormat.YUV_420_888, 2)
                reader.set(r)
                r.setOnImageAvailableListener({ ir ->
                    val img = try {
                        ir.acquireLatestImage()
                    } catch (_: Throwable) {
                        null
                    }
                    if (img != null) {
                        try {
                            if (out.get() == null) {
                                val b = toBitmap(img)
                                if (b != null) {
                                    out.set(b)
                                    done.countDown()
                                }
                            }
                        } catch (_: Throwable) {
                        } finally {
                            try {
                                img.close()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }, h)
                val load = DefaultLoadControl.Builder().setBufferDurationsMs(500, 2000, 100, 100).build()
                val p = ExoPlayer.Builder(c).setLooper(thread.looper).setLoadControl(load).build()
                player.set(p)
                p.volume = 0f
                // the picture only: no sound is asked from the camera
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        refused.set(Tile.isAuth(error))
                        unreachable.set(Tile.isNetwork(error))
                        done.countDown()
                    }
                })
                p.setVideoSurface(r.surface)
                val source = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(tcp)
                    .setTimeoutMs(RTSP_MS)
                    .createMediaSource(MediaItem.fromUri(url))
                p.setMediaSource(source)
                p.playWhenReady = true
                p.prepare()
            } catch (_: Throwable) {
                done.countDown()
            }
        }
        val got = try {
            done.await(RTSP_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        // let go of the player and the reader on their own thread, then end the thread
        val released = CountDownLatch(1)
        val posted = h.post {
            try {
                player.getAndSet(null)?.release()
            } catch (_: Throwable) {
            }
            try {
                reader.getAndSet(null)?.close()
            } catch (_: Throwable) {
            }
            released.countDown()
        }
        if (posted) {
            try {
                released.await(3_000, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
        thread.quitSafely()
        val bmp = out.get()
        // no answer at all in time = not reachable at this address (the other one may work)
        return Frame(bmp, bmp == null && refused.get(), bmp == null && (unreachable.get() || !got))
    }

    /** A YUV picture of the decoder → a small RGB_565 picture (BT.601), read straight at the smaller size. */
    private fun toBitmap(img: Image): Bitmap? {
        if (img.format != ImageFormat.YUV_420_888) return null
        val planes = img.planes
        if (planes.size < 3) return null
        var w = img.width
        var h = img.height
        var x0 = 0
        var y0 = 0
        val crop = img.cropRect
        if (crop != null && !crop.isEmpty && crop.right <= w && crop.bottom <= h) {
            x0 = crop.left
            y0 = crop.top
            w = crop.width()
            h = crop.height()
        }
        if (w < 2 || h < 2) return null
        val s = minOf(1f, MAX_W / w.toFloat(), MAX_H / h.toFloat())
        val ow = maxOf(1, (w * s).toInt())
        val oh = maxOf(1, (h * s).toInt())
        val yb = planes[0].buffer
        val ub = planes[1].buffer
        val vb = planes[2].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride
        val yLim = yb.limit()
        val uLim = ub.limit()
        val vLim = vb.limit()
        val px = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val sy = y0 + oy * h / oh
            val yRowAt = sy * yRow
            val cy = sy / 2
            for (ox in 0 until ow) {
                val sx = x0 + ox * w / ow
                val yi = yRowAt + sx * yPix
                val cx = sx / 2
                val ui = cy * uRow + cx * uPix
                val vi = cy * vRow + cx * vPix
                if (yi >= yLim || ui >= uLim || vi >= vLim) continue
                val yy = maxOf(0, (yb.get(yi).toInt() and 0xFF) - 16) * 1192
                val u = (ub.get(ui).toInt() and 0xFF) - 128
                val v = (vb.get(vi).toInt() and 0xFF) - 128
                val r = ((yy + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((yy - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((yy + 2066 * u) shr 10).coerceIn(0, 255)
                px[oy * ow + ox] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.RGB_565)
    }
}
