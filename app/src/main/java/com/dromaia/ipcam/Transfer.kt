package com.dromaia.ipcam

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// Moving cameras from one device of the family to another on the same Wi-Fi (phone → TV, phone → phone):
// the receiving device opens «استقبال» and shows a 4-digit code; it announces itself on the network (NSD) only while
// that screen is open. The sending device finds it, the owner types the code, and the chosen cameras (with their
// passwords) go straight to that device. Nothing passes through the internet.
object Transfer {
    private const val TYPE = "_ipcamshare._tcp"

    class Peer(val name: String, val host: String, val port: Int)

    // ---------- receiving side
    class Receiver(private val ctx: Context, val code: String, private val onCameras: (from: String, count: Int) -> Unit) {
        private var server: ServerSocket? = null
        private var reg: NsdManager.RegistrationListener? = null
        private val stopped = AtomicBoolean(false)
        private val wrong = java.util.concurrent.atomic.AtomicInteger(0)
        val isStopped: Boolean get() = stopped.get()

        fun start(): Boolean {
            val ss = try { ServerSocket(0) } catch (_: Exception) { return false }
            server = ss
            thread(isDaemon = true) {
                while (!stopped.get()) {
                    val c = try { ss.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) { handle(c) }
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
            try {
                s.use {
                    it.soTimeout = 10_000
                    val line = it.getInputStream().bufferedReader(Charsets.UTF_8).readLine()?.take(2_000_000) ?: return
                    val o = JSONObject(line)
                    val out = it.getOutputStream()
                    if (o.optString("code") != code) {
                        out.write("bad\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                        // guessing the code: after 5 wrong tries the receiving screen stops listening
                        if (wrong.incrementAndGet() >= 5) stop()
                        return
                    }
                    val arr = o.optJSONArray("cams") ?: JSONArray()
                    val cams = (0 until arr.length()).map { i -> Camera.fromJson(arr.getJSONObject(i)) }
                    val n = Store.merge(ctx, cams)
                    out.write("ok\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                    onCameras(o.optString("from"), n)
                }
            } catch (_: Exception) {
            }
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

    /** Sends the cameras; "ok", "bad" (wrong code) or "fail". Blocking. */
    fun send(ctx: Context, peer: Peer, code: String, cams: List<Camera>): String = try {
        val arr = JSONArray()
        cams.forEach { arr.put(it.toJson()) }
        val payload = JSONObject().put("code", code).put("from", Device.name(ctx)).put("cams", arr).toString()
        Socket().use { s ->
            s.connect(InetSocketAddress(peer.host, peer.port), 4000)
            s.soTimeout = 10_000
            s.getOutputStream().write((payload + "\n").toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
            when (s.getInputStream().bufferedReader(Charsets.UTF_8).readLine()) {
                "ok" -> "ok"
                "bad" -> "bad"
                else -> "fail"
            }
        }
    } catch (_: Exception) {
        "fail"
    }
}
