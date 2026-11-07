package com.dromaia.ipcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Push-to-talk from this device to a phone in camera mode (Brand.PHONE): while the button is held, the microphone
 * (16 kHz mono, 16-bit) goes over the home Wi-Fi to that phone (port 8555), which plays it on its loud speaker.
 * The first line sent is "TALK ipcam <password>" (the camera's own password, to the camera itself only).
 */
object Talk {
    const val PORT = 8555
    private const val RATE = 16_000

    private class Session(val onError: (String) -> Unit) {
        @Volatile var on = true
        @Volatile var socket: Socket? = null
    }

    private val lock = Any()
    private var session: Session? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * Starts talking. Returns false when the microphone permission is missing (the caller asks for it) or the
     * microphone cannot start. Connecting happens in the background: if it fails, [onError] gets a short message
     * (Arabic or English) on the main thread.
     */
    @SuppressLint("MissingPermission")
    fun start(c: Context, cam: Camera, onError: (String) -> Unit): Boolean {
        val app = c.applicationContext
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        stop()
        val host = cam.address()
        if (host.isEmpty()) {
            main.post { onError(L.t(app, "عنوان هذه الكاميرا فارغ. عدّل الكاميرا.", "This camera has no address. Edit the camera.")) }
            return false
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            if (min <= 0) null
            else AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200) * 2)
        } catch (_: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec?.release() } catch (_: Exception) {}
            main.post { onError(L.t(app, "تعذّر تشغيل الميكروفون.", "Could not start the microphone.")) }
            return false
        }
        val s = Session(onError)
        synchronized(lock) { session = s }
        val th = Thread({ run(app, s, rec, host, cam.pass) }, "ipcam-talk")
        th.isDaemon = true
        th.start()
        return true
    }

    /** Stops talking (button released). Safe to call any time, also when not talking. */
    fun stop() {
        val s = synchronized(lock) { session.also { session = null } } ?: return
        s.on = false
        // unblock a stuck connection without network work on the caller's thread
        val sock = s.socket ?: return
        val th = Thread({ try { sock.close() } catch (_: Exception) {} }, "ipcam-talk-stop")
        th.isDaemon = true
        th.start()
    }

    private fun run(app: Context, s: Session, rec: AudioRecord, host: String, pass: String) {
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        val sock = Socket()
        s.socket = sock
        try {
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, PORT), 4000)
            if (!s.on) return
            sock.soTimeout = 5000
            val out = sock.getOutputStream()
            out.write("TALK ${CameraService.USER} $pass\n".toByteArray(Charsets.UTF_8))
            out.flush()
            when (readLine(sock.getInputStream())) {
                "OK" -> {}
                "BUSY" -> {
                    fail(s, L.t(app, "شخص آخر يتكلم عبر هذا الجوال الآن. حاول بعد قليل.", "Someone else is talking through this phone now. Try again shortly."))
                    return
                }
                else -> {
                    fail(s, L.t(app, "كلمة المرور غير صحيحة. اكتب كلمة مرور الجوال الكاميرا في «تعديل الكاميرا».", "Wrong password. Type the camera phone's password in “Edit camera”."))
                    return
                }
            }
            sock.soTimeout = 0
            // the viewer may be playing the camera's sound: keep it out of what he sends
            try {
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.setEnabled(true) }
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.setEnabled(true) }
            } catch (_: Exception) {
            }
            rec.startRecording()
            val buf = ByteArray(640) // 20 ms
            while (s.on) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0) out.write(buf, 0, n)
            }
        } catch (_: Exception) {
            fail(s, L.t(app, "تعذّر الوصول إلى الجوال الكاميرا. تأكد أن وضع الكاميرا يعمل عليه وأنه على نفس الواي فاي.",
                "Could not reach the camera phone. Make sure camera mode is on and it is on the same Wi-Fi."))
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
            try { aec?.release() } catch (_: Exception) {}
            try { ns?.release() } catch (_: Exception) {}
            try { sock.close() } catch (_: Exception) {}
            synchronized(lock) { if (session === s) session = null }
        }
    }

    /** Tells the screen, unless the user already let go of the button. */
    private fun fail(s: Session, msg: String) {
        if (!s.on) return
        s.on = false
        main.post { s.onError(msg) }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 100) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }
}
