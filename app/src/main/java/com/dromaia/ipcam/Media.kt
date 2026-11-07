package com.dromaia.ipcam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves photos and videos of the cameras in the device's gallery:
 * Pictures/كاميرات البيت and Movies/كاميرات البيت.
 *  - Android 10 and newer: through MediaStore (no permission needed; hidden until the file is complete).
 *  - Android 7–9: the public folders, then the media scanner (needs WRITE_EXTERNAL_STORAGE first).
 * Disk work: call these off the main thread.
 */
object Media {
    const val FOLDER = "كاميرات البيت"

    /** Android 7–9 need the storage permission before saving in the gallery; Android 10 and newer do not. */
    fun needsPermission(c: Context): Boolean =
        Build.VERSION.SDK_INT < 29 &&
            c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /** A file name from the camera name and the time, e.g. "الحوش_20261107_211530" (no extension). */
    fun stamp(label: String): String {
        val clean = label.trim().replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").take(40).ifEmpty { "camera" }
        return clean + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    /** Saves a photo as JPEG in Pictures/كاميرات البيت. true = saved. */
    fun saveImage(c: Context, bmp: Bitmap, name: String): Boolean {
        val file = if (name.endsWith(".jpg", ignoreCase = true)) name else "$name.jpg"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cr = c.contentResolver
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, file)
                v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
                v.put(MediaStore.MediaColumns.IS_PENDING, 1)
                val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return false
                val ok = try {
                    cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: false
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    val done = ContentValues()
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    cr.update(uri, done, null, null)
                } else {
                    cr.delete(uri, null, null)
                }
                ok
            } else {
                val dir = File(legacyDir(Environment.DIRECTORY_PICTURES), FOLDER)
                if (!dir.isDirectory && !dir.mkdirs()) return false
                val f = File(dir, file)
                val ok = FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                if (ok && f.length() > 0) {
                    scan(c, f, "image/jpeg")
                    true
                } else {
                    f.delete()
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /** A new, empty video in Movies/كاميرات البيت for the Recorder; null if it cannot be made. */
    fun newVideo(c: Context, name: String): VideoTarget? {
        val file = if (name.endsWith(".mp4", ignoreCase = true)) name else "$name.mp4"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cr = c.contentResolver
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, file)
                v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER)
                v.put(MediaStore.MediaColumns.IS_PENDING, 1)
                val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return null
                val pfd = try {
                    cr.openFileDescriptor(uri, "rw")
                } catch (_: Exception) {
                    null
                }
                if (pfd == null) {
                    cr.delete(uri, null, null)
                    null
                } else {
                    VideoTarget(uri, null, pfd)
                }
            } else {
                val dir = File(legacyDir(Environment.DIRECTORY_MOVIES), FOLDER)
                if (!dir.isDirectory && !dir.mkdirs()) return null
                VideoTarget(null, File(dir, file), null)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Where a recording is written: a file path (Android 7–9) or an open file descriptor (Android 10+). */
    class VideoTarget internal constructor(
        val uri: Uri?,   // Android 10+: the gallery entry, hidden until finish()
        val file: File?, // Android 7–9: the file itself
        private val pfd: ParcelFileDescriptor?,
    ) {
        val path: String? get() = file?.absolutePath
        val fd: FileDescriptor? get() = pfd?.fileDescriptor

        /**
         * After the recording: keep = it worked. An empty file is always deleted.
         * Returns true when the video is now in the gallery.
         */
        fun finish(c: Context, keep: Boolean): Boolean {
            // -1 = size unknown (then trust [keep]); 0 = nothing was written
            val size: Long = try {
                pfd?.statSize ?: file?.length() ?: 0L
            } catch (_: Exception) {
                -1L
            }
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
            val ok = keep && size != 0L
            val u = uri
            if (u != null) {
                return try {
                    val cr = c.contentResolver
                    if (ok && Build.VERSION.SDK_INT >= 29) {
                        val done = ContentValues()
                        done.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        cr.update(u, done, null, null)
                        true
                    } else {
                        cr.delete(u, null, null)
                        false
                    }
                } catch (_: Exception) {
                    false
                }
            }
            val f = file ?: return false
            return if (ok) {
                scan(c, f, "video/mp4")
                true
            } else {
                try {
                    f.delete()
                } catch (_: Exception) {
                }
                false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyDir(type: String): File = Environment.getExternalStoragePublicDirectory(type)

    // Android 7–9: tell the gallery about the new file
    private fun scan(c: Context, f: File, mime: String) {
        try {
            MediaScannerConnection.scanFile(c.applicationContext, arrayOf(f.absolutePath), arrayOf(mime), null)
        } catch (_: Exception) {
        }
    }
}
