package com.dromaia.ipcam

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import java.security.MessageDigest

/**
 * Every screen extends this:
 *  - right to left in Arabic, left to right in English;
 *  - the optional PIN lock (LockActivity) before any screen when the app comes back after a while;
 *  - t("عربي", "English") for the texts, [tv] for the TV layout.
 */
abstract class Base : Activity() {
    val tv: Boolean by lazy { Device.isTv(this) }

    /** false only for the lock screen itself. */
    open val lockable = true

    fun t(ar: String, en: String) = L.t(this, ar, en)

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.decorView.layoutDirection = if (L.en(this)) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
        privateInRecents()
    }

    override fun onResume() {
        // super.onResume() runs App's onActivityResumed first (Lock.resumed), so the check below sees the new state
        super.onResume()
        privateInRecents()
        if (lockable && Lock.needed(this)) startActivity(Intent(this, LockActivity::class.java))
    }

    // With the PIN lock on, the recent-apps screen shows no picture of the cameras (Android 13+).
    // Not FLAG_SECURE: that would also block casting the screen and taking screenshots.
    private fun privateInRecents() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                setRecentsScreenshotEnabled(!Lock.enabled(this))
            } catch (_: Exception) {
            }
        }
    }
}

// Intent extras of the live view (WatchActivity)
object Watch {
    const val ID = "id"       // String: start on this camera alone (full screen)
    const val GROUP = "group" // String: only the cameras of this group
    const val TOUR = "tour"   // Boolean: automatic tour, one camera after the other
    const val IDS = "ids"               // String array: only these cameras, in this order (alone, on the first one)
    const val PIP = "pip"               // Boolean: go to the small floating window at once (phones)
    const val CYCLE = "cycle"           // Int: seconds before the next camera (0 = no cycling)
    const val STOP_AFTER = "stopAfter"  // Int: seconds before the live view closes by itself (0 = never)

    fun one(c: Context, id: String) = Intent(c, WatchActivity::class.java).putExtra(ID, id)
    fun all(c: Context, group: String? = null) = Intent(c, WatchActivity::class.java).also { if (group != null) it.putExtra(GROUP, group) }
    fun tour(c: Context, group: String? = null) = all(c, group).putExtra(TOUR, true)

    /** Floating live video (the live camera widget's ⧉): these cameras in a small window, one after the other. */
    fun floating(c: Context, ids: List<String>, cycleSec: Int, stopAfterSec: Int): Intent =
        Intent(c, WatchActivity::class.java)
            .putExtra(IDS, ids.toTypedArray())
            .putExtra(PIP, true)
            .putExtra(CYCLE, cycleSec)
            .putExtra(STOP_AFTER, stopAfterSec)
}

// The optional PIN: asked when the app opens, and again when the app comes back after more than 30 seconds away.
// "Away" = no screen of the app in front (every screen is paused): home button, another app, screen off,
// the live view in picture-in-picture. Moving between the app's own screens (or rotating) takes a moment only.
object Lock {
    const val AWAY_MS = 30_000L
    @Volatile var unlocked = false
    @Volatile var backgroundAt = 0L // when the last screen was paused (0 = a screen of the app is in front)
    @Volatile var started = 0       // screens started (visible): the TV motion pop-up stays away while the app shows

    fun enabled(c: Context) = Prefs.pinHash(c).isNotEmpty()

    fun needed(c: Context): Boolean = enabled(c) && !unlocked

    /** A screen of the app was paused: the time away starts now. */
    fun paused() {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    /** A screen of the app was resumed: after more than 30 seconds away the PIN is asked again. */
    fun resumed() {
        val at = backgroundAt
        if (at > 0 && SystemClock.elapsedRealtime() - at > AWAY_MS) unlocked = false
        backgroundAt = 0L
    }

    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(("ipcam:" + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    fun check(c: Context, pin: String): Boolean = hash(pin) == Prefs.pinHash(c)

    /** The right PIN was entered. */
    fun open() {
        unlocked = true
        backgroundAt = 0L
    }

    fun set(c: Context, pin: String?) {
        Prefs.put(c, "pinHash", if (pin.isNullOrEmpty()) "" else hash(pin))
        // a new PIN (or none): the count of wrong tries starts again
        Prefs.put(c, "pinWrong", 0)
        Prefs.putLong(c, "pinWait", 0L)
        open()
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                Lock.started++
                // the app is opened: the TV motion pop-up (if any) goes away
                try {
                    Alerts.hidePop()
                } catch (_: Exception) {
                }
            }

            override fun onActivityStopped(a: Activity) {
                Lock.started = maxOf(0, Lock.started - 1)
            }

            override fun onActivityResumed(a: Activity) = Lock.resumed()
            override fun onActivityPaused(a: Activity) = Lock.paused()
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        // motion alerts keep running after a restart of the app (the service decides whether there is anything to watch)
        try {
            Alerts.refresh(this)
        } catch (_: Exception) {
        }
    }
}
