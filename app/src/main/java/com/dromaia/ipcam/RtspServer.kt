package com.dromaia.ipcam

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The small video server of the camera mode: other devices of the family open
 * rtsp://ipcam:<password>@<this phone>:8554/live (or /low, the same picture) like any IP camera.
 *
 * RTSP 1.0 with the video (H.264, RFC 6184) and the optional sound (AAC, RFC 3640) sent inside the same TCP
 * connection (interleaved, "$" frames); a request for UDP gets 461 so the player asks again for TCP.
 * Password: Digest (realm "ipcam"), Basic also accepted. Several viewers at once; a slow viewer only loses pictures
 * itself (it waits for the next key picture), it never slows the others.
 *
 * Times given to [sendVideo] / [sendAudio] are microseconds of System.nanoTime() / 1000.
 */
class RtspServer(
    private val password: () -> String,
    private val onChange: () -> Unit,
    private val onKeyFrameWanted: () -> Unit,
) {
    /** One device watching, for the list on the camera phone. */
    class Viewer(val ip: String, val since: Long) {
        @Volatile var name = ""
        @Volatile var path = "live"
    }

    @Volatile var port = 0
        private set

    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var running = false
    private val random = SecureRandom()
    private val nonce = hex(16)
    private val videoBase = random.nextInt().toLong() and 0xFFFFFFFFL
    private val audioBase = random.nextInt().toLong() and 0xFFFFFFFFL

    // what the encoders produce (the stream description sent to every viewer)
    private val lock = Object()
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    @Volatile private var audioOn = false
    @Volatile private var audioConfig: ByteArray? = null
    @Volatile private var audioRate = 44100

    // ------------------------------------------------------------------ start / stop

    /** Opens the port (8554, or the next free one; 8555 is kept for talking). Returns the port, 0 if none. */
    fun start(): Int {
        val ports = listOf(8554) + (8556..8574)
        var ss: ServerSocket? = null
        for (p in ports) {
            ss = bind(p)
            if (ss != null) break
        }
        if (ss == null) ss = bind(0)
        if (ss == null) return 0
        server = ss
        port = ss.localPort
        running = true
        val s: ServerSocket = ss
        val t = Thread({ acceptLoop(s) }, "rtsp-accept")
        t.isDaemon = true
        t.start()
        return port
    }

    private fun bind(p: Int): ServerSocket? = try {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(p))
        s
    } catch (_: Exception) {
        null
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        kickAll()
    }

    /** The camera / quality / sound changed: forget the old description and disconnect everyone (they reconnect). */
    fun newStream(withAudio: Boolean) {
        synchronized(lock) {
            sps = null
            pps = null
            audioConfig = null
            audioOn = withAudio
        }
        kickAll()
    }

    /** The sound could not start: describe the stream without it. */
    fun audioOff() {
        synchronized(lock) {
            audioOn = false
            audioConfig = null
            lock.notifyAll()
        }
    }

    fun kickAll() {
        for (c in clients) c.close()
        clients.clear()
        onChange()
    }

    /** The devices watching now (one entry per connection). */
    fun viewers(): List<Viewer> = clients.filter { it.playing && !it.closed }.map { it.viewer }

    // ------------------------------------------------------------------ from the encoders

    fun setVideoConfig(s: ByteArray, p: ByteArray) {
        synchronized(lock) {
            sps = s
            pps = p
            lock.notifyAll()
        }
    }

    fun setAudioConfig(asc: ByteArray, rate: Int) {
        synchronized(lock) {
            audioConfig = asc
            audioRate = rate
            lock.notifyAll()
        }
    }

    /** One encoded picture (Annex B, with start codes). */
    fun sendVideo(frame: ByteArray, ptsUs: Long) {
        if (clients.isEmpty()) return
        val nals = Nal.split(frame)
        if (nals.isEmpty()) return
        var hasSps = false
        var idr = false
        for (n in nals) {
            when (n[0].toInt() and 0x1F) {
                5 -> idr = true
                7 -> hasSps = true
            }
        }
        // every key picture starts with the stream description (SPS / PPS), so a viewer can start there
        val units = ArrayList<ByteArray>(nals.size + 2)
        if (idr && !hasSps) {
            sps?.let { units.add(it) }
            pps?.let { units.add(it) }
        }
        for (n in nals) if ((n[0].toInt() and 0x1F) != 9) units.add(n) // access unit delimiters are not needed

        // cut into RTP payloads once, for all viewers: single NAL unit, or FU-A pieces for the big ones
        val payloads = ArrayList<ByteArray>()
        val marks = ArrayList<Boolean>()
        for ((i, n) in units.withIndex()) {
            val last = i == units.size - 1
            if (n.size <= MTU) {
                payloads.add(n)
                marks.add(last)
                continue
            }
            val head = n[0].toInt()
            val indicator = ((head and 0xE0) or 28).toByte()
            val type = head and 0x1F
            var off = 1
            while (off < n.size) {
                val len = minOf(MTU - 2, n.size - off)
                val first = off == 1
                val end = off + len >= n.size
                val p = ByteArray(len + 2)
                p[0] = indicator
                p[1] = ((if (first) 0x80 else 0) or (if (end) 0x40 else 0) or type).toByte()
                System.arraycopy(n, off, p, 2, len)
                payloads.add(p)
                marks.add(end && last)
                off += len
            }
        }
        val ts = rtpTime(ptsUs, 90_000, videoBase)
        for (c in clients) c.pushVideo(payloads, marks, ts, idr)
    }

    /** One AAC frame (raw, without ADTS header). */
    fun sendAudio(aac: ByteArray, ptsUs: Long) {
        if (clients.isEmpty() || aac.isEmpty() || aac.size > 8191) return
        // RFC 3640, AAC-hbr: AU-headers-length = 16 bits, then one AU header (13 bits size, 3 bits index = 0)
        val p = ByteArray(4 + aac.size)
        p[0] = 0
        p[1] = 16
        p[2] = (aac.size shr 5).toByte()
        p[3] = ((aac.size and 0x1F) shl 3).toByte()
        System.arraycopy(aac, 0, p, 4, aac.size)
        val ts = rtpTime(ptsUs, audioRate, audioBase)
        for (c in clients) c.pushAudio(p, ts)
    }

    private fun rtpTime(us: Long, clock: Int, base: Long): Long = (base + us * clock / 1_000_000L) and 0xFFFFFFFFL

    private fun nowUs(): Long = System.nanoTime() / 1000

    // ------------------------------------------------------------------ viewers

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val s = try { ss.accept() } catch (_: Exception) { break }
            if (!running || clients.size >= MAX_CLIENTS) {
                try { s.close() } catch (_: Exception) {}
                continue
            }
            val c = Client(s)
            clients.add(c)
            c.begin()
        }
    }

    /** The description of the stream (SDP), waiting a little for the encoders when the camera has just started. */
    private fun sdp(localIp: String): Pair<String, Boolean>? {
        val end = System.currentTimeMillis() + 4000
        synchronized(lock) {
            while (true) {
                val ready = sps != null && pps != null && (!audioOn || audioConfig != null)
                val left = end - System.currentTimeMillis()
                if (ready || left <= 0) break
                try { lock.wait(left) } catch (_: InterruptedException) { break }
            }
        }
        val s = sps ?: return null
        val p = pps ?: return null
        if (s.size < 4) return null
        val asc = if (audioOn) audioConfig else null
        val sb = StringBuilder()
        sb.append("v=0\r\n")
        sb.append("o=- ").append(System.currentTimeMillis() / 1000).append(" 1 IN IP4 ").append(localIp).append("\r\n")
        sb.append("s=Home Cameras\r\n")
        sb.append("c=IN IP4 0.0.0.0\r\n")
        sb.append("t=0 0\r\n")
        sb.append("a=tool:ipcam\r\n")
        sb.append("a=type:broadcast\r\n")
        sb.append("a=control:*\r\n")
        sb.append("a=range:npt=0-\r\n")
        sb.append("m=video 0 RTP/AVP 96\r\n")
        sb.append("a=rtpmap:96 H264/90000\r\n")
        sb.append("a=fmtp:96 packetization-mode=1;profile-level-id=")
            .append(String.format(java.util.Locale.US, "%02x%02x%02x", s[1].toInt() and 0xFF, s[2].toInt() and 0xFF, s[3].toInt() and 0xFF))
            .append(";sprop-parameter-sets=")
            .append(Base64.encodeToString(s, Base64.NO_WRAP)).append(',').append(Base64.encodeToString(p, Base64.NO_WRAP))
            .append("\r\n")
        sb.append("a=control:trackID=0\r\n")
        if (asc != null) {
            val rate = audioRate
            sb.append("m=audio 0 RTP/AVP 97\r\n")
            sb.append("a=rtpmap:97 mpeg4-generic/").append(rate).append("/1\r\n")
            sb.append("a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=")
                .append(asc.joinToString("") { String.format(java.util.Locale.US, "%02X", it.toInt() and 0xFF) })
                .append("\r\n")
            sb.append("a=control:trackID=1\r\n")
        }
        return Pair(sb.toString(), asc != null)
    }

    private class Track(val channel: Int, val uri: String, val ssrc: Int, var seq: Int)

    private class Request(val method: String, val uri: String, val headers: Map<String, String>)

    private inner class Client(private val socket: Socket) {
        val viewer = Viewer(socket.inetAddress?.hostAddress ?: "", System.currentTimeMillis())
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val pending = AtomicLong(0)
        @Volatile var closed = false
        @Volatile var playing = false
        @Volatile private var video: Track? = null
        @Volatile private var audio: Track? = null
        @Volatile private var needKey = true
        @Volatile private var slowSince = 0L
        private var authed = false
        private var badTries = 0
        private var describedAudio: Boolean? = null
        private val session = hex(4).uppercase()

        fun begin() {
            val r = Thread({ readLoop() }, "rtsp-in")
            r.isDaemon = true
            r.start()
            val w = Thread({ writeLoop() }, "rtsp-out")
            w.isDaemon = true
            w.start()
            // a friendly name for the list (the router often knows the device's name)
            val n = Thread({
                try {
                    val h = socket.inetAddress?.canonicalHostName ?: ""
                    if (h.isNotEmpty() && h != viewer.ip && !h[0].isDigit()) {
                        viewer.name = h.substringBefore('.')
                        if (playing) onChange()
                    }
                } catch (_: Exception) {
                }
            }, "rtsp-name")
            n.isDaemon = true
            n.start()
        }

        fun close() {
            if (closed) return
            closed = true
            val wasPlaying = playing
            playing = false
            try { socket.close() } catch (_: Exception) {}
            queue.clear()
            queue.offer(WAKE)
            clients.remove(this)
            if (wasPlaying) onChange()
        }

        // ---- sending

        private fun enqueue(b: ByteArray) {
            if (closed) return
            pending.addAndGet(b.size.toLong())
            queue.offer(b)
        }

        private fun writeLoop() {
            try {
                val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
                while (!closed) {
                    val b = queue.poll(1, TimeUnit.SECONDS)
                    if (b == null) {
                        out.flush()
                        continue
                    }
                    if (b === WAKE) continue
                    if (b === BYE) {
                        out.flush()
                        break
                    }
                    out.write(b)
                    pending.addAndGet(-b.size.toLong())
                    if (queue.isEmpty()) out.flush()
                }
            } catch (_: Exception) {
            }
            close()
        }

        private fun packet(tr: Track, type: Int, marker: Boolean, ts: Long, payload: ByteArray): ByteArray {
            val len = 12 + payload.size
            val b = ByteArray(4 + len)
            b[0] = 0x24 // '$'
            b[1] = tr.channel.toByte()
            b[2] = (len shr 8).toByte()
            b[3] = len.toByte()
            b[4] = 0x80.toByte() // RTP version 2
            b[5] = ((if (marker) 0x80 else 0) or type).toByte()
            val seq = tr.seq
            tr.seq = (seq + 1) and 0xFFFF
            b[6] = (seq shr 8).toByte()
            b[7] = seq.toByte()
            b[8] = (ts shr 24).toByte()
            b[9] = (ts shr 16).toByte()
            b[10] = (ts shr 8).toByte()
            b[11] = ts.toByte()
            b[12] = (tr.ssrc shr 24).toByte()
            b[13] = (tr.ssrc shr 16).toByte()
            b[14] = (tr.ssrc shr 8).toByte()
            b[15] = tr.ssrc.toByte()
            System.arraycopy(payload, 0, b, 16, payload.size)
            return b
        }

        /** Called on the video encoder's thread. */
        fun pushVideo(payloads: List<ByteArray>, marks: List<Boolean>, ts: Long, key: Boolean) {
            val tr = video ?: return
            if (!playing || closed) return
            if (needKey) {
                if (!key) return
                needKey = false
            }
            var size = 0L
            for (p in payloads) size += p.size + 16
            if (pending.get() + size > MAX_PENDING) {
                // this viewer cannot keep up: skip pictures until the next key picture
                needKey = true
                val now = System.currentTimeMillis()
                if (slowSince == 0L) slowSince = now
                else if (now - slowSince > 15_000) close() // gone without saying goodbye
                return
            }
            slowSince = 0L
            for (i in payloads.indices) enqueue(packet(tr, 96, marks[i], ts, payloads[i]))
        }

        /** Called on the sound encoder's thread. */
        fun pushAudio(payload: ByteArray, ts: Long) {
            val tr = audio ?: return
            if (!playing || closed) return
            if (video != null && needKey) return // sound starts together with the picture
            if (pending.get() + payload.size > MAX_PENDING) return
            enqueue(packet(tr, 97, true, ts, payload))
        }

        // ---- requests

        private fun readLoop() {
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                val input = BufferedInputStream(socket.getInputStream(), 8192)
                while (!closed) {
                    val req = readRequest(input) ?: break
                    handle(req)
                }
            } catch (_: Exception) {
            }
            close()
        }

        private fun readLine(input: InputStream, first: Int): String {
            val sb = StringBuilder()
            var c = first
            while (c >= 0 && c != '\n'.code) {
                if (c != '\r'.code) sb.append(c.toChar())
                if (sb.length > 8192) throw IOException("line too long")
                c = input.read()
            }
            if (c < 0) throw IOException("closed")
            return sb.toString()
        }

        private fun skip(input: InputStream, n: Int) {
            var left = n
            val tmp = ByteArray(minOf(n, 4096).coerceAtLeast(1))
            while (left > 0) {
                val r = input.read(tmp, 0, minOf(left, tmp.size))
                if (r < 0) throw IOException("closed")
                left -= r
            }
        }

        private fun readRequest(input: InputStream): Request? {
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == 0x24) {
                    // data from the player inside the connection (RTCP reports): not needed, skipped
                    input.read()
                    val hi = input.read()
                    val lo = input.read()
                    if (lo < 0) return null
                    skip(input, (hi shl 8) or lo)
                    continue
                }
                if (b == '\r'.code || b == '\n'.code) continue
                val line = readLine(input, b)
                val headers = HashMap<String, String>()
                var count = 0
                while (true) {
                    val h = readLine(input, input.read())
                    if (h.isEmpty()) break
                    if (++count > 64) throw IOException("too many headers")
                    val i = h.indexOf(':')
                    if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                }
                val body = headers["content-length"]?.toIntOrNull() ?: 0
                if (body > 0) skip(input, minOf(body, 65_536))
                val parts = line.split(' ').filter { it.isNotEmpty() }
                return Request(parts.getOrElse(0) { "" }.uppercase(), parts.getOrElse(1) { "" }, headers)
            }
        }

        private fun handle(req: Request) {
            val cseq = req.headers["cseq"] ?: "0"
            when (req.method) {
                "OPTIONS" -> reply(200, cseq, listOf("Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER"))
                "DESCRIBE" -> describe(req, cseq)
                "SETUP" -> setup(req, cseq)
                "PLAY" -> play(req, cseq)
                "PAUSE" -> {
                    playing = false
                    reply(200, cseq, listOf("Session: $session"))
                    onChange()
                }
                "TEARDOWN" -> {
                    playing = false
                    reply(200, cseq, listOf("Session: $session"))
                    queue.offer(BYE)
                }
                "GET_PARAMETER", "SET_PARAMETER" -> reply(200, cseq, listOf("Session: $session"))
                else -> reply(501, cseq)
            }
        }

        private fun describe(req: Request, cseq: String) {
            val path = path(req.uri) ?: return reply(404, cseq)
            if (!authOk(req)) return challenge(cseq)
            viewer.path = path
            val local = socket.localAddress?.hostAddress ?: "0.0.0.0"
            val (text, withAudio) = sdp(local) ?: return reply(503, cseq)
            describedAudio = withAudio
            reply(200, cseq, listOf("Content-Base: ${base(req.uri)}/", "Content-Type: application/sdp"), text)
        }

        private fun setup(req: Request, cseq: String) {
            val path = path(req.uri) ?: return reply(404, cseq)
            if (!authOk(req)) return challenge(cseq)
            viewer.path = path
            val transport = req.headers["transport"] ?: ""
            if (!transport.contains("RTP/AVP/TCP", ignoreCase = true)) return reply(461, cseq) // the player then asks for TCP
            val isAudio = req.uri.trimEnd('/').endsWith("trackID=1", ignoreCase = true)
            if (isAudio && (describedAudio ?: (audioConfig != null)) != true) return reply(404, cseq)
            val m = Regex("interleaved=(\\d+)").find(transport)
            val ch = m?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 254) ?: if (isAudio) 2 else 0
            val tr = Track(ch, clean(req.uri), random.nextInt(), random.nextInt(0x8000))
            if (isAudio) audio = tr else video = tr
            reply(200, cseq, listOf(
                "Transport: RTP/AVP/TCP;unicast;interleaved=$ch-${ch + 1};ssrc=" + String.format(java.util.Locale.US, "%08X", tr.ssrc),
                "Session: $session;timeout=60",
            ))
        }

        private fun play(req: Request, cseq: String) {
            if (!authOk(req)) return challenge(cseq)
            val v = video
            val a = audio
            if (v == null && a == null) return reply(455, cseq)
            if (playing) return reply(200, cseq, listOf("Session: $session", "Range: npt=0.000-"))
            needKey = v != null
            // where each track starts (sequence number of the first packet, and the clock now)
            val now = nowUs()
            val info = ArrayList<String>()
            if (v != null) info.add("url=${v.uri};seq=${v.seq};rtptime=${rtpTime(now, 90_000, videoBase)}")
            if (a != null) info.add("url=${a.uri};seq=${a.seq};rtptime=${rtpTime(now, audioRate, audioBase)}")
            reply(200, cseq, listOf("Session: $session", "Range: npt=0.000-", "RTP-Info: " + info.joinToString(",")))
            playing = true
            onChange()
            if (v != null) onKeyFrameWanted() // the picture appears at once instead of after up to a second
        }

        // ---- password

        private fun authOk(req: Request): Boolean {
            if (authed) return true
            val pass = password()
            val h = req.headers["authorization"] ?: return false
            val ok = try {
                when {
                    h.startsWith("Basic ", ignoreCase = true) -> {
                        val s = String(Base64.decode(h.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8)
                        same(s.substringAfter(':', ""), pass)
                    }
                    h.startsWith("Digest ", ignoreCase = true) -> digestOk(req, h.substring(7), pass)
                    else -> false
                }
            } catch (_: Exception) {
                false
            }
            if (ok) {
                authed = true
            } else {
                // a wrong password: answer slowly, so guessing takes very long
                badTries++
                try { Thread.sleep(minOf(5000L, 1000L * badTries)) } catch (_: InterruptedException) {}
            }
            return ok
        }

        private fun digestOk(req: Request, params: String, pass: String): Boolean {
            val p = HashMap<String, String>()
            for (m in Regex("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))").findAll(params)) {
                p[m.groupValues[1].lowercase()] = m.groupValues[2].ifEmpty { m.groupValues[3] }
            }
            if (p["nonce"] != nonce) return false
            val user = p["username"] ?: ""
            val uri = p["uri"] ?: req.uri
            val ha1 = md5("$user:$REALM:$pass")
            val ha2 = md5("${req.method}:$uri")
            val qop = p["qop"]
            val expected = if (qop.isNullOrEmpty()) md5("$ha1:$nonce:$ha2")
            else md5("$ha1:$nonce:${p["nc"] ?: ""}:${p["cnonce"] ?: ""}:$qop:$ha2")
            return same(expected, (p["response"] ?: "").lowercase())
        }

        private fun challenge(cseq: String) = reply(401, cseq, listOf("WWW-Authenticate: Digest realm=\"$REALM\", nonce=\"$nonce\""))

        private fun reply(code: Int, cseq: String, headers: List<String> = emptyList(), body: String = "") {
            val b = body.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder()
            sb.append("RTSP/1.0 ").append(code).append(' ').append(reason(code)).append("\r\n")
            sb.append("CSeq: ").append(cseq).append("\r\n")
            sb.append("Server: ipcam\r\n")
            for (h in headers) sb.append(h).append("\r\n")
            if (b.isNotEmpty()) sb.append("Content-Length: ").append(b.size).append("\r\n")
            sb.append("\r\n")
            val head = sb.toString().toByteArray(Charsets.UTF_8)
            enqueue(if (b.isEmpty()) head else head + b)
        }
    }

    companion object {
        private const val REALM = "ipcam"
        private const val MTU = 1400
        private const val MAX_CLIENTS = 12
        private const val MAX_PENDING = 1_500_000L // bytes waiting for one viewer (about 4 seconds of video)
        private val WAKE = ByteArray(0)
        private val BYE = ByteArray(0)

        private fun reason(code: Int) = when (code) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            455 -> "Method Not Valid in This State"
            461 -> "Unsupported Transport"
            501 -> "Not Implemented"
            503 -> "Service Unavailable"
            else -> "Error"
        }

        /** The link without the user and password ("rtsp://ipcam:1234@host:8554/live" → "rtsp://host:8554/live"). */
        private fun clean(uri: String): String {
            val i = uri.indexOf("://")
            if (i < 0) return uri
            val rest = uri.substring(i + 3)
            val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
            val at = rest.lastIndexOf('@', slash - 1)
            return if (at >= 0) uri.substring(0, i + 3) + rest.substring(at + 1) else uri
        }

        /** The session link given back to the player: without password, query or trailing "/". */
        private fun base(uri: String): String = clean(uri).substringBefore('?').trimEnd('/')

        /** "live" or "low" for the paths we serve (also with "/trackID=n"), null for anything else. */
        private fun path(uri: String): String? {
            var p = clean(uri)
            val i = p.indexOf("://")
            if (i >= 0) {
                val rest = p.substring(i + 3)
                val slash = rest.indexOf('/')
                p = if (slash < 0) "" else rest.substring(slash)
            }
            p = p.substringBefore('?').trimEnd('/').lowercase()
            p = p.replace(Regex("/?trackid=\\d+$"), "").trimEnd('/')
            return when (p) {
                "", "/live" -> "live"
                "/low" -> "low"
                else -> null
            }
        }

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { String.format(java.util.Locale.US, "%02x", it.toInt() and 0xFF) }

        /** Compares without leaking how many characters matched. */
        fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

        private fun hex(bytes: Int): String {
            val b = ByteArray(bytes)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { String.format(java.util.Locale.US, "%02x", it.toInt() and 0xFF) }
        }
    }
}

/** H.264 helpers: cut an Annex-B buffer (00 00 01 / 00 00 00 01 start codes) into NAL units. */
object Nal {
    fun split(b: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val n = b.size
        var start = -1
        var i = 0
        while (i + 2 < n) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                if (start >= 0) add(out, b, start, i)
                i += 3
                start = i
            } else {
                i++
            }
        }
        if (start >= 0) add(out, b, start, n)
        else if (n > 0) out.add(b.copyOf()) // no start code: the whole buffer is one unit
        return out
    }

    private fun add(out: MutableList<ByteArray>, b: ByteArray, from: Int, to: Int) {
        var end = to
        while (end > from && b[end - 1].toInt() == 0) end-- // zeros before the next start code
        if (end > from) out.add(b.copyOfRange(from, end))
    }

    fun type(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F
}
