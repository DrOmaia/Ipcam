package com.dromaia.ipcam

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

// Update from inside the app (same way as the prayer-times app): download the latest release with the system's
// download manager, then open the Android installer. Works while the GitHub repository is public.
object Update {
    const val URL = "https://github.com/DrOmaia/Ipcam/releases/latest/download/ipcam.apk"
    private const val APK = "application/vnd.android.package-archive"
    private const val PREFS = "ipcam-update"

    const val NEEDS_PERMISSION = -1L
    const val FAILED = -2L

    fun version(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    /** Starts the download; returns its id, or NEEDS_PERMISSION / FAILED. */
    fun start(a: Activity): Long {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            try {
                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            } catch (_: Exception) {
            }
            return NEEDS_PERMISSION
        }
        return try {
            val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()?.forEach { it.delete() }
            val req = DownloadManager.Request(Uri.parse(URL))
                .setTitle("كاميرات البيت — آخر نسخة")
                .setMimeType(APK)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, "ipcam.apk")
            dm.enqueue(req)
        } catch (_: Exception) {
            FAILED
        }
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
            prefs.edit().putLong("installedAt", installed).apply()
        } catch (_: Exception) {
        }
    }
}
