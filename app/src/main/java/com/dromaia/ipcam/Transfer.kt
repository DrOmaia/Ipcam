package com.dromaia.ipcam

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

// Moving cameras from one device of the family to another on the same Wi-Fi (phone → TV, phone → phone):
// the receiving device opens «استقبال» and shows a 4-digit code; it announces itself on the network (NSD) only while
// that screen is open. The sending device finds it, the owner types the code, and the chosen cameras (with their
// passwords) go straight to that device. Nothing passes through the internet.
//
// How the passwords are protected (standard Java crypto only):
//  1. Each side makes a new key pair (EC P-256) and they agree on a shared secret (ECDH) that nobody listening can know.
//  2. The code is checked one bit at a time, so that it can never be worked out from what is sent: for each bit the
//     sender first sends a sealed promise (a hash of the bit with a random salt), then the receiver shows its bit,
//     the sender checks it and only then opens its promise. A device that only pretends to be the receiver (or sits in
//     the middle) is caught at its first wrong bit, before anything secret is sent.
//  3. A wrong try makes the receiver show a new code (5 wrong tries: it stops listening). Each code is used once.
//  4. Only then are the cameras sent, encrypted with AES-GCM; the key comes from the shared secret and the code.
object Transfer {
    private const val TYPE = "_ipcamshare._tcp"
    private const val HELLO = "ipcam2 "     // first words of a sender of this version
    private const val BITS = 14             // a code 1000–9999 fits in 14 bits
    private const val SHORT_LINE = 1_000    // every message but the cameras
    private const val LONG_LINE = 2_000_000 // the cameras (one line); longer = not ours, the connection is closed
    private const val TRIES = 5
    private val random = SecureRandom()

    class Peer(val name: String, val host: String, val port: Int)

    /** A new random 4-digit code for the receiving screen. */
    fun newCode(): String = (1000 + random.nextInt(9000)).toString()

    // ---------- receiving side
    class Receiver(private val ctx: Context, code: String, private val onCameras: (from: String, count: Int) -> Unit) {
        /** The code shown on the screen. It changes after a wrong try and after cameras arrive (see [onCode]). */
        @Volatile var code: String = code
            private set

        /** Called on a background thread with the new code; wrong = after a wrong try. Also check [isStopped]. */
        @Volatile var onCode: ((code: String, wrong: Boolean) -> Unit)? = null

        private var server: ServerSocket? = null
        private var reg: NsdManager.RegistrationListener? = null
        private val stopped = AtomicBoolean(false)
        private val wrong = AtomicInteger(0)
        private val busy = AtomicInteger(0) // connections being answered (at most 2 at a time)
        @Volatile private var round = 0    // goes up with every new code: a try begun with the old code is dropped
        val isStopped: Boolean get() = stopped.get()

        fun start(): Boolean {
            val ss = try { ServerSocket(0) } catch (_: Exception) { return false }
            server = ss
            thread(isDaemon = true) {
                while (!stopped.get()) {
                    val c = try { ss.accept() } catch (_: Exception) { break }
                    if (busy.incrementAndGet() > 2) {
                        busy.decrementAndGet()
                        try { c.close() } catch (_: Exception) {}
                        continue
                    }
                    thread(isDaemon = true) {
                        try {
                            handle(c)
                        } finally {
                            busy.decrementAndGet()
                        }
                    }
                }
            }
            try {
                val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
                val info = NsdServiceInfo()
                info.serviceName = Device.name(ctx).take(40)
                info.serviceType = TYPE
                info.port = ss.localPort
                val l = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(i: NsdServiceInfo) {}
                    override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                    override fun onServiceUnregistered(i: NsdServiceInfo) {}
                    override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
                }
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
                reg = l
            } catch (_: Exception) {
            }
            return true
        }

        private fun handle(s: Socket) {
            val myRound = round
            val myCode = code
            var shown = false // this try has seen at least one bit of the code
            var done = false
            try {
                s.use { sock ->
                    sock.tcpNoDelay = true
                    val end = System.currentTimeMillis() + 60_000
                    val ins = BufferedInputStream(sock.getInputStream())
                    val out = sock.getOutputStream()
                    // every read waits 10 s at most, the whole exchange 60 s at most
                    fun read(max: Int): String? {
                        val left = end - System.currentTimeMillis()
                        if (left <= 0) return null
                        sock.soTimeout = minOf(left, 10_000L).toInt()
                        return readLine(ins, max)
                    }
                    fun stale() = myRound != round || stopped.get()

                    val hello = read(SHORT_LINE) ?: return
                    if (!hello.startsWith(HELLO)) return // an older version of the app, or not ours
                    val pubS = hello.substring(HELLO.length)
                    val theirs = publicKey(pubS)
                    val keys = keyPair()
                    val pubR = b64(keys.public.encoded)
                    write(out, pubR)
                    val n = myCode.toInt()
                    for (i in 0 until BITS) {
                        val sealed = read(SHORT_LINE) ?: return
                        if (stale()) { shown = false; return } // the code changed meanwhile: not counted
                        write(out, b64(showBit(i, pubS, pubR, sealed, bit(n, i))))
                        shown = true
                        val salt = read(SHORT_LINE) ?: return
                        if (!same(unb64(sealed), sealBit(i, pubS, pubR, bit(n, i), salt))) {
                            write(out, "bad")
                            return
                        }
                    }
                    val line = read(LONG_LINE) ?: return
                    val json = decrypt(line, sessionKey(keys.private, theirs, myCode, pubS, pubR)) ?: return
                    if (stale()) { shown = false; return }
                    val o = JSONObject(json)
                    val arr = o.optJSONArray("cams") ?: JSONArray()
                    val cams = (0 until arr.length()).map { k -> Camera.fromJson(arr.getJSONObject(k)) }
                    val count = Store.merge(ctx, cams)
                    write(out, "ok")
                    done = true
                    onCameras(o.optString("from"), count)
                }
            } catch (_: Exception) {
            } finally {
                if (done) renew(myRound, false) else if (shown) wrongTry(myRound)
            }
        }

        // someone tried with a wrong code (or left in the middle after seeing part of it)
        private fun wrongTry(from: Int) {
            // guessing the code: after 5 wrong tries the receiving screen stops listening
            if (wrong.incrementAndGet() >= TRIES) {
                stop()
                onCode?.invoke(code, true)
            } else {
                renew(from, true)
            }
        }

        // a new code on the screen (once: if another try already changed it, nothing to do)
        private fun renew(from: Int, afterWrong: Boolean) {
            val fresh = synchronized(this) {
                if (from != round || stopped.get()) return
                var c = newCode()
                while (c == code) c = newCode()
                code = c
                round = from + 1
                c
            }
            onCode?.invoke(fresh, afterWrong)
        }

        fun stop() {
            stopped.set(true)
            try { server?.close() } catch (_: Exception) {}
            server = null
            reg?.let { l ->
                try { (ctx.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) } catch (_: Exception) {}
            }
            reg = null
        }
    }

    // ---------- sending side
    /** Looks for receiving devices for [waitMs]; calls [onPeer] for each one found (on a background thread). Blocking. */
    fun discover(ctx: Context, waitMs: Long, onPeer: (Peer) -> Unit) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
        val busy = AtomicBoolean(false)
        val seen = HashSet<String>()

        fun next() {
            if (!busy.compareAndSet(false, true)) return
            val info = queue.poll()
            if (info == null) {
                busy.set(false)
                return
            }
            try {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                        busy.set(false)
                        next()
                    }

                    override fun onServiceResolved(i: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val h = i.host?.hostAddress
                        if (h != null) {
                            val key = "$h:${i.port}"
                            val fresh = synchronized(seen) { seen.add(key) }
                            if (fresh) onPeer(Peer(i.serviceName, h, i.port))
                        }
                        busy.set(false)
                        next()
                    }
                })
            } catch (_: Exception) {
                busy.set(false)
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                queue.add(info)
                next()
            }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            return
        }
        try { Thread.sleep(waitMs) } catch (_: InterruptedException) {}
        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
    }

    /** Sends the cameras; "ok", "bad" (wrong code, or not the right device) or "fail". Blocking. */
    fun send(ctx: Context, peer: Peer, code: String, cams: List<Camera>): String {
        val typed = code.trim()
        if (typed.length != 4 || typed.any { it !in '0'..'9' }) return "bad"
        val n = typed.toInt()
        val arr = JSONArray()
        cams.forEach { arr.put(it.toJson()) }
        val payload = JSONObject().put("from", Device.name(ctx)).put("cams", arr).toString()
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(peer.host, peer.port), 4000)
                s.soTimeout = 10_000
                s.tcpNoDelay = true
                val ins = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val keys = keyPair()
                val pubS = b64(keys.public.encoded)
                write(out, HELLO + pubS)
                val pubR = readLine(ins, SHORT_LINE) ?: return "fail"
                val theirs = publicKey(pubR)
                for (i in 0 until BITS) {
                    val salt = b64(ByteArray(16).also { random.nextBytes(it) })
                    val sealed = b64(sealBit(i, pubS, pubR, bit(n, i), salt))
                    write(out, sealed)
                    val shown = readLine(ins, SHORT_LINE) ?: return "fail"
                    // the other device does not know this code: stop here, nothing secret was sent
                    if (!same(unb64(shown), showBit(i, pubS, pubR, sealed, bit(n, i)))) return "bad"
                    write(out, salt)
                }
                write(out, encrypt(payload, sessionKey(keys.private, theirs, typed, pubS, pubR)))
                when (readLine(ins, SHORT_LINE)) {
                    "ok" -> "ok"
                    "bad" -> "bad"
                    else -> "fail"
                }
            }
        } catch (_: Exception) {
            "fail"
        }
    }

    // ---------- the crypto (standard Java / Android only)
    private fun keyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), random)
        return g.generateKeyPair()
    }

    private fun publicKey(b64: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(b64)))

    private fun sha(vararg parts: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) {
            md.update(p.toByteArray(Charsets.UTF_8))
            md.update(0.toByte())
        }
        return md.digest()
    }

    private fun bit(code: Int, i: Int) = (code shr i) and 1

    // the sender's sealed promise for bit i (the random salt hides the bit until it is opened)
    private fun sealBit(i: Int, pubS: String, pubR: String, bit: Int, salt: String) =
        sha("ipcam-S", i.toString(), pubS, pubR, bit.toString(), salt)

    // the receiver shows its bit i, tied to this connection and to the sender's promise
    private fun showBit(i: Int, pubS: String, pubR: String, sealed: String, bit: Int) =
        sha("ipcam-R", i.toString(), pubS, pubR, sealed, bit.toString())

    // compares in the same time whatever the bytes (gives nothing away)
    private fun same(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var d = 0
        for (k in a.indices) d = d or (a[k].toInt() xor b[k].toInt())
        return d == 0
    }

    // AES key = HMAC-SHA256(ECDH secret, code + both public keys)
    private fun sessionKey(mine: PrivateKey, theirs: PublicKey, code: String, pubS: String, pubR: String): SecretKeySpec {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(mine)
        ka.doPhase(theirs, true)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ka.generateSecret(), "HmacSHA256"))
        val key = mac.doFinal("ipcam-key\u0000$code\u0000$pubS\u0000$pubR".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(key, "AES")
    }

    // "iv data" in base64: AES/GCM/NoPadding with a random 12-byte IV
    private fun encrypt(text: String, key: SecretKeySpec): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return b64(iv) + " " + b64(c.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    private fun decrypt(line: String, key: SecretKeySpec): String? {
        val parts = line.split(' ')
        if (parts.size != 2) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, unb64(parts[0])))
            String(c.doFinal(unb64(parts[1])), Charsets.UTF_8)
        } catch (_: Exception) {
            null // wrong key or changed on the way
        }
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    private fun write(out: OutputStream, line: String) {
        out.write((line + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** One line without its end; null at the end of the stream, or when the line is longer than [max] bytes. */
    private fun readLine(ins: InputStream, max: Int): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = ins.read()
            if (b < 0) return null
            if (b == '\n'.code) return String(buf.toByteArray(), Charsets.UTF_8).trimEnd('\r')
            if (buf.size() >= max) return null
            buf.write(b)
        }
    }
}
