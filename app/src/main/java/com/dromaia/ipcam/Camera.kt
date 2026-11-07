package com.dromaia.ipcam

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// The supported camera makes and how each one names its RTSP streams
enum class Brand(val key: String, val ar: String, val en: String, val port: Int, val onvifPort: Int) {
    // the order is the order in the «camera type» list: the most common first
    HIKVISION("hik", "Hikvision / HiLook", "Hikvision / HiLook", 554, 80),
    DAHUA("dahua", "Dahua / Imou", "Dahua / Imou", 554, 80),
    EZVIZ("ezviz", "EZVIZ", "EZVIZ", 554, 0),
    TAPO("tapo", "TP-Link Tapo", "TP-Link Tapo", 554, 2020),
    VIGI("vigi", "TP-Link VIGI", "TP-Link VIGI", 554, 80),
    REOLINK("reolink", "Reolink", "Reolink", 554, 8000),
    UNIVIEW("uniview", "Uniview (UNV)", "Uniview (UNV)", 554, 80),
    FOSCAM("foscam", "Foscam", "Foscam", 88, 888),
    EUFY("eufy", "eufy", "eufy", 554, 0),
    WYZE("wyze", "Wyze", "Wyze", 554, 0),
    YOOSEE("yoosee", "Yoosee", "Yoosee", 554, 5000),
    VSTARCAM("vstarcam", "Vstarcam / Eye4", "Vstarcam / Eye4", 10554, 10080),
    IPWEBCAM("ipwebcam", "جوال (IP Webcam)", "Phone (IP Webcam)", 8080, 0),
    PHONE("phone", "جوال بهذا التطبيق", "Phone with this app", 8554, 0),
    CUSTOM("custom", "رابط RTSP", "RTSP link", 554, 80);

    /** Makes whose cameras can sit on a recorder with several channels. */
    val channels: Boolean get() = this == HIKVISION || this == DAHUA || this == UNIVIEW || this == REOLINK

    /** Yoosee sends its video over UDP only; every other make plays steadier over TCP. */
    val tcp: Boolean get() = this != YOOSEE

    /** User name to start with («» = the owner sets it in the camera's own app). */
    val defaultUser: String get() = when (this) {
        TAPO, IPWEBCAM, EUFY, WYZE -> ""
        PHONE -> "ipcam"
        else -> "admin"
    }

    fun label(c: Context) = L.t(c, ar, en)

    /** Makes that can answer ONVIF (PTZ, motion events, snapshots). */
    val onvif: Boolean get() = onvifPort > 0

    companion object {
        fun of(key: String?): Brand? = values().firstOrNull { it.key == key }
    }
}

class Camera(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var brand: Brand = Brand.HIKVISION,
    var host: String = "",
    var port: Int = 554,
    var user: String = "admin",
    var pass: String = "",
    var channel: Int = 1,
    var url: String = "",       // only for CUSTOM: the full rtsp:// link
    var group: String = "",     // "" = no group (e.g. "داخل البيت", "الحوش")
    var ptz: Boolean = false,   // the camera can turn (pan / tilt / zoom) through ONVIF
    var onvifPort: Int = 0,     // 0 = the make's usual ONVIF port (Brand.onvifPort)
    var onvifUser: String = "", // "" = same as user (Hikvision often needs a separate ONVIF user)
    var onvifPass: String = "",
    var alert: Boolean = false, // motion alerts for this camera
    var popTv: Boolean = true,  // on a TV: show this camera over other apps when it sees motion
    var remoteHost: String = "", // from outside the home: DDNS name, public IP or VPN (Tailscale) address; "" = none
    var remotePort: Int = 0,     // RTSP port from outside (0 = same as port)
    var remoteHttp: Int = 0,     // HTTP port from outside, for ISAPI / ONVIF (0 = same as httpPort())
    var alertWhat: String = "any", // which motion alerts: "any", "human", "vehicle", "humanVehicle"
) {
    /** main = full quality (one camera on screen); sub = light stream (several cameras on screen).
     *  remote = through the address from outside the home (Route decides which one to try first). */
    fun rtsp(main: Boolean, remote: Boolean = false): String {
        if (brand == Brand.CUSTOM) return if (remote && hasRemote()) swapHost(url.trim()) else url.trim()
        val auth = if (user.isEmpty()) "" else Uri.encode(user) + ":" + Uri.encode(pass) + "@"
        val h = if (remote && hasRemote()) remoteHost.trim() else host.trim()
        val pt = if (remote && hasRemote() && remotePort > 0) remotePort else port
        val base = "rtsp://$auth$h:$pt"
        return when (brand) {
            // channel 1 → 101 (main) / 102 (sub); on a recorder (NVR) channel 5 → 501 / 502
            Brand.HIKVISION -> "$base/Streaming/Channels/${channel}0${if (main) 1 else 2}"
            Brand.DAHUA -> "$base/cam/realmonitor?channel=$channel&subtype=${if (main) 0 else 1}"
            Brand.TAPO, Brand.VIGI -> "$base/${if (main) "stream1" else "stream2"}"
            Brand.EZVIZ -> "$base/h264/ch1/${if (main) "main" else "sub"}/av_stream"
            // Reolink: main by the new name (H.264 or H.265), light stream by the old name every firmware knows
            Brand.REOLINK -> channel.toString().padStart(2, '0').let { if (main) "$base/Preview_${it}_main" else "$base/h264Preview_${it}_sub" }
            Brand.UNIVIEW -> "$base/unicast/c$channel/s${if (main) 0 else 1}/live"
            Brand.FOSCAM -> "$base/${if (main) "videoMain" else "videoSub"}"
            Brand.EUFY -> "$base/live0"
            Brand.WYZE -> "$base/live"
            Brand.YOOSEE -> "$base/${if (main) "onvif1" else "onvif2"}"
            Brand.VSTARCAM -> "$base/tcp/av0_${if (main) 0 else 1}"
            Brand.IPWEBCAM -> "$base/h264_ulaw.sdp"
            Brand.PHONE -> "$base/${if (main) "live" else "low"}"
            Brand.CUSTOM -> url.trim()
        }
    }

    /** Recorded video of this channel (Hikvision) from [startLocal] = "yyyyMMdd'T'HHmmss'Z'" in the recorder's own clock. */
    fun playback(startLocal: String, remote: Boolean = false): String {
        val auth = if (user.isEmpty()) "" else Uri.encode(user) + ":" + Uri.encode(pass) + "@"
        val h = if (remote && hasRemote()) remoteHost.trim() else host.trim()
        val pt = if (remote && hasRemote() && remotePort > 0) remotePort else port
        return "rtsp://$auth$h:$pt/Streaming/tracks/${channel}01?starttime=$startLocal"
    }

    fun hasRemote(): Boolean = remoteHost.isNotBlank()

    /** HTTP port of the camera / recorder (ISAPI and ONVIF). */
    fun httpPort(): Int = onvifPortOrDefault()

    /** Same camera on the same recorder (same address and login): channels of one DVR / NVR. */
    fun sameDevice(o: Camera): Boolean =
        brand == o.brand && address().equals(o.address(), true) && port == o.port && user == o.user && pass == o.pass

    // a CUSTOM rtsp:// link with the outside address and port instead of the home one
    private fun swapHost(u: String): String {
        return try {
            val p = Uri.parse(u)
            val hostPart = p.host ?: return u
            val newPort = if (remotePort > 0) remotePort else p.port
            u.replaceFirst(Regex("@?" + Regex.escape(hostPart) + "(:\\d+)?"),
                (if (u.contains("@")) "@" else "") + remoteHost.trim() + (if (newPort > 0) ":$newPort" else ""))
        } catch (_: Exception) {
            u
        }
    }

    /** The link to show on screen, with the password hidden. */
    fun shown(main: Boolean): String = rtsp(main).replace(Regex("(rtsp://[^:/@]*:)[^@]*@"), "$1•••@")

    /** The camera's address (host), also for CUSTOM links. */
    fun address(): String =
        if (brand == Brand.CUSTOM) (try { Uri.parse(url.trim()).host } catch (_: Exception) { null } ?: "") else host.trim()

    /** The RTSP port actually used (also for CUSTOM links). */
    fun rtspPort(): Int =
        if (brand == Brand.CUSTOM) (try { Uri.parse(url.trim()).port } catch (_: Exception) { -1 }).let { if (it > 0) it else 554 } else port

    /** For the list. */
    fun where(): String = address().ifEmpty { url }

    fun onvifPortOrDefault(): Int = if (onvifPort > 0) onvifPort else brand.onvifPort.let { if (it > 0) it else 80 }
    fun onvifUserOrDefault(): String = onvifUser.ifEmpty { user }
    fun onvifPassOrDefault(): String = if (onvifUser.isEmpty()) pass else onvifPass

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("brand", brand.key).put("host", host).put("port", port)
        .put("user", user).put("pass", pass).put("channel", channel).put("url", url)
        .put("group", group).put("ptz", ptz).put("onvifPort", onvifPort).put("onvifUser", onvifUser)
        .put("onvifPass", onvifPass).put("alert", alert).put("popTv", popTv)
        .put("remoteHost", remoteHost).put("remotePort", remotePort).put("remoteHttp", remoteHttp).put("alertWhat", alertWhat)

    companion object {
        fun fromJson(o: JSONObject) = Camera(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            name = o.optString("name"),
            brand = Brand.of(o.optString("brand")) ?: Brand.HIKVISION,
            host = o.optString("host"),
            port = o.optInt("port", 554),
            user = o.optString("user", "admin"),
            pass = o.optString("pass"),
            channel = o.optInt("channel", 1),
            url = o.optString("url"),
            group = o.optString("group"),
            ptz = o.optBoolean("ptz", false),
            onvifPort = o.optInt("onvifPort", 0),
            onvifUser = o.optString("onvifUser"),
            onvifPass = o.optString("onvifPass"),
            alert = o.optBoolean("alert", false),
            popTv = o.optBoolean("popTv", true),
            remoteHost = o.optString("remoteHost"),
            remotePort = o.optInt("remotePort", 0),
            remoteHttp = o.optInt("remoteHttp", 0),
            alertWhat = o.optString("alertWhat", "any").ifEmpty { "any" },
        )
    }
}

// Which address to use for a camera: at home its local address, away the outside one (remoteHost).
// The local one is tried first when this device is on the same network as the camera; whichever works is remembered.
object Route {
    class Target(val host: String, val rtspPort: Int, val httpPort: Int, val remote: Boolean)

    private val lastRemote = ConcurrentHashMap<String, Boolean>()

    /** Addresses to try, in order (one when there is no outside address). */
    fun targets(cam: Camera): List<Target> {
        val local = Target(cam.address(), cam.rtspPort(), cam.httpPort(), false)
        if (!cam.hasRemote()) return listOf(local)
        val remote = Target(cam.remoteHost.trim(), if (cam.remotePort > 0) cam.remotePort else cam.rtspPort(),
            if (cam.remoteHttp > 0) cam.remoteHttp else cam.httpPort(), true)
        val remoteFirst = lastRemote[cam.id] ?: !sameNetwork(cam.address())
        return if (remoteFirst) listOf(remote, local) else listOf(local, remote)
    }

    /** Start with the outside address? */
    fun preferRemote(cam: Camera): Boolean = cam.hasRemote() && targets(cam).first().remote

    /** Remember which address answered for this camera. */
    fun worked(cam: Camera, remote: Boolean) {
        if (cam.hasRemote()) lastRemote[cam.id] = remote
    }

    // a private address (192.168.x / 10.x / 172.16–31.x) on the same /24 as this device = at home with the camera
    private fun sameNetwork(host: String): Boolean {
        val me = Discovery.localIPv4() ?: return false
        val parts = host.trim().split('.')
        // a name for the home address (no lookup here: this can run on the main thread): try it first
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return true
        val mine = me.hostAddress?.split('.') ?: return false
        return mine.size == 4 && mine[0] == parts[0] && mine[1] == parts[1] && mine[2] == parts[2]
    }
}

// The cameras are kept in the app's private storage on this device only. They leave it only when the owner sends
// them himself to another device of the family on the same Wi-Fi (Transfer, with the code shown on that device).
// Changes are one at a time (@Synchronized): two transfers arriving together must not lose each other's cameras.
object Store {
    private const val PREFS = "ipcam"
    private const val KEY = "cameras"

    fun all(c: Context): MutableList<Camera> {
        val out = ArrayList<Camera>()
        try {
            val a = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
            for (i in 0 until a.length()) out.add(Camera.fromJson(a.getJSONObject(i)))
        } catch (_: Exception) {
        }
        return out
    }

    @Synchronized
    fun write(c: Context, list: List<Camera>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
        try { Alerts.refresh(c) } catch (_: Exception) {}
        CamWidget.updateAll(c)
        try { LiveWidget.updateAll(c) } catch (_: Exception) {} // a renamed / deleted camera shows at once
    }

    fun get(c: Context, id: String): Camera? = all(c).firstOrNull { it.id == id }

    @Synchronized
    fun put(c: Context, cam: Camera) {
        val list = all(c)
        val i = list.indexOfFirst { it.id == cam.id }
        if (i >= 0) list[i] = cam else list.add(cam)
        write(c, list)
    }

    @Synchronized
    fun remove(c: Context, id: String) = write(c, all(c).filter { it.id != id })

    /** Cameras received from another device: same id = replaced, new id = added. Returns how many. */
    @Synchronized
    fun merge(c: Context, incoming: List<Camera>): Int {
        val list = all(c)
        for (cam in incoming) {
            val i = list.indexOfFirst { it.id == cam.id }
            if (i >= 0) list[i] = cam else list.add(cam)
        }
        write(c, list)
        return incoming.size
    }

    /** The groups in the order they first appear. */
    fun groups(c: Context): List<String> = all(c).map { it.group.trim() }.filter { it.isNotEmpty() }.distinct()
}
