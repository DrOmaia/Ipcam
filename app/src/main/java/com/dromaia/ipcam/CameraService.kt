package com.dromaia.ipcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Camera mode: this phone becomes a camera that the other devices of the family watch like any IP camera.
 *
 * A foreground service (notification «هذا الجوال يعمل كاميرا», with a Stop button) that keeps running with the screen
 * off: Camera2 → H.264 encoder (and AAC from the microphone when the sound is on) → [RtspServer] on port 8554,
 * announced on the Wi-Fi by [PhoneCams]. It also listens on port 8555 for a viewer who talks (push-to-talk, [Talk])
 * and plays that voice on the loud speaker, one talker at a time (signed in with a challenge, [TalkAuth]).
 * Must be started while [CameraModeActivity] is on screen (Android allows the camera only then).
 */
class CameraService : Service() {
    companion object {
        const val ACTION_STOP = "com.dromaia.ipcam.CAMERA_STOP"
        const val TALK_PORT = 8555
        const val USER = "ipcam"

        // settings of the camera mode (Prefs keys)
        const val K_PASS = "camPass"
        const val K_FRONT = "camFront"
        const val K_QUALITY = "camQuality"
        const val K_SOUND = "camSound"

        private const val NOTE_ID = 8554
        private const val CHANNEL = "camera-mode"

        @Volatile private var instance: CameraService? = null
        @Volatile private var preview: SurfaceTexture? = null

        /** Camera mode is on (the service runs). */
        @Volatile var running = false
            private set

        /** The camera works and the picture flows to the server. */
        @Volatile var streaming = false
            private set

        /** The last problem in plain words ("" = none). */
        @Volatile var problem = ""
            private set

        /** The RTSP port actually used (0 = not running). */
        @Volatile var port = 0
            private set

        // the picture being sent: size, sensor orientation and lens (for the preview on the screen)
        @Volatile var width = 0
            private set
        @Volatile var height = 0
            private set
        @Volatile var sensor = 90
            private set
        @Volatile var isFront = false
            private set

        /** The stream has sound now (the microphone works). */
        @Volatile var soundNow = false
            private set

        /** Address of the device talking through this phone now ("" = nobody). */
        @Volatile var talker = ""
            private set

        /** Called (on any thread) when something on the camera screen should change. */
        @Volatile var listener: (() -> Unit)? = null

        /** Starts camera mode. Call it from the visible camera screen, after the camera permission was granted. */
        fun start(c: Context): Boolean = try {
            val i = Intent(c, CameraService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            true
        } catch (_: Exception) {
            false
        }

        fun stop(c: Context) {
            try {
                c.stopService(Intent(c, CameraService::class.java))
            } catch (_: Exception) {
            }
        }

        /** Camera, quality or sound changed: start again with the new settings (viewers reconnect by themselves). */
        fun restart(c: Context) {
            val s = instance ?: return
            if (!running) return
            s.main.post {
                s.promote()
                s.cam.post { s.startPipeline() }
            }
        }

        /**
         * The picture on the camera screen (null = not shown). [done] runs on the main thread once the camera no
         * longer draws into the previous one (so the screen can release it).
         */
        fun setPreview(st: SurfaceTexture?, done: (() -> Unit)? = null) {
            preview = st
            val main = Handler(Looper.getMainLooper())
            val s = instance
            if (s == null || !running) {
                if (done != null) main.post(done)
                return
            }
            val posted = try { s.cam.post { s.applyPreview(done) } } catch (_: Exception) { false }
            if (!posted && done != null) main.post(done)
        }

        /** The devices watching now (one entry per connection). */
        fun viewers(): List<RtspServer.Viewer> = instance?.server?.viewers() ?: emptyList()

        /** The password the other devices need (6 digits made on first use). */
        fun password(c: Context): String {
            var p = Prefs.str(c, K_PASS)
            if (p.isEmpty()) {
                p = newPassword()
                Prefs.put(c, K_PASS, p)
            }
            return p
        }

        fun newPassword(): String {
            val r = SecureRandom()
            return (1..6).joinToString("") { r.nextInt(10).toString() }
        }

        class Lens(val id: String, val sensor: Int, val front: Boolean)

        /** The camera to use: the asked side if the phone has it, otherwise the first camera. */
        fun lens(c: Context, front: Boolean): Lens? = try {
            val m = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val ids = m.cameraIdList
            val id = ids.firstOrNull { m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want } ?: ids.firstOrNull()
            if (id == null) {
                null
            } else {
                val ch = m.getCameraCharacteristics(id)
                Lens(id, ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
            }
        } catch (_: Exception) {
            null
        }

        /** This phone has a camera on that side. */
        fun hasSide(c: Context, front: Boolean): Boolean = try {
            val m = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            m.cameraIdList.any { m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
        } catch (_: Exception) {
            false
        }

        private fun changed() {
            try {
                listener?.invoke()
            } catch (_: Exception) {
            }
            instance?.noteLater()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var camThread: HandlerThread
    private lateinit var cam: Handler
    @Volatile private var server: RtspServer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var talkServer: ServerSocket? = null
    private val talking = AtomicBoolean(false)
    private val talkSlots = Semaphore(3) // at most 3 talk connections at once; more are closed at once
    @Volatile private var micOk = true
    private var noteCount = -1

    // camera thread only
    private var gen = 0
    private var sessGen = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var previewUsed: SurfaceTexture? = null
    private var noPreview = false
    private var fps: Range<Int>? = null
    private var afVideo = false
    private val retry = Runnable { if (running) startPipeline() }

    // encoders
    @Volatile private var videoCodec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var videoThread: Thread? = null
    private var ptsOffset = Long.MIN_VALUE
    @Volatile private var audioGen = 0
    private var audioThread: Thread? = null
    @Volatile private var lastKeyAsk = 0L

    private fun t(ar: String, en: String) = L.t(this, ar, en)
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        camThread = HandlerThread("ipcam-camera")
        camThread.start()
        cam = Handler(camThread.looper)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) {
            promote() // every start request must be answered with startForeground
            return START_NOT_STICKY
        }
        if (!promote()) {
            problem = t("تعذّر تشغيل وضع الكاميرا. افتح هذه الشاشة وحاول مرة أخرى.", "Could not start camera mode. Open this screen and try again.")
            changed()
            stopSelf()
            return START_NOT_STICKY
        }
        begin()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running) problem = "" // stopped by the user: no old problem left on the screen
        running = false
        streaming = false
        instance = null
        try { PhoneCams.unregister(this) } catch (_: Exception) {}
        server?.stop()
        server = null
        try { talkServer?.close() } catch (_: Exception) {}
        talkServer = null
        cam.removeCallbacksAndMessages(null)
        cam.post { stopPipeline() }
        camThread.quitSafely()
        try { wake?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
        wake = null
        wifi = null
        port = 0
        talker = ""
        changed()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ start

    private fun begin() {
        running = true
        problem = ""
        password(this)
        locksOn()
        val s = RtspServer({ Prefs.str(this, K_PASS) }, { changed() }, { askKeyFrame() })
        server = s
        port = s.start()
        if (port == 0) {
            problem = t("تعذّر فتح منفذ البث في هذا الجوال.", "Could not open the streaming port on this phone.")
        } else {
            PhoneCams.register(this, Device.name(this), port)
        }
        startTalk()
        cam.post { startPipeline() }
        changed()
    }

    /** Shows the notification and tells Android what runs in the background: the camera, and the microphone if used. */
    private fun promote(): Boolean {
        val mic = Prefs.bool(this, K_SOUND, true) && granted(Manifest.permission.RECORD_AUDIO)
        micOk = mic
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val camType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (mic) {
                    try {
                        startForeground(NOTE_ID, note(), camType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    } catch (_: Exception) {
                        micOk = false
                        startForeground(NOTE_ID, note(), camType)
                    }
                } else {
                    startForeground(NOTE_ID, note(), camType)
                }
            } else {
                startForeground(NOTE_ID, note())
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun wantSound() = micOk && Prefs.bool(this, K_SOUND, true) && granted(Manifest.permission.RECORD_AUDIO)

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun locksOn() {
        // the phone keeps sending with the screen off: CPU awake, Wi-Fi without power saving
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ipcam:camera")
            w.setReferenceCounted(false)
            w.acquire()
            wake = w
        } catch (_: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val l = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ipcam:camera")
            l.setReferenceCounted(false)
            l.acquire()
            wifi = l
        } catch (_: Exception) {
        }
    }

    private fun note(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, t("وضع الكاميرا", "Camera mode"), NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, CameraModeActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP), flags)
        val b = newBuilder()
        val n = viewers().map { it.ip }.distinct().size
        b.setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(t("هذا الجوال يعمل كاميرا", "This phone is working as a camera"))
            .setContentText(if (n == 0) t("لا أحد يشاهد الآن", "Nobody is watching now") else t("يشاهد الآن: ", "Watching now: ") + Ui.devices(this, n))
            .setOngoing(true)
            .setShowWhen(false)
            .setColor(Ui.GOLD)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), t("إيقاف", "Stop"), stop).build())
        return b.build()
    }

    @Suppress("DEPRECATION")
    private fun newBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)

    /** Updates the notification when the number of viewers changes. */
    private fun noteLater() {
        main.post {
            if (!running) return@post
            val n = viewers().map { it.ip }.distinct().size
            if (n == noteCount) return@post
            noteCount = n
            try {
                getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, note())
            } catch (_: Exception) {
            }
        }
    }

    private fun fail(msg: String, again: Boolean) {
        problem = msg
        streaming = false
        if (again && running) {
            cam.removeCallbacks(retry)
            cam.postDelayed(retry, 5000)
        }
        changed()
    }

    // ------------------------------------------------------------------ camera (camera thread)

    private fun startPipeline() {
        cam.removeCallbacks(retry)
        stopPipeline()
        if (!running) return
        val myGen = ++gen
        noPreview = false
        val wantFront = Prefs.bool(this, K_FRONT, false)
        val quality = Prefs.int(this, K_QUALITY, 720)
        var sound = wantSound()
        server?.newStream(sound)
        val mgr = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val ln = lens(this, wantFront)
        if (mgr == null || ln == null) return fail(t("لا توجد كاميرا في هذا الجهاز.", "This device has no camera."), false)
        try {
            val ch = mgr.getCameraCharacteristics(ln.id)
            sensor = ln.sensor
            isFront = ln.front
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = chooseSize(map?.getOutputSizes(MediaCodec::class.java), quality)
            width = size.width
            height = size.height
            fps = chooseFps(ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))
            afVideo = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true
            if (!startEncoder(size.width, size.height)) {
                return fail(t("هذا الجوال لا يستطيع ترميز الفيديو بهذه الجودة. جرّب جودة أقل.", "This phone cannot encode video at this quality. Try a lower one."), false)
            }
            if (sound && !startAudio()) {
                sound = false
                server?.audioOff()
            }
            soundNow = sound
            openCamera(mgr, ln.id, myGen)
        } catch (_: Exception) {
            fail(t("تعذّر تشغيل الكاميرا. سأحاول مرة أخرى بعد قليل.", "Could not start the camera. Trying again shortly."), true)
        }
        changed()
    }

    private fun stopPipeline() {
        gen++
        sessGen++
        streaming = false
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        stopAudio()
        stopEncoder()
        try { previewSurface?.release() } catch (_: Exception) {}
        previewSurface = null
        previewUsed = null
        soundNow = false
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(mgr: CameraManager, id: String, myGen: Int) {
        if (!granted(Manifest.permission.CAMERA)) {
            return fail(t("اسمح للتطبيق باستخدام الكاميرا، ثم شغّل وضع الكاميرا من جديد.", "Allow camera access, then start camera mode again."), false)
        }
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (myGen != gen || !running) {
                    d.close()
                    return
                }
                device = d
                createSession(null)
            }

            override fun onDisconnected(d: CameraDevice) {
                d.close()
                if (myGen != gen) return
                device = null
                session = null
                fail(t("الكاميرا يستخدمها تطبيق آخر الآن. سأحاول مرة أخرى بعد قليل.", "Another app is using the camera now. Trying again shortly."), true)
            }

            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                if (myGen != gen) return
                device = null
                session = null
                when (error) {
                    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED ->
                        fail(t("الكاميرا معطّلة في هذا الجوال (سياسة الجهاز).", "The camera is disabled on this phone (device policy)."), false)
                    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE, CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
                        fail(t("الكاميرا يستخدمها تطبيق آخر الآن. سأحاول مرة أخرى بعد قليل.", "Another app is using the camera now. Trying again shortly."), true)
                    else -> fail(t("تعذّر تشغيل الكاميرا. سأحاول مرة أخرى بعد قليل.", "Could not start the camera. Trying again shortly."), true)
                }
            }
        }, cam)
    }

    private fun finish(done: (() -> Unit)?) {
        if (done != null) main.post(done)
    }

    /** The screen showed / hid the picture: send to the encoder only, or to the encoder and the screen. */
    private fun applyPreview(done: (() -> Unit)?) {
        if (device == null || encoderSurface == null) {
            if (preview == null) {
                try { previewSurface?.release() } catch (_: Exception) {}
                previewSurface = null
                previewUsed = null
            }
            finish(done)
            return
        }
        val st = if (noPreview) null else preview
        if (st === previewUsed && session != null) {
            finish(done)
            return
        }
        createSession(done)
    }

    @Suppress("DEPRECATION")
    private fun createSession(done: (() -> Unit)?) {
        val d = device
        val enc = encoderSurface
        if (d == null || enc == null) {
            finish(done)
            return
        }
        val st = if (noPreview) null else preview
        val ps: Surface? = if (st != null) {
            try {
                st.setDefaultBufferSize(width, height)
                Surface(st)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val outs = ArrayList<Surface>()
        outs.add(enc)
        if (ps != null) outs.add(ps)
        val old = previewSurface
        previewSurface = ps
        previewUsed = st
        try { session?.close() } catch (_: Exception) {}
        session = null
        val mySess = ++sessGen
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (mySess != sessGen || device !== d) {
                    try { s.close() } catch (_: Exception) {}
                    if (old !== previewSurface) try { old?.release() } catch (_: Exception) {}
                    finish(done)
                    return
                }
                session = s
                try {
                    val r = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    for (o in outs) r.addTarget(o)
                    r.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    if (afVideo) r.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    fps?.let { r.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    s.setRepeatingRequest(r.build(), null, cam)
                    streaming = true
                    problem = ""
                } catch (_: Exception) {
                    fail(t("تعذّر تشغيل الكاميرا. سأحاول مرة أخرى بعد قليل.", "Could not start the camera. Trying again shortly."), true)
                }
                if (old !== previewSurface) try { old?.release() } catch (_: Exception) {}
                finish(done)
                changed()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (old !== previewSurface) try { old?.release() } catch (_: Exception) {}
                finish(done)
                if (mySess != sessGen || device !== d) return
                if (ps != null) {
                    // this phone cannot show the picture and send it at the same time: send only
                    noPreview = true
                    try { ps.release() } catch (_: Exception) {}
                    previewSurface = null
                    previewUsed = null
                    createSession(null)
                    return
                }
                fail(t("تعذّر تشغيل الكاميرا. سأحاول مرة أخرى بعد قليل.", "Could not start the camera. Trying again shortly."), true)
            }
        }
        try {
            d.createCaptureSession(outs, cb, cam)
        } catch (_: Exception) {
            if (old !== previewSurface) try { old?.release() } catch (_: Exception) {}
            finish(done)
            fail(t("تعذّر تشغيل الكاميرا. سأحاول مرة أخرى بعد قليل.", "Could not start the camera. Trying again shortly."), true)
        }
    }

    /** The picture size closest to the chosen quality (480 / 720 / 1080 lines), wide (16:9) when possible. */
    private fun chooseSize(all: Array<Size>?, quality: Int): Size {
        val list = all?.filter { it.width >= it.height && it.width <= 1920 && it.height <= 1080 }.orEmpty()
        if (list.isEmpty()) return Size(1280, 720)
        val caps = avcCaps()
        val ok = list.filter { caps == null || (try { caps.isSizeSupported(it.width, it.height) } catch (_: Exception) { true }) }.ifEmpty { list }
        return ok.minByOrNull { s ->
            val d = s.height - quality
            var score = if (d <= 0) -d else d * 3
            if (abs(s.width * 9 - s.height * 16) > s.height) score += 100 // not 16:9
            if (s.width % 16 != 0) score += 10
            score
        } ?: Size(1280, 720)
    }

    private fun avcCaps(): MediaCodecInfo.VideoCapabilities? = try {
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
        }
        info?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
    } catch (_: Exception) {
        null
    }

    /** Up to 30 pictures a second, allowed to go down to about 15 in the dark (brighter picture at night). */
    private fun chooseFps(ranges: Array<Range<Int>>?): Range<Int>? {
        if (ranges == null || ranges.isEmpty()) return null
        val fit = ranges.filter { it.upper in 15..30 }
        if (fit.isEmpty()) return null
        val top = fit.maxOf { it.upper }
        return fit.filter { it.upper == top }.minByOrNull { if (it.lower >= 10) it.lower else 100 + it.lower }
    }

    // ------------------------------------------------------------------ video encoder

    private fun startEncoder(w: Int, h: Int): Boolean {
        var codec: MediaCodec? = null
        return try {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            f.setInteger(MediaFormat.KEY_BIT_RATE, when {
                h >= 1000 -> 4_000_000
                h >= 700 -> 3_000_000
                else -> 2_000_000
            })
            f.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // a key picture every second: a new viewer sees the picture fast
            f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L) // a still scene keeps sending pictures
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = c
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = c.createInputSurface()
            c.start()
            videoCodec = c
            ptsOffset = Long.MIN_VALUE
            val th = Thread({ drainVideo(c) }, "ipcam-video")
            videoThread = th
            th.start()
            true
        } catch (_: Exception) {
            videoCodec = null
            try { codec?.release() } catch (_: Exception) {}
            try { encoderSurface?.release() } catch (_: Exception) {}
            encoderSurface = null
            false
        }
    }

    private fun stopEncoder() {
        val c = videoCodec
        videoCodec = null
        try { videoThread?.join(500) } catch (_: Exception) {}
        videoThread = null
        if (c != null) {
            try { c.stop() } catch (_: Exception) {}
            try { c.release() } catch (_: Exception) {}
        }
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null
    }

    private fun drainVideo(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (videoCodec === c) {
            val i = try { c.dequeueOutputBuffer(info, 50_000) } catch (_: Exception) { break }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try { videoConfig(c.outputFormat) } catch (_: Exception) {}
                continue
            }
            if (i < 0) continue
            try {
                val buf = c.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(data)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) configFrom(Nal.split(data))
                    else server?.sendVideo(data, videoPts(info.presentationTimeUs))
                }
                c.releaseOutputBuffer(i, false)
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun videoConfig(f: MediaFormat) {
        val all = ArrayList<ByteArray>()
        for (k in listOf("csd-0", "csd-1")) {
            val b = (if (f.containsKey(k)) f.getByteBuffer(k) else null) ?: continue
            val a = ByteArray(b.remaining())
            b.duplicate().get(a)
            all.addAll(Nal.split(a))
        }
        configFrom(all)
    }

    private fun configFrom(nals: List<ByteArray>) {
        val s = nals.firstOrNull { Nal.type(it) == 7 } ?: return
        val p = nals.firstOrNull { Nal.type(it) == 8 } ?: return
        server?.setVideoConfig(s, p)
    }

    /** Camera times → the clock the server uses (System.nanoTime), keeping the camera's even spacing. */
    private fun videoPts(p: Long): Long {
        val now = System.nanoTime() / 1000
        if (ptsOffset == Long.MIN_VALUE || abs(p + ptsOffset - now) > 2_000_000L) ptsOffset = now - p
        return p + ptsOffset
    }

    /** A new viewer: the next picture is a key picture, so he sees the picture at once. */
    private fun askKeyFrame() {
        val now = System.currentTimeMillis()
        if (now - lastKeyAsk < 300) return
        lastKeyAsk = now
        try {
            val b = Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            videoCodec?.setParameters(b)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ sound (microphone → AAC)

    @SuppressLint("MissingPermission")
    private fun startAudio(): Boolean {
        if (!granted(Manifest.permission.RECORD_AUDIO)) return false
        val rate = 44_100
        var rec: AudioRecord? = null
        var enc: MediaCodec? = null
        try {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return false
            for (src in intArrayOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)) {
                val r = try {
                    AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 8192))
                } catch (_: Exception) {
                    null
                }
                if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                    rec = r
                    break
                }
                try { r?.release() } catch (_: Exception) {}
            }
            val record = rec ?: return false
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc = e
            e.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            e.start()
            record.startRecording()
            val myGen = ++audioGen
            val th = Thread({ audioLoop(record, e, rate, myGen) }, "ipcam-audio")
            audioThread = th
            th.start()
            return true
        } catch (_: Exception) {
            try { rec?.release() } catch (_: Exception) {}
            try { enc?.release() } catch (_: Exception) {}
            return false
        }
    }

    private fun stopAudio() {
        audioGen++
        try { audioThread?.join(800) } catch (_: Exception) {}
        audioThread = null
    }

    private fun audioLoop(rec: AudioRecord, enc: MediaCodec, rate: Int, myGen: Int) {
        val buf = ByteArray(2048) // 1024 samples = one AAC frame
        val info = MediaCodec.BufferInfo()
        var samples = 0L
        var startUs = System.nanoTime() / 1000
        try {
            while (audioGen == myGen) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                // while someone talks through this phone its microphone sends silence (no echo back to him)
                if (talking.get()) java.util.Arrays.fill(buf, 0, n, 0.toByte())
                val got = n / 2
                val now = System.nanoTime() / 1000
                val endUs = startUs + (samples + got) * 1_000_000L / rate
                if (abs(endUs - now) > 300_000L) startUs = now - (samples + got) * 1_000_000L / rate
                val pts = startUs + samples * 1_000_000L / rate
                samples += got
                val i = enc.dequeueInputBuffer(20_000)
                if (i >= 0) {
                    val ib = enc.getInputBuffer(i)
                    if (ib != null) {
                        ib.clear()
                        val len = minOf(n, ib.remaining())
                        ib.put(buf, 0, len)
                        enc.queueInputBuffer(i, 0, len, pts, 0)
                    } else {
                        enc.queueInputBuffer(i, 0, 0, pts, 0)
                    }
                }
                while (true) {
                    val o = enc.dequeueOutputBuffer(info, 0)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = enc.outputFormat
                        val b = if (f.containsKey("csd-0")) f.getByteBuffer("csd-0") else null
                        if (b != null && b.remaining() > 0) {
                            val a = ByteArray(b.remaining())
                            b.duplicate().get(a)
                            server?.setAudioConfig(a, rate)
                        }
                        continue
                    }
                    if (o < 0) break
                    val ob = enc.getOutputBuffer(o)
                    if (ob != null && info.size > 0) {
                        val a = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.get(a)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) server?.setAudioConfig(a, rate)
                        else server?.sendAudio(a, info.presentationTimeUs)
                    }
                    enc.releaseOutputBuffer(o, false)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
            try { enc.stop() } catch (_: Exception) {}
            try { enc.release() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ talk: a viewer speaks through this phone

    private fun startTalk() {
        val ss = try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(TALK_PORT))
            s
        } catch (_: Exception) {
            null
        } ?: return
        talkServer = ss
        val th = Thread({
            while (!ss.isClosed) {
                val s = try {
                    ss.accept()
                } catch (_: Throwable) {
                    if (ss.isClosed) break
                    // a passing problem (too many open files…): keep listening
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                    continue
                }
                if (!talkSlots.tryAcquire()) {
                    try { s.close() } catch (_: Exception) {}
                    continue
                }
                try {
                    val h = Thread({
                        try { talk(s) } finally { talkSlots.release() }
                    }, "ipcam-talk")
                    h.isDaemon = true
                    h.start()
                } catch (_: Throwable) {
                    // no memory or no thread: this one is turned away, the server keeps going
                    talkSlots.release()
                    try { s.close() } catch (_: Exception) {}
                }
            }
        }, "ipcam-talk-accept")
        th.isDaemon = true
        th.start()
    }

    /** The challenge sign-in ([TalkAuth]), then 16 kHz mono 16-bit sound until the viewer lets go of the button. */
    private fun talk(s: Socket) {
        try {
            s.soTimeout = 5000
            s.tcpNoDelay = true
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            if (!TalkAuth.cameraSide(input, out, Prefs.str(this, K_PASS))) return
            if (!running || !talking.compareAndSet(false, true)) {
                out.write("BUSY\n".toByteArray(Charsets.UTF_8))
                out.flush()
                return
            }
            try {
                out.write("OK\n".toByteArray(Charsets.UTF_8))
                out.flush()
                talker = s.inetAddress?.hostAddress ?: ""
                changed()
                s.soTimeout = 10_000
                play(DataInputStream(input))
            } finally {
                talking.set(false)
                talker = ""
                changed()
            }
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun play(input: DataInputStream) {
        val rate = 16_000
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // voice call on the loud speaker (needs MODIFY_AUDIO_SETTINGS); without it, plain media sound (also loud)
        val voice = granted(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        val attrs = AudioAttributes.Builder()
            .setUsage(if (voice) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack(attrs, fmt, maxOf(min * 2, 9600), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        val saved = if (voice) speakerOn(am) else null
        try {
            track.play()
            val buf = ByteArray(640) // 20 ms
            while (running) {
                input.readFully(buf)
                track.write(buf, 0, buf.size)
            }
        } finally {
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
            speakerOff(am, saved)
        }
    }

    private class AudioState(val mode: Int, val speaker: Boolean, val volume: Int)

    @Suppress("DEPRECATION")
    private fun speakerOn(am: AudioManager): AudioState? = try {
        val st = AudioState(am.mode, am.isSpeakerphoneOn, am.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
        } else {
            am.isSpeakerphoneOn = true
        }
        try {
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
        } catch (_: Exception) {
        }
        st
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun speakerOff(am: AudioManager, st: AudioState?) {
        if (st == null) return
        try {
            try { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, st.volume, 0) } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else am.isSpeakerphoneOn = st.speaker
            am.mode = st.mode
        } catch (_: Exception) {
        }
    }
}
