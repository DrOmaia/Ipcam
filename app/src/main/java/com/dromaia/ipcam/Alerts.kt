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
 * Motion alerts. A service keeps listening to the cameras that have «تنبيه الحركة» switched on: Hikvision through its
 * own alert stream (ISAPI, one connection per recorder for all its channels: person / vehicle / motion / line
 * crossing), the other makes through ONVIF events. Only the kinds the camera's choice asks for (Camera.alertWhat);
 * inside the alert hours, at most once a minute per camera:
 *  - phone: a notification («شخص عند «الحوش»») with a picture from the camera; tapping it opens that camera;
 *  - TV: the camera appears in a corner over any app (YouTube…) for 30 seconds, without taking the remote.
 * At home the cameras' home address is used, away their outside address (Route).
 */
object Alerts {
    private val main = Handler(Looper.getMainLooper())
    private const val TARGETS_KEY = "alertTargets"
    private var targets: MutableSet<String>? = null

    /** The last TV pop-up: camera id and when it appeared (System.currentTimeMillis()); the home screen may open that camera. */
    @Volatile var lastPop: Pair<String, Long>? = null

    /** Removes the TV motion pop-up if it is shown (the app itself came to the screen). Safe on any thread, never throws. */
    fun hidePop() {
        try {
            val s = AlertService.instance ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) s.removePop() else main.post { s.removePop() }
        } catch (_: Throwable) {
        }
    }

    /** A clock time in the app's language, 12 or 24 hours like the device, Latin digits: "10:32 م" / "10:32 PM". */
    fun time(c: Context, at: Long): String {
        val pattern = if (DateFormat.is24HourFormat(c)) "HH:mm" else "h:mm a"
        val locale = if (L.en(c)) Locale.US else Locale.forLanguageTag("ar-u-nu-latn")
        return SimpleDateFormat(pattern, locale).format(Date(at))
    }

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

    /**
     * Does an event of this kind pass the camera's choice? "any" = every detection; "human" / "vehicle" /
     * "humanVehicle" = only those. A plain motion (or a line crossing / intrusion that does not say what crossed)
     * passes them too while that device has never told a person or a vehicle apart: an older recorder still alerts.
     */
    internal fun wanted(c: Context, cam: Camera, kind: String): Boolean {
        val what = cam.alertWhat
        return when (kind) {
            "human" -> what != "vehicle"
            "vehicle" -> what != "human"
            "motion", "line", "intrusion" ->
                (what != "human" && what != "vehicle" && what != "humanVehicle") || !toldApart(c, cam)
            else -> false
        }
    }

    /** This device reported a person or a vehicle: remembered (also after a restart) for [wanted]. */
    @Synchronized
    internal fun sawTarget(c: Context, cam: Camera) {
        val set = targetSet(c)
        if (set.add(deviceId(cam))) {
            try {
                Prefs.put(c, TARGETS_KEY, set.joinToString("\n"))
            } catch (_: Throwable) {
            }
        }
    }

    @Synchronized
    private fun toldApart(c: Context, cam: Camera): Boolean = deviceId(cam) in targetSet(c)

    private fun targetSet(c: Context): MutableSet<String> =
        targets ?: HashSet(Prefs.str(c, TARGETS_KEY).split('\n').filter { it.isNotBlank() }).also { targets = it }

    /** A recorder / camera by make, home address and web port (no password). */
    private fun deviceId(cam: Camera): String = cam.brand.key + "|" + cam.address().lowercase(Locale.US) + ":" + cam.httpPort()

    /** Inside the alert hours [from, to) (minutes after midnight; from == to = all day; 22:00 → 06:00 crosses midnight). */
    internal fun inHours(c: Context, now: Calendar = Calendar.getInstance()): Boolean {
        val from = Prefs.alertFrom(c)
        val to = Prefs.alertTo(c)
        if (from == to) return true
        val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (from < to) m in from until to else m >= from || m < to
    }
}

/** Keeps listening to the cameras for motion (one thread per recorder / camera) while alerts are on. */
class AlertService : Service() {
    companion object {
        @Volatile var instance: AlertService? = null

        private const val FG_ID = 4101
        private const val PAUSED_ID = 4102
        private const val CH_WATCH = "watch"
        private const val CH_MOTION = "motion"
        private const val COOLDOWN_MS = 60_000L
        private const val SHOW_MS = 30_000L
        private const val LONGEST_MS = 2 * 60_000L // a camera stuck on "motion" does not stay on the TV for ever
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
    private var popRemote = false // the pop-up plays the camera's outside address (Route)
    private val hideTick = Runnable { tick() }
    private val retryPlay = Runnable { play() }
    private val popListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                popStatus?.visibility = View.GONE
                popCam?.let { Route.worked(it, popRemote) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            popStatus?.text = L.t(this@AlertService, "تعذّر عرض الكاميرا، إعادة المحاولة…", "Could not show the camera, retrying…")
            popStatus?.visibility = View.VISIBLE
            // the other address (home / outside) next time
            if (popCam?.hasRemote() == true) popRemote = !popRemote
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
        val n = cameras().size
        if (!foreground(n)) {
            // not allowed now: Android restarted the service in the background after it closed the app
            if (n > 0) paused()
            stopNow()
            return START_NOT_STICKY
        }
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(PAUSED_ID)
        } catch (_: Throwable) {
        }
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
        val stop = { destroyed || generation.get() != g }
        val ids = cams.map { it.id }.toSet()
        moving.keys.retainAll(ids)
        lastAlert.keys.retainAll(ids)
        // Hikvision: one alert stream per recorder serves all its channels (ISAPI)
        val recorders = ArrayList<MutableList<Camera>>()
        for (cam in cams) {
            if (cam.brand != Brand.HIKVISION) continue
            val same = recorders.firstOrNull { it[0].sameDevice(cam) }
            if (same != null) same.add(cam) else recorders.add(mutableListOf(cam))
        }
        for (group in recorders) {
            thread(isDaemon = true, name = "ipcam-hik-alerts") {
                Hik.watch(group, stop) { ch, kind ->
                    if (!stop()) {
                        for (cam in group) {
                            if (cam.channel != ch) continue
                            try {
                                event(cam, true, kind, true)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }
        }
        // the other makes: ONVIF events, one listener per camera
        for (cam in cams) {
            if (cam.brand == Brand.HIKVISION) continue
            thread(isDaemon = true, name = "ipcam-motion") {
                Onvif.watchEvents(cam, stop) { on, kind ->
                    if (!stop()) {
                        try {
                            event(cam, on, kind, false)
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

    /** The quiet notification that keeps the service alive: «مراقبة الحركة — كاميرتان». false = Android did not allow it. */
    private fun foreground(n: Int): Boolean = try {
        val note = watchBuilder()
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(L.t(this, "مراقبة الحركة", "Watching for motion"))
            .setContentText(Ui.cameras(this, n))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(FG_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(FG_ID, note)
        }
        true
    } catch (_: Throwable) {
        false
    }

    /** «توقّفت تنبيهات الحركة — افتح التطبيق لتشغيلها»: a plain notification; tapping it opens the app, which starts them again. */
    private fun paused() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val note = watchBuilder()
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(L.t(this, "توقّفت تنبيهات الحركة", "Motion alerts paused"))
                .setContentText(L.t(this, "افتح التطبيق لتشغيلها", "Open the app to turn them back on"))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
            nm.notify(PAUSED_ID, note)
        } catch (_: Throwable) {
            // notifications not allowed (Android 13+: the permission is asked in Settings)
        }
    }

    /** A notification on the quiet "watching" channel. */
    private fun watchBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_WATCH, L.t(this, "مراقبة الحركة", "Watching for motion"), NotificationManager.IMPORTANCE_LOW),
            )
            Notification.Builder(this, CH_WATCH)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }

    /** Opens the app as it was (like its icon), not a second home screen. */
    private fun openApp(): PendingIntent {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // ---------------------------------------------------------------- motion (called on the camera's thread)

    /**
     * One event of a camera: [on] = it starts (false = it ended, ONVIF only), [kind] = human, vehicle, motion, line,
     * intrusion, other. [pulse] = Hikvision repeats "active" every second while it lasts and never says "ended".
     */
    private fun event(cam: Camera, on: Boolean, kind: String, pulse: Boolean) {
        if (on && (kind == "human" || kind == "vehicle")) Alerts.sawTarget(this, cam)
        if (!Alerts.wanted(this, cam, kind)) return
        if (!on) {
            moving[cam.id] = false
            return
        }
        if (!pulse) moving[cam.id] = true
        motion(cam, kind)
    }

    private fun motion(cam: Camera, kind: String) {
        // the same camera already on the TV: it stays while the motion goes on (at most 2 minutes)
        if (tv) {
            main.post {
                if (pop != null && popCam?.id == cam.id && SystemClock.elapsedRealtime() - popSince < LONGEST_MS) keepPop()
            }
        }
        if (!Alerts.inHours(this)) return
        val now = SystemClock.elapsedRealtime()
        val last = lastAlert[cam.id]
        if (last != null && now - last < COOLDOWN_MS) return
        lastAlert[cam.id] = now
        if (tv) {
            if (cam.popTv && Alerts.canOverlay(this)) main.post { showPop(cam, kind) }
        } else {
            try {
                wake?.acquire(30_000L)
            } catch (_: Throwable) {
            }
            notifyPhone(cam, kind)
        }
    }

    private fun name(cam: Camera): String = cam.name.ifBlank { cam.where() }

    /** «شخص عند «الحوش»» / "Person at “Yard”", by kind. */
    private fun title(kind: String, n: String): String = when (kind) {
        "human" -> L.t(this, "شخص عند «$n»", "Person at “$n”")
        "vehicle" -> L.t(this, "سيارة عند «$n»", "Vehicle at “$n”")
        "line" -> L.t(this, "عبور خط عند «$n»", "Line crossed at “$n”")
        "intrusion" -> L.t(this, "تسلّل عند «$n»", "Intrusion at “$n”")
        else -> L.t(this, "حركة عند «$n»", "Motion at “$n”")
    }

    /** The word on the TV pop-up's red badge. */
    private fun badgeText(kind: String): String = when (kind) {
        "human" -> L.t(this, "شخص", "Person")
        "vehicle" -> L.t(this, "سيارة", "Vehicle")
        "line" -> L.t(this, "عبور خط", "Line crossed")
        "intrusion" -> L.t(this, "تسلّل", "Intrusion")
        else -> L.t(this, "حركة", "Motion")
    }

    // ---------------------------------------------------------------- phone: notification with a picture

    private fun notifyPhone(cam: Camera, kind: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val id = 5000 + (cam.id.hashCode() and 0xFFF)
        val at = System.currentTimeMillis()
        // a fresh alert (with sound) even if the last one of this camera is still in the list
        try {
            nm.cancel(id)
        } catch (_: Throwable) {
        }
        postAlert(nm, id, cam, at, kind, null)
        // the picture follows a moment later: the alert itself does not wait for the camera
        thread(isDaemon = true, name = "ipcam-snapshot") {
            val pic = try {
                picture(cam)
            } catch (_: Throwable) {
                null
            }
            if (pic != null && !destroyed && stillShown(nm, id)) postAlert(nm, id, cam, at, kind, pic)
        }
    }

    /** Hikvision: the channel's own picture (ISAPI) first, then ONVIF (unless that would be the same refused password). */
    private fun picture(cam: Camera): Bitmap? {
        if (cam.brand != Brand.HIKVISION) return Onvif.snapshot(cam)
        Hik.snapshot(cam)?.let { return it }
        if (Hik.refused(cam) && cam.onvifUser.isEmpty()) return null
        return Onvif.snapshot(cam)
    }

    private fun stillShown(nm: NotificationManager, id: Int): Boolean = try {
        nm.activeNotifications.any { it.id == id }
    } catch (_: Throwable) {
        true
    }

    private fun postAlert(nm: NotificationManager, id: Int, cam: Camera, at: Long, kind: String, pic: Bitmap?) {
        try {
            val open = PendingIntent.getActivity(
                this, id,
                Watch.one(this, cam.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = motionBuilder(nm, cam, at, kind)
                .setAutoCancel(true)
                .setOnlyAlertOnce(pic != null) // adding the picture does not ring a second time
                .setContentIntent(open)
            if (pic != null) {
                b.setLargeIcon(pic)
                b.setStyle(Notification.BigPictureStyle().bigPicture(pic).bigLargeIcon(null as Bitmap?))
            }
            if (Lock.enabled(this)) {
                // the app is locked with a PIN: a locked phone shows only the words, never the picture
                b.setVisibility(Notification.VISIBILITY_PRIVATE)
                b.setPublicVersion(motionBuilder(nm, cam, at, kind).build())
            }
            nm.notify(id, b.build())
        } catch (_: SecurityException) {
            // notifications not allowed (Android 13+: the permission is asked in Settings)
        } catch (_: Throwable) {
        }
    }

    /** «حركة عند «الحوش»» (or شخص / سيارة / عبور خط …) and the time, without a picture. */
    private fun motionBuilder(nm: NotificationManager, cam: Camera, at: Long, kind: String): Notification.Builder {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH_MOTION, L.t(this, "تنبيه الحركة", "Motion alert"), NotificationManager.IMPORTANCE_HIGH),
            )
            Notification.Builder(this, CH_MOTION)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
        }
        return b.setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title(kind, name(cam)))
            .setContentText(clock(at))
            .setWhen(at)
            .setShowWhen(true)
            .setColor(Ui.GOLD)
            .setCategory(Notification.CATEGORY_EVENT)
    }

    /** «الساعة 10:32 م» / "at 10:32 PM" (12 or 24 hours like the device). */
    private fun clock(at: Long): String {
        val t = Alerts.time(this, at)
        return L.t(this, "الساعة $t", "at $t")
    }

    // ---------------------------------------------------------------- TV: the camera over other apps

    private fun showPop(cam: Camera, kind: String) {
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

        val status = Ui.text(ctx, L.t(this, "جارٍ الاتصال…", "Connecting…"), 13f, Ui.TEXT)
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

        val badge = Ui.text(ctx, badgeText(kind), 13f, Ui.BG, true)
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
        lp.x = (dm.widthPixels * 0.05f).toInt()
        lp.y = (dm.heightPixels * 0.05f).toInt()
        try {
            wm.addView(root, lp)
        } catch (_: Throwable) {
            return
        }
        Alerts.lastPop = Pair(cam.id, System.currentTimeMillis())
        try {
            // TalkBack says it too: the pop-up never takes the focus
            root.announceForAccessibility(title(kind, name(cam)))
        } catch (_: Throwable) {
        }
        pop = root
        popCam = cam
        popVideo = video
        popStatus = status
        popSince = SystemClock.elapsedRealtime()
        popRemote = Route.preferRemote(cam)
        play()
        keepPop()
    }

    /** (Re)start the pop-up's video: light stream, no sound, small buffer (like Tile). */
    private fun play() {
        main.removeCallbacks(retryPlay)
        val cam = popCam ?: return
        val video = popVideo ?: return
        releasePlayer()
        val url = cam.rtsp(false, popRemote)
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

    /** 30 s after the last motion: gone, unless the camera still reports motion (then 30 s more, up to 2 minutes). */
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

    /** Removes the pop-up (also [Alerts.hidePop] when the app comes to the screen). Main thread. */
    internal fun removePop() {
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
