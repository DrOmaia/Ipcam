package com.dromaia.ipcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Settings of one live camera widget, kept in Prefs as "lw_<widget id>" (deleted with the widget). */
class LiveConf(
    val cams: List<String>, // the chosen cameras (ids), in order
    val snapSec: Int,       // a new picture every 1 / 3 / 5 / 10 seconds
    val switchSec: Int,     // with several cameras: the next one every 10 / 15 / 30 / 60 seconds
    var index: Int = 0,     // the camera shown now
    var at: Long = 0L,      // when the kept picture was taken (clock time, ms; 0 = none)
    var picCam: String = "", // the camera of the kept picture
) {
    fun json(): String = JSONObject()
        .put("cams", JSONArray(cams))
        .put("snap", snapSec)
        .put("switch", switchSec)
        .put("index", index)
        .put("at", at)
        .put("picCam", picCam)
        .toString()

    companion object {
        val SNAPS = intArrayOf(1, 3, 5, 10)
        val SWITCHES = intArrayOf(10, 15, 30, 60)

        fun parse(s: String): LiveConf? {
            if (s.isEmpty()) return null
            return try {
                val o = JSONObject(s)
                val a = o.optJSONArray("cams")
                val ids = ArrayList<String>()
                if (a != null) for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let { ids.add(it) }
                LiveConf(
                    ids,
                    o.optInt("snap", 3).let { if (it in SNAPS) it else 3 },
                    o.optInt("switch", 15).let { if (it in SWITCHES) it else 15 },
                    maxOf(0, o.optInt("index", 0)),
                    o.optLong("at", 0L),
                    o.optString("picCam"),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** What one widget shows now. */
internal class Look(
    val playing: Boolean,
    val index: Int,      // the camera shown (in the widget's cameras still in the app)
    val pic: Bitmap?,
    val picCam: String,  // the camera of [pic]
    val at: Long,        // when [pic] was taken (clock time, ms)
    val failed: Boolean, // the last picture could not be had
)

/**
 * Home-screen widget «كاميرا حيّة» / "Live camera" (separate from the buttons widget, CamWidget).
 * Android widgets cannot play video, so it shows a still picture of the camera, refreshed every few seconds while
 * it plays ([LiveService]), with the camera's name and the time of the picture. Buttons: ▶ / ⏸ start / stop,
 * ⏭ next camera (several cameras), ⧉ floating live video (WatchActivity in picture-in-picture), ⚙ settings
 * (WidgetSetupActivity); a tap on the picture opens the live view of that camera. Each widget has its own settings.
 * Playing stops by itself after 5 minutes; the last picture stays (also after a restart: kept in the cache folder).
 */
class LiveWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, am: AppWidgetManager, ids: IntArray) {
        refresh(ctx, ids, goAsync())
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val app = ctx.applicationContext ?: ctx
        for (id in ids) {
            try {
                LiveService.instance?.forget(id)
            } catch (_: Exception) {
            }
            try {
                Prefs.sp(app).edit().remove(key(id)).apply()
            } catch (_: Exception) {
            }
        }
        work {
            for (id in ids) {
                try {
                    cacheFile(app, id).delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            // ⏸ on a widget
            ACTION_STOP -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                val s = LiveService.instance
                if (s != null && s.isPlaying(id)) s.stopWidget(id) else refresh(ctx, intArrayOf(id), goAsync())
            }
            // «إيقاف» in the notification: every widget stops
            ACTION_STOP_ALL -> LiveService.instance?.stopAll()
        }
    }

    companion object {
        internal const val ACTION_STOP = "com.dromaia.ipcam.LIVE_STOP"
        internal const val ACTION_STOP_ALL = "com.dromaia.ipcam.LIVE_STOP_ALL"

        // one thread for drawing the widgets and for the kept pictures: drawings arrive in the order they were made
        private val io: ExecutorService = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())

        private fun key(id: Int) = "lw_$id"

        internal fun work(task: () -> Unit) {
            try {
                io.execute {
                    try {
                        task()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }

        /** The ids of the live camera widgets on the home screen. */
        fun ids(c: Context): IntArray = try {
            AppWidgetManager.getInstance(c)?.getAppWidgetIds(ComponentName(c, LiveWidget::class.java)) ?: IntArray(0)
        } catch (_: Exception) {
            IntArray(0)
        }

        /** The settings of widget [id], or null before it was set up. */
        fun conf(c: Context, id: Int): LiveConf? = LiveConf.parse(Prefs.str(c, key(id)))

        fun saveConf(c: Context, id: Int, conf: LiveConf) = Prefs.put(c, key(id), conf.json())

        /** The widget's cameras that are still in the app, in its order. */
        internal fun cams(c: Context, conf: LiveConf): List<Camera> {
            val byId = Store.all(c).associateBy { it.id }
            return conf.cams.distinct().mapNotNull { byId[it] }
        }

        internal fun cacheFile(c: Context, id: Int) = File(c.cacheDir, "lw_$id.jpg")

        internal fun readPicture(f: File): Bitmap? = try {
            if (f.isFile) {
                val o = BitmapFactory.Options()
                o.inPreferredConfig = Bitmap.Config.RGB_565
                BitmapFactory.decodeFile(f.path, o)?.let { Snap.small(it) }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }

        internal fun writePicture(f: File, b: Bitmap) {
            try {
                val tmp = File(f.path + ".tmp")
                FileOutputStream(tmp).use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                if (!tmp.renameTo(f)) tmp.delete()
            } catch (_: Throwable) {
            }
        }

        /** Draws every live camera widget again (e.g. after cameras were changed or deleted). Safe on any thread. */
        fun updateAll(c: Context) {
            val app = c.applicationContext ?: c
            main.post {
                val s = LiveService.instance
                val idle = ids(app).filter { s == null || !s.isPlaying(it) }
                s?.reloadAll()
                if (idle.isNotEmpty()) refresh(app, idle.toIntArray(), null)
            }
        }

        /** Widget [id] starts showing pictures. Call it from a screen of the app in front (Android allows it then). */
        fun play(c: Context, id: Int) {
            val app = c.applicationContext ?: c
            try {
                val i = Intent(app, LiveService::class.java).setAction(LiveService.ACTION_PLAY)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
            } catch (_: Throwable) {
                refresh(app, intArrayOf(id), null)
            }
        }

        /** The settings of widget [id] were saved (WidgetSetupActivity, in front): it shows them and plays. Main thread. */
        fun changed(c: Context, id: Int) {
            val s = LiveService.instance
            if (s != null && s.isPlaying(id)) s.reload(id) else play(c, id)
        }

        /** Draws stopped widgets from their kept picture (read on the widgets' thread); playing ones are left to the service. */
        internal fun refresh(c: Context, ids: IntArray, pending: BroadcastReceiver.PendingResult?) {
            val app = c.applicationContext ?: c
            val run = Runnable {
                try {
                    for (id in ids) {
                        if (LiveService.playingIds.contains(id)) continue
                        val conf = conf(app, id)
                        val pic = if (conf != null && conf.at > 0 && conf.picCam.isNotEmpty()) readPicture(cacheFile(app, id)) else null
                        paint(app, id, conf, Look(false, conf?.index ?: 0, pic, conf?.picCam ?: "", conf?.at ?: 0L, false))
                    }
                } catch (_: Throwable) {
                } finally {
                    try {
                        pending?.finish()
                    } catch (_: Throwable) {
                    }
                }
            }
            try {
                io.execute(run)
            } catch (_: Throwable) {
                try {
                    pending?.finish()
                } catch (_: Throwable) {
                }
            }
        }

        /** Sends the widget's look to the home screen (on the widgets' thread, in order). */
        internal fun draw(c: Context, id: Int, conf: LiveConf, look: Look) {
            val app = c.applicationContext ?: c
            work { paint(app, id, conf, look) }
        }

        private fun paint(c: Context, id: Int, conf: LiveConf?, look: Look) {
            try {
                val am = AppWidgetManager.getInstance(c) ?: return
                am.updateAppWidget(id, build(c, id, conf, look))
            } catch (_: Throwable) {
            }
        }

        // request codes: 8 per widget, so every button of every widget has its own PendingIntent
        private fun code(id: Int, k: Int) = id * 8 + k

        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        private fun activity(c: Context, code: Int, i: Intent): PendingIntent = PendingIntent.getActivity(c, code, i, FLAGS)

        // ▶ and ⏭ start the refresh service: widget taps may start a foreground service (Android 12+ too)
        private fun service(c: Context, code: Int, action: String, id: Int): PendingIntent {
            val i = Intent(c, LiveService::class.java).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(c, code, i, FLAGS)
            else PendingIntent.getService(c, code, i, FLAGS)
        }

        private fun broadcast(c: Context, code: Int, action: String, id: Int): PendingIntent =
            PendingIntent.getBroadcast(
                c, code,
                Intent(c, LiveWidget::class.java).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                FLAGS,
            )

        // the settings screen in a task of its own: closing it returns to the home screen
        private fun setup(c: Context, id: Int): PendingIntent = activity(
            c, code(id, 4),
            Intent(c, WidgetSetupActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        )

        private fun build(c: Context, id: Int, conf: LiveConf?, look: Look): RemoteViews {
            val rv = RemoteViews(c.packageName, R.layout.widget_live)
            rv.setTextViewText(R.id.live_setup, "⚙︎")
            rv.setContentDescription(R.id.live_setup, L.t(c, "إعدادات الويدجت", "Widget settings"))
            rv.setOnClickPendingIntent(R.id.live_setup, setup(c, id))
            val cams = if (conf == null) emptyList() else cams(c, conf)

            // not set up yet, or its cameras were deleted from the app
            if (conf == null || cams.isEmpty()) {
                rv.setImageViewResource(R.id.live_pic, 0)
                rv.setViewVisibility(R.id.live_info, View.GONE)
                rv.setViewVisibility(R.id.live_err, View.GONE)
                rv.setViewVisibility(R.id.live_play, View.GONE)
                rv.setViewVisibility(R.id.live_next, View.GONE)
                rv.setViewVisibility(R.id.live_float, View.GONE)
                rv.setViewVisibility(R.id.live_msg, View.VISIBLE)
                rv.setTextViewText(R.id.live_msg, L.t(c, "اضغط ⚙ لاختيار الكاميرات", "Tap ⚙ to choose the cameras"))
                rv.setContentDescription(R.id.live_pic, L.t(c, "اختيار الكاميرات", "Choose the cameras"))
                rv.setOnClickPendingIntent(R.id.live_pic, setup(c, id))
                return rv
            }

            val index = look.index.coerceIn(0, cams.size - 1)
            val cur = cams[index]
            // the picture is shown with its own camera's name (it may be of the previous camera for a moment)
            val picCam = if (look.pic != null) cams.firstOrNull { it.id == look.picCam } else null
            val pic = if (picCam != null) look.pic else null
            val shown = picCam ?: cur

            if (pic != null) rv.setImageViewBitmap(R.id.live_pic, pic) else rv.setImageViewResource(R.id.live_pic, 0)
            rv.setViewVisibility(R.id.live_info, View.VISIBLE)
            rv.setTextViewText(R.id.live_name, shown.name)
            rv.setTextViewText(
                R.id.live_time,
                if (pic != null && look.at > 0) SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(look.at)) else "",
            )
            val msg = when {
                !look.playing -> L.t(c, "متوقف — اضغط ▶", "Stopped — tap ▶")
                pic == null -> L.t(c, "جارٍ التحميل…", "Loading…")
                else -> ""
            }
            rv.setViewVisibility(R.id.live_msg, if (msg.isEmpty()) View.GONE else View.VISIBLE)
            rv.setTextViewText(R.id.live_msg, msg)
            rv.setViewVisibility(R.id.live_err, if (look.playing && look.failed) View.VISIBLE else View.GONE)
            rv.setTextViewText(R.id.live_err, L.t(c, "تعذّر التحديث", "Could not refresh"))

            // a tap on the picture: the live view of that camera
            rv.setOnClickPendingIntent(
                R.id.live_pic,
                activity(
                    c, code(id, 5),
                    Watch.one(c, shown.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                ),
            )
            rv.setContentDescription(R.id.live_pic, L.t(c, "فتح البث المباشر: ${shown.name}", "Open the live view: ${shown.name}"))

            // ▶ / ⏸
            rv.setViewVisibility(R.id.live_play, View.VISIBLE)
            if (look.playing) {
                rv.setTextViewText(R.id.live_play, "⏸︎")
                rv.setContentDescription(R.id.live_play, L.t(c, "إيقاف تحديث الصورة", "Stop refreshing the picture"))
                rv.setOnClickPendingIntent(R.id.live_play, broadcast(c, code(id, 1), ACTION_STOP, id))
            } else {
                rv.setTextViewText(R.id.live_play, "▶︎")
                rv.setContentDescription(R.id.live_play, L.t(c, "تشغيل تحديث الصورة", "Start refreshing the picture"))
                rv.setOnClickPendingIntent(R.id.live_play, service(c, code(id, 0), LiveService.ACTION_PLAY, id))
            }

            // ⏭ only with several cameras
            if (cams.size > 1) {
                rv.setViewVisibility(R.id.live_next, View.VISIBLE)
                rv.setTextViewText(R.id.live_next, "⏭︎")
                rv.setContentDescription(R.id.live_next, L.t(c, "الكاميرا التالية", "Next camera"))
                rv.setOnClickPendingIntent(R.id.live_next, service(c, code(id, 2), LiveService.ACTION_NEXT, id))
            } else {
                rv.setViewVisibility(R.id.live_next, View.GONE)
            }

            // ⧉ floating live video: the widget's cameras from the one shown, in a task of its own (the small
            // window floats over the home screen, not over the app), closing by itself after 5 minutes
            val ids = cams.map { it.id }
            val from = maxOf(0, ids.indexOf(shown.id))
            val order = ids.drop(from) + ids.take(from)
            val floating = Watch.floating(c, order, if (order.size > 1) conf.switchSec else 0, (LiveService.RUN_MS / 1000L).toInt())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            rv.setViewVisibility(R.id.live_float, View.VISIBLE)
            rv.setTextViewText(R.id.live_float, "⧉")
            rv.setContentDescription(R.id.live_float, L.t(c, "فيديو مباشر في نافذة عائمة", "Live video in a floating window"))
            rv.setOnClickPendingIntent(R.id.live_float, activity(c, code(id, 3), floating))
            return rv
        }
    }
}

/**
 * Refreshes the pictures of the live camera widgets that play: one loop for all of them, in the foreground with a
 * quiet notification «تحديث ويدجت الكاميرا» (with «إيقاف»). Each widget stops by itself after 5 minutes; the service
 * stops when none is left. Pictures are taken on background threads, never two at a time for the same camera (two
 * widgets on the same camera share its pictures). While the screen is off no picture is taken.
 */
class LiveService : Service() {
    companion object {
        @Volatile var instance: LiveService? = null
            private set

        /** The widgets that play now (any thread may read it). */
        @Volatile var playingIds: Set<Int> = emptySet()
            private set

        internal const val ACTION_PLAY = "com.dromaia.ipcam.LIVE_PLAY"
        internal const val ACTION_NEXT = "com.dromaia.ipcam.LIVE_NEXT"

        /** Every widget stops by itself after 5 minutes (so does the floating video). */
        const val RUN_MS = 5 * 60_000L

        private const val NOTE_ID = 4301
        private const val CHANNEL = "livewidget"
        private const val TICK_MS = 250L
        private const val STUCK_MS = 12_000L // no picture for this long: «تعذّر التحديث» while it keeps trying
        private const val KEEP_MS = 15_000L  // the picture is kept on disk at most this often (and when it stops)
    }

    private class Play(val id: Int, var conf: LiveConf, var cams: List<Camera>) {
        var until = 0L      // elapsedRealtime when it stops by itself
        var nextSnap = 0L   // elapsedRealtime of the next picture
        var nextSwitch = 0L // elapsedRealtime of the next camera
        var pic: Bitmap? = null
        var picCam = ""
        var picAt = 0L
        var failed = false
        var keptAt = 0L
    }

    private class Pic(val bmp: Bitmap, val at: Long)

    // all of these on the main thread only
    private val main = Handler(Looper.getMainLooper())
    private val plays = LinkedHashMap<Int, Play>()
    private val busy = HashMap<String, Long>()   // camera id → when its picture was asked (one at a time)
    private val pics = HashMap<String, Pic>()    // the last picture of each camera shown by a playing widget
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private var lastStart = 0
    private var finishing = false
    private var dead = false
    private val tick = object : Runnable {
        override fun run() {
            loop()
            if (!dead && plays.isNotEmpty()) main.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        finishing = false
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // every start must be answered with startForeground (it was started with startForegroundService)
        if (!promote()) {
            // Android did not allow it: the widget stays stopped
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) LiveWidget.refresh(this, intArrayOf(id), null)
            stopAll()
            finish()
            return START_NOT_STICKY
        }
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            when (intent?.action) {
                ACTION_PLAY -> play(id)
                ACTION_NEXT -> next(id)
            }
        }
        if (plays.isEmpty()) finish()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        dead = true
        main.removeCallbacksAndMessages(null)
        val left = plays.values.toList()
        plays.clear()
        published()
        for (p in left) {
            keep(p)
            draw(p, false)
        }
        try {
            pool.shutdown()
        } catch (_: Throwable) {
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Widget [id] is refreshing now. Main thread. */
    fun isPlaying(id: Int): Boolean = plays.containsKey(id)

    /** ⏸ (or 5 minutes passed): widget [id] stops and keeps its last picture. Main thread. */
    fun stopWidget(id: Int) {
        val p = plays.remove(id)
        if (p == null) {
            LiveWidget.refresh(this, intArrayOf(id), null)
            return
        }
        published()
        keep(p)
        draw(p, false)
        if (plays.isEmpty()) finish()
    }

    /** «إيقاف» in the notification: every widget stops. Main thread. */
    fun stopAll() {
        for (id in plays.keys.toList()) stopWidget(id)
        if (plays.isEmpty()) finish()
    }

    /** Its settings were saved: the widget shows them (and plays 5 minutes from now). Main thread. */
    fun reload(id: Int) = play(id)

    /** The cameras of the app changed: the playing widgets read them again. Main thread. */
    fun reloadAll() {
        for (p in plays.values.toList()) {
            val conf = LiveWidget.conf(this, p.id)
            val cams = if (conf == null) emptyList() else LiveWidget.cams(this, conf)
            if (conf == null || cams.isEmpty()) {
                stopWidget(p.id)
                continue
            }
            val curId = p.cams.getOrNull(p.conf.index)?.id
            p.conf = conf
            p.cams = cams
            val i = cams.indexOfFirst { it.id == curId }
            p.conf.index = if (i >= 0) i else conf.index.coerceIn(0, cams.size - 1)
            draw(p, true)
        }
    }

    /** The widget was removed from the home screen. Main thread. */
    fun forget(id: Int) {
        if (plays.remove(id) == null) return
        published()
        if (plays.isEmpty()) finish()
    }

    // ---------------------------------------------------------------- playing

    private fun play(id: Int) {
        val conf = LiveWidget.conf(this, id)
        val cams = if (conf == null) emptyList() else LiveWidget.cams(this, conf)
        if (conf == null || cams.isEmpty()) {
            if (plays.remove(id) != null) published()
            LiveWidget.refresh(this, intArrayOf(id), null)
            return
        }
        conf.index = conf.index.coerceIn(0, cams.size - 1)
        val now = SystemClock.elapsedRealtime()
        val old = plays[id]
        val p = old ?: Play(id, conf, cams)
        p.conf = conf
        p.cams = cams
        p.until = now + RUN_MS
        p.nextSnap = now
        p.nextSwitch = now + conf.switchSec * 1000L
        p.failed = false
        plays[id] = p
        published()
        val cam = cams[conf.index]
        if (p.picCam != cam.id) showKept(p, cam)
        draw(p, true)
        main.removeCallbacks(tick)
        main.post(tick)
    }

    // ⏭: the next camera at once (a stopped widget starts on it)
    private fun next(id: Int) {
        val p = plays[id]
        if (p != null) {
            switchTo(p, p.conf.index + 1, SystemClock.elapsedRealtime())
            return
        }
        val conf = LiveWidget.conf(this, id)
        if (conf != null) {
            val n = LiveWidget.cams(this, conf).size
            if (n > 1) {
                conf.index = (conf.index.coerceIn(0, n - 1) + 1) % n
                LiveWidget.saveConf(this, id, conf)
            }
        }
        play(id)
    }

    private fun switchTo(p: Play, index: Int, now: Long) {
        val n = p.cams.size
        if (n == 0) return
        p.conf.index = ((index % n) + n) % n
        p.nextSwitch = now + p.conf.switchSec * 1000L
        p.nextSnap = now
        p.failed = false
        showKept(p, p.cams[p.conf.index])
        LiveWidget.saveConf(this, p.id, p.conf)
        draw(p, true)
    }

    // the last picture of [cam] while its new one comes: from memory, else the one kept on disk for this widget
    private fun showKept(p: Play, cam: Camera) {
        val mem = pics[cam.id]
        p.pic = mem?.bmp
        p.picCam = if (mem != null) cam.id else ""
        p.picAt = mem?.at ?: 0L
        if (mem != null || p.conf.picCam != cam.id || p.conf.at <= 0L) return
        val file = LiveWidget.cacheFile(this, p.id)
        val at = p.conf.at
        LiveWidget.work {
            val b = LiveWidget.readPicture(file)
            if (b != null) {
                main.post {
                    if (!dead && plays[p.id] === p && p.pic == null && p.cams.getOrNull(p.conf.index)?.id == cam.id) {
                        p.pic = b
                        p.picCam = cam.id
                        p.picAt = at
                        draw(p, true)
                    }
                }
            }
        }
    }

    private fun loop() {
        if (dead) return
        val now = SystemClock.elapsedRealtime()
        val awake = screenOn()
        for (p in plays.values.toList()) {
            if (now >= p.until) {
                stopWidget(p.id)
                continue
            }
            if (p.cams.size > 1 && now >= p.nextSwitch) switchTo(p, p.conf.index + 1, now)
            val cam = p.cams.getOrNull(p.conf.index) ?: continue
            val since = busy[cam.id]
            if (since != null) {
                if (!p.failed && now - since > STUCK_MS) {
                    p.failed = true
                    draw(p, true)
                }
            } else if (awake && now >= p.nextSnap) {
                grab(cam)
            }
        }
    }

    // nobody sees the widget with the screen off: no picture is taken then
    private fun screenOn(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false
    } catch (_: Throwable) {
        true
    }

    private fun grab(cam: Camera) {
        val started = SystemClock.elapsedRealtime()
        busy[cam.id] = started
        val app = applicationContext
        try {
            pool.execute {
                val b = try {
                    Snap.grab(app, cam)
                } catch (_: Throwable) {
                    null
                }
                main.post { grabbed(cam.id, b, started) }
            }
        } catch (_: Throwable) {
            busy.remove(cam.id)
        }
    }

    private fun grabbed(camId: String, b: Bitmap?, started: Long) {
        busy.remove(camId)
        if (dead) return
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        if (b != null) pics[camId] = Pic(b, wall)
        for (p in plays.values) {
            if (p.cams.getOrNull(p.conf.index)?.id != camId) continue
            if (b != null) {
                p.pic = b
                p.picCam = camId
                p.picAt = wall
                p.failed = false
                if (now - p.keptAt >= KEEP_MS) keep(p)
            } else {
                p.failed = true
            }
            // the next picture: at the chosen interval from the start of this one (after a failure at least 2 s)
            val gap = p.conf.snapSec * 1000L
            p.nextSnap = maxOf(started + gap, now + if (b != null) 200L else 2_000L)
            draw(p, true)
        }
        // pictures of cameras no playing widget shows are let go
        val shown = HashSet<String>()
        for (p in plays.values) for (cam in p.cams) shown.add(cam.id)
        pics.keys.retainAll(shown)
    }

    // the picture is kept on disk (and its time and camera in the settings) so the widget shows it after a restart
    private fun keep(p: Play) {
        val b = p.pic ?: return
        if (p.picCam.isEmpty()) return
        p.keptAt = SystemClock.elapsedRealtime()
        p.conf.at = p.picAt
        p.conf.picCam = p.picCam
        LiveWidget.saveConf(this, p.id, p.conf)
        val file = LiveWidget.cacheFile(this, p.id)
        LiveWidget.work { LiveWidget.writePicture(file, b) }
    }

    private fun draw(p: Play, playing: Boolean) {
        LiveWidget.draw(this, p.id, p.conf, Look(playing, p.conf.index, p.pic, p.picCam, p.picAt, p.failed))
    }

    private fun published() {
        playingIds = HashSet(plays.keys)
    }

    // nothing left to refresh: the notification goes and the service ends (unless a newer start just arrived)
    private fun finish() {
        if (finishing) return
        finishing = true
        main.removeCallbacks(tick)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        try {
            stopSelf(lastStart)
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- notification

    private fun promote(): Boolean = try {
        val n = note()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTE_ID, n)
        }
        true
    } catch (_: Throwable) {
        false
    }

    private fun note(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, L.t(this, "ويدجت الكاميرا الحيّة", "Live camera widget"), NotificationManager.IMPORTANCE_LOW),
            )
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getBroadcast(
            this, 1, Intent(this, LiveWidget::class.java).setAction(LiveWidget.ACTION_STOP_ALL), flags,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags,
        )
        val stopText = L.t(this, "إيقاف", "Stop")
        return b.setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(L.t(this, "تحديث ويدجت الكاميرا", "Updating the camera widget"))
            .setContentText(L.t(this, "يتوقف وحده بعد 5 دقائق", "Stops by itself after 5 minutes"))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), stopText, stop).build())
            .build()
    }
}
