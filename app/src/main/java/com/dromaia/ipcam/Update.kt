package com.dromaia.ipcam

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Updates from inside the app (same way as the prayer-times app and Mani): the screen shows this version, the newest
// version on GitHub and the stable one (file STABLE = the last version the owner tested); the download manager fetches
// the APK and the Android installer opens. Android only installs a higher version number over a lower one.
object Update {
    private const val REPO = "DrOmaia/Ipcam"
    private const val APK = "application/vnd.android.package-archive"
    private const val PREFS = "ipcam-update"

    const val NEEDS_PERMISSION = -1L
    const val FAILED = -2L

    class Info(val latest: Int, val stable: Int)

    fun url(version: Int) = "https://github.com/$REPO/releases/download/v$version/ipcam.apk"

    fun versionName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    /** This app's build number (= the release vN it came from; 1 for a local build). */
    fun versionNumber(c: Context): Int = try {
        @Suppress("DEPRECATION")
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    } catch (_: Exception) {
        0
    }

    private fun get(url: String): String? = try {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 8000
        con.readTimeout = 8000
        con.setRequestProperty("Accept", "application/vnd.github+json")
        con.setRequestProperty("User-Agent", "ipcam-app")
        try {
            if (con.responseCode == 200) con.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            con.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** Blocking (call off the main thread): newest and stable release numbers; 0 = unknown / none. */
    fun check(): Info {
        val latest = get("https://api.github.com/repos/$REPO/releases/latest")?.let {
            try { JSONObject(it).optString("tag_name").removePrefix("v").toIntOrNull() } catch (_: Exception) { null }
        } ?: 0
        val stable = get("https://raw.githubusercontent.com/$REPO/main/STABLE")?.trim()?.removePrefix("v")?.toIntOrNull() ?: 0
        return Info(latest, stable)
    }

    /**
     * Starts downloading release v[version]; returns its download id, or NEEDS_PERMISSION / FAILED.
     * If an update is already downloading (or waiting for the network), returns that one and leaves its file alone.
     */
    fun start(a: Activity, version: Int): Long {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            try {
                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            } catch (_: Exception) {
            }
            return NEEDS_PERMISSION
        }
        return try {
            val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val busy = running(dm)
            if (busy >= 0) return busy
            a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()?.forEach { it.delete() }
            val req = DownloadManager.Request(Uri.parse(url(version)))
                .setTitle(L.t(a, "كاميرات البيت — النسخة $version", "Home Cameras — version $version"))
                .setMimeType(APK)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, "ipcam.apk")
            dm.enqueue(req)
        } catch (_: Exception) {
            FAILED
        }
    }

    // a download of this app that is waiting, running or paused (-1 = none)
    private fun running(dm: DownloadManager): Long {
        val q = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED
        )
        dm.query(q)?.use { cur ->
            if (cur.moveToFirst()) return cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
        }
        return -1L
    }

    /** -1 = failed, 0..99 = downloading (percent), 100 = done. */
    fun progress(c: Context, id: Long): Int {
        try {
            val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                if (!cur.moveToFirst()) return -1
                val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val got = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                return when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> 100
                    DownloadManager.STATUS_FAILED -> -1
                    else -> if (total > 0) (got * 99 / total).toInt() else 0
                }
            }
        } catch (_: Exception) {
        }
        return -1
    }

    fun install(a: Activity, id: Long) {
        val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(id) ?: return
        try {
            a.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    // once the new version is installed, the downloaded file is not needed: remove it and its downloads entry
    fun cleanAfterUpdate(c: Context) {
        try {
            val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val installed = c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime
            if (prefs.getLong("installedAt", 0L) == installed) return
            val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val ids = ArrayList<Long>()
            dm.query(DownloadManager.Query())?.use { cur ->
                while (cur.moveToNext()) ids.add(cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)))
            }
            if (ids.isNotEmpty()) dm.remove(*ids.toLongArray())
            c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()?.forEach { it.delete() }
            Prefs.putLong(c, "updId", -1L)
            prefs.edit().putLong("installedAt", installed).apply()
        } catch (_: Exception) {
        }
    }
}
