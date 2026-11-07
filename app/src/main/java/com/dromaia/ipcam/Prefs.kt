package com.dromaia.ipcam

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings

// App settings of this device (not the cameras: those are in Store). Never rename a key without reading the old one.
object Prefs {
    private const val NAME = "ipcam-settings"

    fun sp(c: Context): SharedPreferences = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    fun str(c: Context, key: String, def: String = ""): String = sp(c).getString(key, def) ?: def
    fun bool(c: Context, key: String, def: Boolean): Boolean = sp(c).getBoolean(key, def)
    fun int(c: Context, key: String, def: Int): Int = sp(c).getInt(key, def)
    fun long(c: Context, key: String, def: Long): Long = sp(c).getLong(key, def)
    fun put(c: Context, key: String, v: String) = sp(c).edit().putString(key, v).apply()
    fun put(c: Context, key: String, v: Boolean) = sp(c).edit().putBoolean(key, v).apply()
    fun put(c: Context, key: String, v: Int) = sp(c).edit().putInt(key, v).apply()
    // a separate name (not put) so that put(c, key, 0) stays an Int
    fun putLong(c: Context, key: String, v: Long) = sp(c).edit().putLong(key, v).apply()

    /** "ar" (default) or "en". */
    fun lang(c: Context) = str(c, "lang", "ar")

    /** Open straight on all cameras when the app starts (default: on a TV yes, on a phone no). */
    fun openAll(c: Context) = bool(c, "openAll", Device.isTv(c))

    /** Seconds each camera stays on screen in the automatic tour. */
    fun tourSec(c: Context) = int(c, "tourSec", 15)

    /** Motion alerts on this device (phone: notification; TV: the camera pops up over other apps). */
    fun alertsOn(c: Context) = bool(c, "alertsOn", false)

    /** Alert hours, minutes after midnight; from == to means all day. */
    fun alertFrom(c: Context) = int(c, "alertFrom", 0)
    fun alertTo(c: Context) = int(c, "alertTo", 0)

    /** SHA-256 of the app PIN, or "" when the lock is off. */
    fun pinHash(c: Context) = str(c, "pinHash", "")

    /** Wrong PINs in a row (kept after the app closes), and until when (clock time, ms) no PIN is accepted. */
    fun pinWrong(c: Context) = int(c, "pinWrong", 0)
    fun pinWait(c: Context) = long(c, "pinWait", 0L)

    /** Download id of an update being downloaded (-1 = none). */
    fun updId(c: Context) = long(c, "updId", -1L)
}

// Arabic / English: every text in the app is written as L.t(context, "عربي", "English")
object L {
    fun en(c: Context) = Prefs.lang(c) == "en"
    fun t(c: Context, ar: String, en: String) = if (en(c)) en else ar
}

object Device {
    fun isTv(c: Context): Boolean {
        val ui = c.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            c.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** A friendly name of this device for the other devices (e.g. "TCL Google TV", "Galaxy S23"). */
    fun name(c: Context): String {
        val n = try {
            if (Build.VERSION.SDK_INT >= 25) Settings.Global.getString(c.contentResolver, Settings.Global.DEVICE_NAME) else null
        } catch (_: Exception) {
            null
        }
        if (!n.isNullOrBlank()) return n
        val m = Build.MODEL ?: ""
        val maker = (Build.MANUFACTURER ?: "").replaceFirstChar { it.uppercase() }
        return if (m.startsWith(maker, ignoreCase = true)) m else "$maker $m".trim()
    }
}
