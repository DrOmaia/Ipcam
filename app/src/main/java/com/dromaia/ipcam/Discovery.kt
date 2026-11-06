package com.dromaia.ipcam

import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A device found on the home network. */
class Found(
    val ip: String,
    var brand: Brand? = null,
    var model: String = "",
    var onvif: Boolean = false,
    var rtsp: Boolean = false,
) {
    fun merge(o: Found) {
        if (brand == null) brand = o.brand
        if (model.isEmpty()) model = o.model
        onvif = onvif || o.onvif
        rtsp = rtsp || o.rtsp
    }

    fun lastPart(): Int = ip.substringAfterLast('.').toIntOrNull() ?: 0
}

// Finding cameras on the home Wi-Fi in two ways at once:
//  1. ONVIF: one "who is a camera?" message to the whole network; cameras answer with their make and model.
//  2. RTSP: knock on port 554 (the video port) of every address 1–254; also finds cameras with ONVIF switched off.
object Discovery {

    /** This device's own address on the home network (Wi-Fi or cable), or null. */
    fun localIPv4(): Inet4Address? {
        var best: Inet4Address? = null
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in all.toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a !is Inet4Address || !a.isSiteLocalAddress) continue
                    if (ni.name.startsWith("wlan") || ni.name.startsWith("eth")) return a
                    if (best == null) best = a
                }
            }
        } catch (_: Exception) {
        }
        return best
    }

    fun guess(text: String): Brand? {
        val t = text.lowercase()
        return when {
            listOf("hikvision", "hilook", "hik-", "hikv", "ds-2c", "ds-7", "ds-2d").any { t.contains(it) } -> Brand.HIKVISION
            listOf("dahua", "imou", "lechange", "amcrest", "dh-ipc", "ipc-hfw", "ipc-hdw", "ipc-a").any { t.contains(it) } -> Brand.DAHUA
            listOf("tapo", "tp-link", "tplink", "tp-ipc").any { t.contains(it) } -> Brand.TAPO
            else -> null
        }
    }

    private fun probe(types: String): String {
        val t = if (types.isEmpty()) "" else "<d:Types>$types</d:Types>"
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\"" +
            " xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\"" +
            " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\"" +
            " xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
            "<e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID>" +
            "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
            "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>" +
            "</e:Header><e:Body><d:Probe>$t</d:Probe></e:Body></e:Envelope>"
    }

    private fun tag(xml: String, name: String): String =
        Regex("<(?:[\\w-]+:)?$name[^>]*>(.*?)</(?:[\\w-]+:)?$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim() ?: ""

    private fun scope(scopes: String, key: String): String {
        val raw = Regex("onvif://www\\.onvif\\.org/$key/(\\S+)", RegexOption.IGNORE_CASE)
            .find(scopes)?.groupValues?.get(1) ?: return ""
        return try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
    }

    /** ONVIF WS-Discovery; calls [onFound] for every camera that answers within [waitMs]. */
    fun onvif(local: Inet4Address, waitMs: Int, onFound: (Found) -> Unit) {
        val sock = MulticastSocket()
        try {
            try {
                sock.networkInterface = NetworkInterface.getByInetAddress(local)
            } catch (_: Exception) {
            }
            sock.soTimeout = 400
            val group = InetAddress.getByName("239.255.255.250")
            for (types in listOf("dn:NetworkVideoTransmitter", "")) {
                val msg = probe(types).toByteArray(Charsets.UTF_8)
                sock.send(DatagramPacket(msg, msg.size, group, 3702))
            }
            val buf = ByteArray(65535)
            val end = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(p)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val xml = String(p.data, 0, p.length, Charsets.UTF_8)
                if (!xml.contains("ProbeMatch")) continue
                val xaddrs = tag(xml, "XAddrs")
                val ip = Regex("https?://(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(xaddrs)?.groupValues?.get(1)
                    ?: p.address?.hostAddress ?: continue
                val scopes = tag(xml, "Scopes")
                val name = scope(scopes, "name")
                val hardware = scope(scopes, "hardware")
                val model = listOf(name, hardware).filter { it.isNotEmpty() }.distinct().joinToString(" ")
                onFound(Found(ip, guess("$scopes $model"), model, onvif = true))
            }
        } finally {
            sock.close()
        }
    }

    /**
     * Knocks on port 554 of every address on the network.
     * Calls [onFound] for every device that answers and [onProgress] with how many addresses are done (of 254).
     */
    fun scanRtsp(local: Inet4Address, cancelled: () -> Boolean, onFound: (Found) -> Unit, onProgress: (Int) -> Unit) {
        val b = local.address
        val prefix = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}."
        val self = local.hostAddress
        val pool = Executors.newFixedThreadPool(48)
        val done = AtomicInteger()
        for (i in 1..254) {
            val ip = prefix + i
            pool.execute {
                if (ip != self && !cancelled()) {
                    val server = rtspAnswer(ip, 554, 700)
                    if (server != null) onFound(Found(ip, guess(server), server, rtsp = true))
                }
                onProgress(done.incrementAndGet())
            }
        }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
    }

    /** null = nothing on that port; otherwise the device's "Server" name (may be empty). */
    private fun rtspAnswer(ip: String, port: Int, timeoutMs: Int): String? {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(ip, port), timeoutMs)
        } catch (_: IOException) {
            try { s.close() } catch (_: IOException) {}
            return null
        }
        try {
            s.soTimeout = 1500
            s.getOutputStream().write("OPTIONS rtsp://$ip:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: ipcam\r\n\r\n".toByteArray())
            val buf = ByteArray(2048)
            val n = s.getInputStream().read(buf)
            if (n <= 0) return ""
            val reply = String(buf, 0, n, Charsets.ISO_8859_1)
            return Regex("(?im)^Server:\\s*(.+?)\\s*$").find(reply)?.groupValues?.get(1) ?: ""
        } catch (_: IOException) {
            return ""
        } finally {
            try { s.close() } catch (_: IOException) {}
        }
    }
}
