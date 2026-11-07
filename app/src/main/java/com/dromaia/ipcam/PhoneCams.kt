package com.dromaia.ipcam

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Phones running the camera mode announce themselves on the home Wi-Fi (NSD / Bonjour, "_ipcamcam._tcp") with their
 * name and RTSP port, so «بحث في الشبكة» on the other devices finds them without typing an address.
 */
object PhoneCams {
    const val TYPE = "_ipcamcam._tcp"

    private val lock = Any()
    private var reg: NsdManager.RegistrationListener? = null

    /** Camera mode on: announce this phone (called by CameraService). */
    fun register(c: Context, name: String, port: Int) {
        unregister(c)
        try {
            val nsd = c.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo()
            info.serviceName = name.ifBlank { L.t(c, "جوال كاميرا", "Phone camera") }.take(40)
            info.serviceType = TYPE
            info.port = port
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            synchronized(lock) { reg = l }
        } catch (_: Exception) {
        }
    }

    /** Camera mode off: stop announcing. */
    fun unregister(c: Context) {
        val l = synchronized(lock) { reg.also { reg = null } } ?: return
        try {
            (c.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l)
        } catch (_: Exception) {
        }
    }

    /**
     * Looks for phones in camera mode for [waitMs] and calls [onFound] (on this thread) for each one: its name,
     * address and RTSP port. Blocking: call it off the main thread. Services are resolved one at a time
     * (older Android versions fail when several are resolved at once).
     */
    fun discover(c: Context, waitMs: Long, onFound: (name: String, host: String, port: Int) -> Unit) {
        val nsd = try {
            c.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        } catch (_: Exception) {
            return
        }
        val queue = LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                queue.offer(info)
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
        // this phone itself, when it is in camera mode, is not shown as a camera to itself
        val self = if (CameraService.running) localIp() else null
        val seen = HashSet<String>()
        val deadline = SystemClock.elapsedRealtime() + waitMs
        try {
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                val s = try { queue.poll(left, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: break
                if (!seen.add(s.serviceName ?: "")) continue
                val r = resolve(nsd, s, left.coerceIn(1500L, 3000L)) ?: continue
                val addr = address(r) ?: continue
                val host = addr.hostAddress ?: continue
                if (host == self) continue
                if (r.port <= 0) continue
                try {
                    onFound(r.serviceName ?: s.serviceName ?: host, host, r.port)
                } catch (_: Exception) {
                }
            }
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun address(r: NsdServiceInfo): InetAddress? =
        if (Build.VERSION.SDK_INT >= 34) r.hostAddresses.firstOrNull { it is Inet4Address } ?: r.hostAddresses.firstOrNull()
        else r.host

    /** Resolves one service (address and port); waits for the answer. Retries when Android is busy with another one. */
    @Suppress("DEPRECATION")
    private fun resolve(nsd: NsdManager, s: NsdServiceInfo, timeoutMs: Long): NsdServiceInfo? {
        repeat(3) {
            val latch = CountDownLatch(1)
            var out: NsdServiceInfo? = null
            var busy = false
            val l = object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                    busy = e == NsdManager.FAILURE_ALREADY_ACTIVE
                    latch.countDown()
                }

                override fun onServiceResolved(i: NsdServiceInfo) {
                    out = i
                    latch.countDown()
                }
            }
            try {
                nsd.resolveService(s, l)
            } catch (_: Exception) {
                return null
            }
            val answered = try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
            if (!answered) {
                if (Build.VERSION.SDK_INT >= 34) {
                    try { nsd.stopServiceResolution(l) } catch (_: Exception) {}
                }
                return null
            }
            out?.let { return it }
            if (!busy) return null
            try { Thread.sleep(300) } catch (_: InterruptedException) { return null }
        }
        return null
    }

    /** This device's address on the home Wi-Fi (or cable), e.g. "192.168.1.23"; null when not connected. */
    fun localIp(): String? {
        var best: String? = null
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in all.toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                val n = ni.name ?: ""
                // mobile data and VPN are not the home network
                if (listOf("rmnet", "ccmni", "pdp", "v4-", "tun", "ppp", "dummy").any { n.startsWith(it) }) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a !is Inet4Address || !a.isSiteLocalAddress) continue
                    val h = a.hostAddress ?: continue
                    if (n.startsWith("wlan") || n.startsWith("eth")) return h
                    if (best == null) best = h
                }
            }
        } catch (_: Exception) {
        }
        return best
    }
}
