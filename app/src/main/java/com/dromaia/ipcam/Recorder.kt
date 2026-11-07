package com.dromaia.ipcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView

/**
 * Records what a camera shows into a video in the gallery (Movies/كاميرات البيت): MP4, H.264, no sound.
 * About 15 times a second it copies the picture of the camera's TextureView (at most 1280 px on the long side)
 * into the encoder's surface (MediaRecorder with VideoSource.SURFACE, drawn with lockHardwareCanvas).
 * At most [MAX_MS]. [onDone] is called once, on the main thread: true = the video is in the gallery.
 * start() / stop() are called on the main thread; the encoder and the disk work run on their own thread.
 */
class Recorder(
    private val c: Context,
    private val view: TextureView,
    srcW: Int,
    srcH: Int,
    private val name: String,
    private val onDone: (Recorder, Boolean) -> Unit,
) {
    companion object {
        const val MAX_MS = 60_000L
        const val MAX_SIDE = 1280
        const val FPS = 15
    }

    /** Size of the video: the picture's shape, at most MAX_SIDE, multiples of 16 (what every encoder accepts). */
    val width: Int
    val height: Int

    init {
        var w = if (srcW > 0) srcW.toFloat() else 1280f
        var h = if (srcH > 0) srcH.toFloat() else 720f
        val k = minOf(1f, MAX_SIDE / maxOf(w, h))
        w *= k
        h *= k
        width = maxOf(160, Math.round(w / 16f) * 16)
        height = maxOf(160, Math.round(h / 16f) * 16)
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var frame: Bitmap? = null
    private var startedAt = 0L
    private var running = false  // main thread
    private var finished = false // main thread

    @Volatile private var ready = false // the encoder takes frames
    @Volatile private var busy = false  // the worker is drawing [frame]

    // worker thread only
    private var rec: MediaRecorder? = null
    private var surface: Surface? = null
    private var target: Media.VideoTarget? = null
    private var frames = 0

    /** Recording now (until stop(), 60 seconds or an error). */
    val active: Boolean get() = running

    /** Seconds since the start, for the «● 0:12» timer. */
    val seconds: Int get() = if (startedAt == 0L) 0 else ((SystemClock.elapsedRealtime() - startedAt) / 1000L).toInt()

    /** Starts. false = could not start at all (then onDone is not called). Later failures come through onDone(false). */
    fun start(): Boolean {
        if (running || finished) return running
        val bmp = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            null
        } ?: return false
        val t = HandlerThread("ipcam-recorder")
        try {
            t.start()
        } catch (_: Exception) {
            return false
        }
        frame = bmp
        thread = t
        val w = Handler(t.looper)
        worker = w
        running = true
        startedAt = SystemClock.elapsedRealtime()
        w.post { setup() }
        main.postDelayed(tick, 1000L / FPS)
        return true
    }

    /** Stops and saves (the saving finishes in the background, then onDone). */
    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        val w = worker
        val posted = w?.post(Runnable { close() }) ?: false
        if (!posted) Thread { close() }.start()
    }

    // main thread: copy the picture about 15 times a second, while the worker is free
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (SystemClock.elapsedRealtime() - startedAt >= MAX_MS) {
                stop()
                return
            }
            val bmp = frame
            if (ready && !busy && bmp != null && view.isAvailable) {
                val copied = try {
                    view.getBitmap(bmp)
                    true
                } catch (_: Exception) {
                    false
                }
                if (copied) {
                    busy = true
                    val posted = worker?.post {
                        draw()
                        busy = false
                    } ?: false
                    if (!posted) busy = false
                }
            }
            main.postDelayed(this, 1000L / FPS)
        }
    }

    // worker: make the file and the encoder
    private fun setup() {
        try {
            val tg = Media.newVideo(c, name) ?: throw IllegalStateException("no file")
            target = tg
            val r = newRecorder()
            rec = r
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(width, height)
            r.setVideoFrameRate(FPS)
            r.setVideoEncodingBitRate((width * height * FPS * 0.2f).toInt().coerceIn(1_000_000, 4_000_000))
            val path = tg.path
            val fd = tg.fd
            when {
                path != null -> r.setOutputFile(path)
                fd != null -> r.setOutputFile(fd)
                else -> throw IllegalStateException("no file")
            }
            r.setOnErrorListener { _, _, _ -> main.post { fail() } }
            r.prepare()
            surface = r.surface
            r.start()
            ready = true
            // the first frame at once: MediaRecorder.stop() fails when nothing was encoded
            busy = true
            draw()
            busy = false
        } catch (_: Exception) {
            ready = false
            main.post { fail() }
        }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(c) else MediaRecorder()

    // worker: one frame into the encoder (black behind, in case the picture is not there yet)
    private fun draw() {
        val s = surface ?: return
        if (!ready) return
        val bmp = frame
        try {
            val canvas: Canvas = try {
                s.lockHardwareCanvas()
            } catch (_: Exception) {
                s.lockCanvas(null)
            }
            try {
                canvas.drawColor(Color.BLACK)
                if (bmp != null && !bmp.isRecycled) canvas.drawBitmap(bmp, 0f, 0f, null)
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
            frames++
        } catch (_: Exception) {
        }
    }

    // worker: finish the file; an empty or broken file is deleted
    private fun close() {
        var ok = false
        val r = rec
        if (r != null && ready) {
            // stop() throws when the encoder got nothing: give it a few frames first
            var tries = 0
            while (frames < 3 && tries < 5) {
                draw()
                tries++
                pause(70)
            }
            ok = try {
                r.stop()
                true
            } catch (_: RuntimeException) {
                false
            }
        }
        ready = false
        try {
            r?.reset()
        } catch (_: Exception) {
        }
        try {
            r?.release()
        } catch (_: Exception) {
        }
        rec = null
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        surface = null
        val saved = try {
            target?.finish(c, ok) ?: false
        } catch (_: Exception) {
            false
        }
        target = null
        main.post { done(saved) }
        try {
            thread?.quitSafely()
        } catch (_: Exception) {
        }
    }

    // main thread: the encoder reported an error, or could not be made
    private fun fail() {
        if (running) stop()
    }

    private fun done(saved: Boolean) {
        if (finished) return
        finished = true
        onDone(this, saved)
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
