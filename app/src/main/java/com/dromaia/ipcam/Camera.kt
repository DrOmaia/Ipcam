package com.dromaia.ipcam

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// The supported camera makes and how each one names its RTSP streams
enum class Brand(val key: String, val label: String) {
    HIKVISION("hik", "Hikvision / HiLook"),
    DAHUA("dahua", "Dahua / Imou"),
    TAPO("tapo", "TP-Link Tapo"),
    CUSTOM("custom", "رابط RTSP");

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
    var url: String = "", // only for CUSTOM: the full rtsp:// link
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
            Brand.CUSTOM -> url.trim()
        }
    }

    /** The link to show on screen, with the password hidden. */
    fun shown(main: Boolean): String {
        val u = rtsp(main)
        return u.replace(Regex("(rtsp://[^:/@]*:)[^@]*@"), "$1•••@")
    }

    /** The camera's address, for the list. */
    fun where(): String =
        if (brand == Brand.CUSTOM) (try { Uri.parse(url.trim()).host } catch (_: Exception) { null } ?: url) else host

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("brand", brand.key).put("host", host).put("port", port)
        .put("user", user).put("pass", pass).put("channel", channel).put("url", url)

    companion object {
        fun fromJson(o: JSONObject) = Camera(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"),
            brand = Brand.of(o.optString("brand")) ?: Brand.HIKVISION,
            host = o.optString("host"),
            port = o.optInt("port", 554),
            user = o.optString("user", "admin"),
            pass = o.optString("pass"),
            channel = o.optInt("channel", 1),
            url = o.optString("url"),
        )
    }
}

// The cameras are kept in the app's private storage on this phone only (never uploaded anywhere)
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

    private fun write(c: Context, list: List<Camera>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    fun get(c: Context, id: String): Camera? = all(c).firstOrNull { it.id == id }

    fun put(c: Context, cam: Camera) {
        val list = all(c)
        val i = list.indexOfFirst { it.id == cam.id }
        if (i >= 0) list[i] = cam else list.add(cam)
        write(c, list)
    }

    fun remove(c: Context, id: String) = write(c, all(c).filter { it.id != id })
}
