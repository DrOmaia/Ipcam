package com.dromaia.ipcam

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// The supported camera makes and how each one names its RTSP streams
enum class Brand(val key: String, val ar: String, val en: String, val port: Int, val onvifPort: Int) {
    HIKVISION("hik", "Hikvision / HiLook", "Hikvision / HiLook", 554, 80),
    DAHUA("dahua", "Dahua / Imou", "Dahua / Imou", 554, 80),
    TAPO("tapo", "TP-Link Tapo", "TP-Link Tapo", 554, 2020),
    IPWEBCAM("ipwebcam", "جوال (IP Webcam)", "Phone (IP Webcam)", 8080, 0),
    PHONE("phone", "جوال بهذا التطبيق", "Phone with this app", 8554, 0),
    CUSTOM("custom", "رابط RTSP", "RTSP link", 554, 80);

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
) {
    /** main = full quality (one camera on screen); sub = light stream (several cameras on screen). */
    fun rtsp(main: Boolean): String {
        if (brand == Brand.CUSTOM) return url.trim()
        val auth = if (user.isEmpty()) "" else Uri.encode(user) + ":" + Uri.encode(pass) + "@"
        val base = "rtsp://$auth${host.trim()}:$port"
        return when (brand) {
            // channel 1 → 101 (main) / 102 (sub); on a recorder (NVR) channel 5 → 501 / 502
            Brand.HIKVISION -> "$base/Streaming/Channels/${channel}0${if (main) 1 else 2}"
            Brand.DAHUA -> "$base/cam/realmonitor?channel=$channel&subtype=${if (main) 0 else 1}"
            Brand.TAPO -> "$base/${if (main) "stream1" else "stream2"}"
            Brand.IPWEBCAM -> "$base/h264_ulaw.sdp"
            Brand.PHONE -> "$base/${if (main) "live" else "low"}"
            Brand.CUSTOM -> url.trim()
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
        )
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
