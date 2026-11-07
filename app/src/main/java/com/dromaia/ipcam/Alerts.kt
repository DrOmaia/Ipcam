package com.dromaia.ipcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Motion alerts. A service keeps listening to the cameras that have «تنبيه الحركة» switched on (ONVIF events);
 * on motion, inside the alert hours, at most once a minute per camera:
 *  - phone: a notification with a picture from the camera; tapping it opens that camera;
 *  - TV: the camera appears in a corner over any app (YouTube…) for 30 seconds, without taking the remote.
 */
object Alerts {
    private val main = Handler(Looper.getMainLooper())

    /** Start or stop the alert service to match the settings and the cameras. Cheap, safe on the main thread, never throws. */
    fun refresh(c: Context) {
        try {
            val app = c.applicationContext ?: c
            val running = AlertService.instance
            if (running != null && !running.stopping) {
                // already running: it re-reads the cameras (and stops itself when nothing is left to watch)
                main.post { running.reload() }
                return
            }
            if (!Prefs.alertsOn(app) || watched(app).isEmpty()) return
            val i = Intent(app, AlertService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        } catch (_: Throwable) {
            // Android 12+ does not allow starting it from the background: the next app start tries again
        }
    }

    /** The TV may show the camera over other apps (SYSTEM_ALERT_WINDOW). */
    fun canOverlay(c: Context): Boolean = try {
        Settings.canDrawOverlays(c)
    } catch (_: Throwable) {
        false
    }

    /** The cameras this device listens to: alert on, and a make that speaks ONVIF (an RTSP link only with an ONVIF port). */
    internal fun watched(c: Context): List<Camera> = Store.all(c).filter { cam ->
        cam.alert && cam.address().isNotEmpty() &&
            (if (cam.brand == Brand.CUSTOM) cam.onvifPort > 0 else cam.brand.onvif)
    }

    /** Inside the alert hours [from, to) (minutes after midnight; from == to = all day; 22:00 → 06:00 crosses midnight). */
    internal fun inHours(c: Context, now: Calendar = Calendar.getInstance()): Boolean {
        val from = Prefs.alertFrom(c)
        val to = Prefs.alertTo(c)
        if (from == to) return true
        val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (from < to) m in from until to else m >= from || m < to
    }
}

/** Keeps listening to the cameras for motion (one thread per camera) while alerts are on. */
class AlertService : Service() {
    companion object {
        @Volatile var instance: AlertService? = null

        private const val FG_ID = 4101
        private const val CH_WATCH = "watch"
        private const val CH_MOTION = "motion"
        private const val COOLDOWN_MS = 60_000L
        private const val SHOW_MS = 30_000L
        private const val LONGEST_MS = 5 * 60_000L // a camera stuck on "motion" does not stay on the TV for ever
    }

    /** stopSelf() was called: a new start must create a fresh service. */
    @Volatile var stopping = false
        private set

    @Volatile private var destroyed = false
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private var watching = ""
    private val lastAlert: MutableMap<String, Long> = ConcurrentHashMap()
    private val moving: MutableMap<String, Boolean> = ConcurrentHashMap()
    private var tv = false
    private var wake: PowerManager.WakeLock? = null

    // the TV pop-up (main thread only)
    private var pop: View? = null
    private var popCam: Camera? = null
    private var popVideo: PlayerView? = null
    private var popStatus: TextView? = null
    private var popPlayer: ExoPlayer? = null
    private var popSince = 0L
    private val hideTick = Runnable { tick() }
    private val retryPlay = Runnable { play() }
    private val popListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) popStatus?.visibility = View.GONE
        }

        override fun onPlayerError(error: PlaybackException) {
            popStatus?.text = L.t(this@AlertService, "تعذّر عرض الكاميرا، إعادة المحاولة…", "Cannot show the camera, retrying…")
            popStatus?.visibility = View.VISIBLE
            main.removeCallbacks(retryPlay)
            main.postDelayed(retryPlay, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        tv = Device.isTv(this)
        if (!tv) {
            // a phone with its screen off: stay awake for a few seconds after a motion to send the notification and its picture
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ipcam:motion").also { it.setReferenceCounted(false) }
            } catch (_: Throwable) {
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // first become a foreground service (Android requires it within seconds of the start), then listen
        foreground(cameras().size)
        reload()
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        generation.incrementAndGet()
        main.removeCallbacksAndMessages(null)
        removePop()
        try {
            wake?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun cameras(): List<Camera> = if (Prefs.alertsOn(this)) Alerts.watched(this) else emptyList()

    /** Re-read the cameras: restart the listeners when the list changed; stop when nothing is left to watch. Main thread. */
    fun reload() {
        if (destroyed || stopping) return
        val cams = cameras()
        if (cams.isEmpty()) {
            stopNow()
            return
        }
        val sig = cams.joinToString("\n") { it.toJson().toString() }
        if (sig == watching) return
        watching = sig
        foreground(cams.size)
        val g = generation.incrementAndGet() // the old listeners see the change and end by themselves
        val ids = cams.map { it.id }.toSet()
        moving.keys.retainAll(ids)
        lastAlert.keys.retainAll(ids)
        for (cam in cams) {
            thread(isDaemon = true, name = "ipcam-motion") {
                Onvif.watchMotion(cam, { destroyed || generation.get() != g }) { on ->
                    if (!destroyed && generation.get() == g) {
                        try {
                            motion(cam, on)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
    }

    private fun stopNow() {
        stopping = true
        generation.incrementAndGet()
        watching = ""
        removePop()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    /** The quiet notification that keeps the service alive: «مراقبة الحركة — كاميرتان». */
    private fun foreground(n: Int) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val title = L.t(this, "مراقبة الحركة", "Watching for motion")
            val b = if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CH_WATCH, title, NotificationManager.IMPORTANCE_LOW))
                Notification.Builder(this, CH_WATCH)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(this)
            }
            val open = PendingIntent.getActivity(
                this, 1,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val note = b.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(title)
                .setContentText(Ui.cameras(this, n))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(FG_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(FG_ID, note)
            }
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- motion (called on the camera's thread)

    private fun motion(cam: Camera, on: Boolean) {
        moving[cam.id] = on
        if (!on) return
        // the same camera already on the TV: it stays while the motion goes on
        if (tv) main.post { if (pop != null && popCam?.id == cam.id) keepPop() }
        if (!Alerts.inHours(this)) return
        val now = SystemClock.elapsedRealtime()
        val last = lastAlert[cam.id]
        if (last != null && now - last < COOLDOWN_MS) return
        lastAlert[cam.id] = now
        if (tv) {
            if (cam.popTv && Alerts.canOverlay(this)) main.post { showPop(cam) }
        } else {
            try {
                wake?.acquire(30_000L)
            } catch (_: Throwable) {
            }
            notifyPhone(cam)
        }
    }

    private fun name(cam: Camera): String = cam.name.ifBlank { cam.where() }

    // ---------------------------------------------------------------- phone: notification with a picture

    private fun notifyPhone(cam: Camera) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val id = 5000 + (cam.id.hashCode() and 0xFFF)
        val at = System.currentTimeMillis()
        // a fresh alert (with sound) even if the last one of this camera is still in the list
        try {
            nm.cancel(id)
        } catch (_: Throwable) {
        }
        postAlert(nm, id, cam, at, null)
        // the picture follows a moment later: the alert itself does not wait for the camera
        thread(isDaemon = true, name = "ipcam-snapshot") {
            val pic = try {
                Onvif.snapshot(cam)
            } catch (_: Throwable) {
                null
            }
            if (pic != null && !destroyed && stillShown(nm, id)) postAlert(nm, id, cam, at, pic)
        }
    }

    private fun stillShown(nm: NotificationManager, id: Int): Boolean = try {
        nm.activeNotifications.any { it.id == id }
    } catch (_: Throwable) {
        true
    }

    private fun postAlert(nm: NotificationManager, id: Int, cam: Camera, at: Long, pic: Bitmap?) {
        try {
            val b = if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_MOTION, L.t(this, "تنبيه الحركة", "Motion alert"), NotificationManager.IMPORTANCE_HIGH),
                )
                Notification.Builder(this, CH_MOTION)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
            }
            val open = PendingIntent.getActivity(
                this, id,
                Watch.one(this, cam.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = name(cam)
            b.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(L.t(this, "حركة عند «$n»", "Motion at “$n”"))
                .setContentText(clock(at))
                .setWhen(at)
                .setShowWhen(true)
                .setColor(Ui.GOLD)
                .setCategory(Notification.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setOnlyAlertOnce(pic != null) // adding the picture does not ring a second time
                .setContentIntent(open)
            if (pic != null) {
                b.setLargeIcon(pic)
                b.setStyle(Notification.BigPictureStyle().bigPicture(pic).bigLargeIcon(null as Bitmap?))
            }
            nm.notify(id, b.build())
        } catch (_: SecurityException) {
            // notifications not allowed (Android 13+: the permission is asked in Settings)
        } catch (_: Throwable) {
        }
    }

    /** «الساعة ١٠:٣٢ م» / "at 10:32 PM" (12 or 24 hours like the device). */
    private fun clock(at: Long): String {
        val en = L.en(this)
        val pattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
        val t = SimpleDateFormat(pattern, if (en) Locale.US else Locale("ar")).format(Date(at))
        return L.t(this, "الساعة $t", "at $t")
    }

    // ---------------------------------------------------------------- TV: the camera over other apps

    private fun showPop(cam: Camera) {
        if (destroyed || stopping) return
        if (Lock.started > 0) return // the app itself is on the screen: no pop-up over it
        if (pop != null && popCam?.id == cam.id) {
            keepPop()
            return
        }
        removePop() // one at a time: a new camera replaces the old one
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_Material_NoActionBar)
        val en = L.en(this)
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.4f).toInt()
        val h = w * 9 / 16
        val edge = Ui.dp(ctx, 3)
        val radius = Ui.dp(ctx, 16).toFloat()
        val gap = Ui.dp(ctx, 8)

        val root = FrameLayout(ctx)
        root.layoutDirection = if (en) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
        root.background = Ui.round(Color.BLACK, radius, edge, Ui.GOLD)
        root.setPadding(edge, edge, edge, edge)

        // the light stream on a TextureView, with rounded corners inside the gold frame
        val video = LayoutInflater.from(ctx).inflate(R.layout.player_texture, root, false) as PlayerView
        video.useController = false
        val innerRadius = radius - edge
        video.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, innerRadius)
            }
        }
        video.clipToOutline = true
        root.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val status = Ui.text(ctx, L.t(this, "جاري الاتصال…", "Connecting…"), 13f, Ui.TEXT)
        status.gravity = Gravity.CENTER
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val label = Ui.text(ctx, name(cam), 13f, Ui.TEXT, true)
        label.setPadding(gap, gap / 2, gap, gap / 2)
        label.background = Ui.round(0x99000000.toInt(), gap.toFloat())
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.maxWidth = (w * 0.65f).toInt()
        root.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ).also { it.setMargins(gap, gap, gap, gap) })

        val badge = Ui.text(ctx, L.t(this, "حركة", "Motion"), 13f, Ui.BG, true)
        badge.setPadding(gap, gap / 2, gap, gap / 2)
        badge.background = Ui.round(Ui.RED, gap.toFloat())
        root.addView(badge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
        ).also { it.setMargins(gap, gap, gap, gap) })

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        // not focusable, not touchable: the remote keeps controlling YouTube (or whatever is on the TV)
        val lp = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        // bottom corner at the end of the reading direction (left in Arabic, right in English), inside the TV's safe area
        lp.gravity = Gravity.BOTTOM or (if (en) Gravity.RIGHT else Gravity.LEFT)
        lp.x = (dm.widthPixels * 0.03f).toInt()
        lp.y = (dm.heightPixels * 0.05f).toInt()
        try {
            wm.addView(root, lp)
        } catch (_: Throwable) {
            return
        }
        pop = root
        popCam = cam
        popVideo = video
        popStatus = status
        popSince = SystemClock.elapsedRealtime()
        play()
        keepPop()
    }

    /** (Re)start the pop-up's video: light stream, no sound, small buffer (like Tile). */
    private fun play() {
        main.removeCallbacks(retryPlay)
        val cam = popCam ?: return
        val video = popVideo ?: return
        releasePlayer()
        val url = cam.rtsp(false)
        if (!url.startsWith("rtsp://", ignoreCase = true)) {
            popStatus?.text = L.t(this, "رابط البث غير صحيح", "The stream link is not valid")
            popStatus?.visibility = View.VISIBLE
            return
        }
        try {
            val load = DefaultLoadControl.Builder().setBufferDurationsMs(1000, 3000, 250, 500).build()
            val p = ExoPlayer.Builder(this).setLoadControl(load).build()
            popPlayer = p
            p.volume = 0f
            // no sound track at all: the TV keeps the sound of what is playing
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            p.addListener(popListener)
            val source = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(10_000)
                .createMediaSource(MediaItem.fromUri(url))
            p.setMediaSource(source)
            p.playWhenReady = true
            p.prepare()
            video.player = p
        } catch (_: Throwable) {
            releasePlayer()
            main.postDelayed(retryPlay, 5000)
        }
    }

    private fun keepPop() {
        main.removeCallbacks(hideTick)
        main.postDelayed(hideTick, SHOW_MS)
    }

    /** 30 s after the last motion: gone, unless the camera still reports motion (then 30 s more, up to 5 minutes). */
    private fun tick() {
        val cam = popCam ?: return
        val shown = SystemClock.elapsedRealtime() - popSince
        if (moving[cam.id] == true && shown < LONGEST_MS) main.postDelayed(hideTick, SHOW_MS) else removePop()
    }

    private fun releasePlayer() {
        val p = popPlayer ?: return
        popPlayer = null
        popVideo?.player = null
        try {
            p.removeListener(popListener)
            p.release()
        } catch (_: Throwable) {
        }
    }

    private fun removePop() {
        main.removeCallbacks(hideTick)
        main.removeCallbacks(retryPlay)
        releasePlayer()
        val v = pop
        pop = null
        popCam = null
        popVideo = null
        popStatus = null
        if (v != null) {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            } catch (_: Throwable) {
            }
        }
    }
}

/** After the device starts or the app is updated: motion alerts start again by themselves. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            Alerts.refresh(ctx)
        } catch (_: Throwable) {
        }
    }
}
