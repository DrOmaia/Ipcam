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
import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Push-to-talk from this device to a phone in camera mode (Brand.PHONE): while the button is held, the microphone
 * (16 kHz mono, 16-bit) goes over the home Wi-Fi to that phone (port 8555), which plays it on its loud speaker.
 * The password itself never crosses the network: the phone sends a random challenge and this device answers with
 * a proof made from it and the password ([TalkAuth]).
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
            when (TalkAuth.viewerSide(sock.getInputStream(), out, pass)) {
                TalkAuth.OK -> {}
                TalkAuth.BUSY -> {
                    fail(s, L.t(app, "شخص آخر يتحدّث عبر هذا الجوال الآن. حاول بعد قليل.", "Someone else is talking through this phone now. Try again shortly."))
                    return
                }
                TalkAuth.WAIT -> {
                    fail(s, L.t(app, "محاولات كثيرة بكلمة مرور خاطئة على جوال الكاميرا. حاول بعد قليل.", "Too many wrong passwords on the camera phone. Try again shortly."))
                    return
                }
                TalkAuth.WRONG -> {
                    fail(s, L.t(app, "كلمة المرور غير صحيحة. اكتب كلمة مرور جوال الكاميرا في «تعديل الكاميرا».", "Wrong password. Type the camera phone's password in “Edit camera”."))
                    return
                }
                TalkAuth.OLD -> {
                    fail(s, L.t(app, "جوال الكاميرا لم يردّ. حدّث التطبيق على الجهازين ثم حاول مرة أخرى.", "The camera phone did not answer. Update the app on both devices, then try again."))
                    return
                }
                else -> throw java.io.IOException("no answer")
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
            fail(s, L.t(app, "تعذّر الوصول إلى جوال الكاميرا. تأكد أن وضع الكاميرا يعمل عليه وأنه على نفس الواي فاي.",
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
}

/**
 * The talk sign-in, without the password on the network (one text line each way):
 *  camera → "CHALLENGE <random, Base64>"; viewer → "TALK ipcam <Base64 HMAC-SHA256(key = password, the challenge text)>";
 *  camera → "OK", "BUSY" (someone else talks), "WAIT" (too many wrong passwords lately, [Guard]) or "ERR".
 */
internal object TalkAuth {
    const val OK = "OK"
    const val BUSY = "BUSY"
    const val WAIT = "WAIT"
    const val WRONG = "ERR"
    const val OLD = "OLD" // (viewer only) no challenge came: the camera phone runs an older version of the app
    private const val PROOF_CHARS = 44 // Base64 of the 32 bytes of HMAC-SHA256

    private val random = SecureRandom()

    /** Base64 HMAC-SHA256 of the challenge text with the password as key ("" for an empty password). */
    fun proof(pass: String, challenge: String): String {
        if (pass.isEmpty()) return ""
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pass.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(challenge.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /**
     * Camera side: sends the challenge, reads and checks the answer through [Guard]. Returns true when the password
     * is right (the caller then answers OK or BUSY); otherwise it has already answered WAIT or ERR.
     */
    fun cameraSide(input: InputStream, out: OutputStream, pass: String): Boolean {
        val challenge = Base64.encodeToString(ByteArray(18).also { random.nextBytes(it) }, Base64.NO_WRAP)
        send(out, "CHALLENGE $challenge")
        val parts = line(input).split(' ')
        val answer = if (parts.size == 3 && parts[0] == "TALK") parts[2] else ""
        // no proof at all (an older viewer sending its password, or nothing): no password is compared, so not counted
        val ok = if (answer.length != PROOF_CHARS || pass.isEmpty()) false
        else Guard.check { RtspServer.same(answer, proof(pass, challenge)) }
        when (ok) {
            true -> return true
            null -> send(out, WAIT)
            false -> {
                Thread.sleep(1000) // guessing the password takes long
                send(out, WRONG)
            }
        }
        return false
    }

    /** Viewer side: answers the challenge; returns the camera's answer (OK, BUSY, WAIT, ERR), OLD, or "" (no answer). */
    fun viewerSide(input: InputStream, out: OutputStream, pass: String): String {
        val first = try {
            line(input)
        } catch (_: SocketTimeoutException) {
            ""
        }
        if (!first.startsWith("CHALLENGE ")) return OLD
        val challenge = first.substring(10).trim()
        if (challenge.isEmpty()) return OLD
        send(out, "TALK ${CameraService.USER} ${proof(pass, challenge)}")
        return line(input)
    }

    private fun send(out: OutputStream, s: String) {
        out.write("$s\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** One text line (at most 200 characters, without the line end). */
    fun line(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 200) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }
}
