package com.dromaia.ipcam

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
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
    }

    override fun onResume() {
        super.onResume()
        if (lockable && Lock.needed(this)) startActivity(Intent(this, LockActivity::class.java))
    }
}

// Intent extras of the live view (WatchActivity)
object Watch {
    const val ID = "id"       // String: start on this camera alone (full screen)
    const val GROUP = "group" // String: only the cameras of this group
    const val TOUR = "tour"   // Boolean: automatic tour, one camera after the other

    fun one(c: Context, id: String) = Intent(c, WatchActivity::class.java).putExtra(ID, id)
    fun all(c: Context, group: String? = null) = Intent(c, WatchActivity::class.java).also { if (group != null) it.putExtra(GROUP, group) }
    fun tour(c: Context, group: String? = null) = all(c, group).putExtra(TOUR, true)
}

// The optional PIN: asked when the app opens, and again after it was in the background for more than 30 seconds
object Lock {
    private const val AWAY_MS = 30_000L
    @Volatile var unlocked = false
    @Volatile var backgroundAt = 0L
    @Volatile var started = 0

    fun enabled(c: Context) = Prefs.pinHash(c).isNotEmpty()

    fun needed(c: Context): Boolean {
        if (!enabled(c)) return false
        if (unlocked && backgroundAt > 0 && started <= 1 && SystemClock.elapsedRealtime() - backgroundAt > AWAY_MS) unlocked = false
        return !unlocked
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
        open()
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                Lock.started++
            }

            override fun onActivityStopped(a: Activity) {
                Lock.started = maxOf(0, Lock.started - 1)
                if (Lock.started == 0) Lock.backgroundAt = SystemClock.elapsedRealtime()
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
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
