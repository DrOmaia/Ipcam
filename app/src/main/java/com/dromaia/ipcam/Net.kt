package com.dromaia.ipcam

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Follows the device's network (Wi-Fi left, Wi-Fi back, another Wi-Fi). When it changes, everything that had given
 *  up or was waiting starts again by itself: the videos on screen, the status dots, the motion alerts and the live
 *  widget. What was learnt on the old network (which address worked, a refused password) is forgotten. */
object Net {
    /** Grows by one at every change of network: a waiting loop that sees it change stops waiting. */
    @Volatile
    var epoch = 0
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Runnable>()
    private var last: String? = null // this device's address on the network before the change ("" = none)
    private var started = false

    private val settle = Runnable {
        val now = try {
            Discovery.localIPv4()?.hostAddress ?: ""
        } catch (_: Throwable) {
            ""
        }
        if (now != last) {
            val first = last == null
            last = now
            if (!first) changed()
        }
    }

    /** Called once from [App]. */
    fun start(c: Context) {
        if (started) return
        started = true
        last = try {
            Discovery.localIPv4()?.hostAddress ?: ""
        } catch (_: Throwable) {
            ""
        }
        try {
            val cm = c.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = soon()
                override fun onLost(network: Network) = soon()
                override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = soon()
            })
        } catch (_: Throwable) {
            // without it the app works as before (retries every few seconds)
        }
    }

    /** [r] runs on the main thread after every change of network, until [remove]. */
    fun add(r: Runnable) {
        if (!listeners.contains(r)) listeners.add(r)
    }

    fun remove(r: Runnable) {
        listeners.remove(r)
    }

    // the network takes a moment to settle (address, routes): one check two seconds after the last sign
    private fun soon() {
        main.removeCallbacks(settle)
        main.postDelayed(settle, 2000)
    }

    private fun changed() {
        epoch++
        try {
            Route.reset()
            Hik.forgetRefused()
            Onvif.forgetRefused()
            Snap.forgetRefused()
        } catch (_: Throwable) {
        }
        for (r in listeners) {
            try {
                r.run()
            } catch (_: Throwable) {
            }
        }
    }
}
