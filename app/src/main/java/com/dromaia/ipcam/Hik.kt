package com.dromaia.ipcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// patterns used to read the recorder's answers (any namespace prefix)
private val HIK_TIME_RX = Regex("(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})")
private val HIK_BOUNDARY_RX = Regex("boundary\\s*=\\s*\"?([^\";,]+)\"?", RegexOption.IGNORE_CASE)
private val HIK_SOURCE_RX = Regex(
    "<(?:[\\w.-]+:)?sourceInputPortDescriptor\\b.*?</(?:[\\w.-]+:)?sourceInputPortDescriptor\\s*>",
    RegexOption.DOT_MATCHES_ALL,
)
// where a recorder names what it saw (AcuSense: human / vehicle), in XML or in JSON
private val HIK_TARGET_XML_RX = Regex(
    "<(?:[\\w.-]+:)?(targetType|detectionTarget|detectTarget|targetClass|objectType)\\b[^>]*>([^<]*)<",
    RegexOption.IGNORE_CASE,
)
private val HIK_TARGET_JSON_RX = Regex(
    "\"(targetType|detectionTarget|detectTarget|targetClass|objectType)\"\\s*:\\s*\\[?\\s*\"([^\"]*)\"",
    RegexOption.IGNORE_CASE,
)
private val HIK_FLAG_XML_RX = Regex("<(?:[\\w.-]+:)?(humanDetection|vehicleDetection)\\b[^>]*>\\s*true\\s*<", RegexOption.IGNORE_CASE)
private val HIK_FLAG_JSON_RX = Regex("\"(humanDetection|vehicleDetection)\"\\s*:\\s*\"?true", RegexOption.IGNORE_CASE)
private val HIK_HUMAN_RX = Regex("human|person|people|pedestrian", RegexOption.IGNORE_CASE)
private val HIK_VEHICLE_RX = Regex("vehicle|\\bcar\\b|truck|\\bbus\\b", RegexOption.IGNORE_CASE)

/**
 * Hikvision ISAPI: the recorder's (DVR / NVR) or camera's own web language, over plain HTTP with its user name and
 * password (HTTP Digest, or Basic on old firmware). Used for the list of channels, recorded video, the list of events
 * and alerts by type (person / vehicle / line crossing). Tries the home address and the outside one ([Route]) in order.
 *
 * Times: a recorder writes its own wall clock (often with a "Z" that is not really UTC). Here a recorder time is a
 * "wall-clock ms": the date and time read as if they were UTC, so showing it with the UTC time zone shows exactly
 * the recorder's clock. A wrong password blocks that recorder for 10 minutes (see [refused]): Hikvision locks a
 * device out after a few failed sign-ins. The password goes only to the recorder. Every call blocks: run it off the
 * main thread.
 */
object Hik {
    class Channel(val no: Int, val name: String, val online: Boolean)  // no = the number used in Camera.channel (1…)
    class Segment(val start: Long, val end: Long)                      // wall-clock ms
    class Event(val channel: Int, val start: Long, val end: Long, val kind: String) // human, vehicle, motion, line, intrusion, other

    private const val CONNECT_MS = 4_000
    private const val READ_MS = 15_000
    private const val STREAM_READ_MS = 90_000 // the alert stream: no word at all for 90 s = dead, connect again
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_PART = 4 * 1024 * 1024
    private const val MAX_WIDTH = 1280
    private const val REFUSED_MS = 10 * 60_000L
    private const val CLOCK_MS = 30 * 60_000L
    private const val PAGE = 50
    private const val MAX_PAGES = 60
    private const val WORKERS = 3
    private const val NS = "http://www.hikvision.com/ver20/XMLSchema"
    private const val META = "//recordType.meta.std-cgi.com"
    private const val POS_TYPO = "searchResultPostion" // the spelling of most Hikvision firmware (sic)
    private const val POS_RIGHT = "searchResultPosition"

    /** What the events list searches for: kind → record type. The human / vehicle names are AcuSense Motion 2.0. */
    private val SEARCHES = listOf(
        "motion" to "$META/VideoMotion",
        "human" to "$META/humanDetection",
        "vehicle" to "$META/vehicleDetection",
        "line" to "$META/lineDetection",
        "intrusion" to "$META/fieldDetection",
        "intrusion" to "$META/regionEntrance",
    )

    /** A failed call; [auth] = the recorder refused the user name / password; [code] = its HTTP answer, if any. */
    private class Fail(val auth: Boolean = false, val code: Int = 0) : Exception()

    private class Resp(
        val code: Int,
        val data: ByteArray,
        val type: String,
        val challenges: List<String>,
        val conn: HttpURLConnection? = null, // an open stream (alertStream): the caller closes it
        val body: InputStream? = null,
    ) {
        val ok: Boolean get() = code in 200..299
        val text: String by lazy { String(data, Charsets.UTF_8) }
    }

    /** One way into a device: the address that answered first and its sign-in. Used by one thread at a time. */
    private class Link(val cam: Camera, @Volatile var target: Route.Target? = null) {
        var ch: Digest.Challenge? = null
        var basic = false
    }

    /** Sign-ins refused lately: device + user + password → until when (elapsedRealtime). */
    private val refusedUntil = ConcurrentHashMap<String, Long>()

    /** The recorder's clock minus this device's clock, per recorder. */
    private class Clock(val offset: Long, val at: Long, val real: Boolean)
    private val clocks = ConcurrentHashMap<String, Clock>()

    /** Which spelling of the search position a recorder reads; record types a recorder rejected. */
    private val posName = ConcurrentHashMap<String, String>()
    private val rejected = ConcurrentHashMap<String, Boolean>()

    // ------------------------------------------------------------------ public API

    /** The recorder's name (e.g. "Irbid…"), or null when it cannot be reached / refused the password. */
    fun deviceName(cam: Camera): String? {
        if (refused(cam)) return null
        return try {
            val r = call(Link(cam), "GET", "/ISAPI/System/deviceInfo")
            if (r.ok) tag(r.text, "deviceName") ?: tag(r.text, "model") ?: "" else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The recorder's channels: a DVR's own camera inputs (name, switched on, a picture on the cable) and the IP
     * cameras of an NVR (number = its id, online as the NVR reports it). A single camera = one channel. Null = not reached.
     */
    fun channels(cam: Camera): List<Channel>? {
        if (refused(cam)) return null
        val l = Link(cam)
        return try {
            val out = TreeMap<Int, Channel>()
            val a = call(l, "GET", "/ISAPI/System/Video/inputs/channels")
            if (a.ok) {
                for (b in blocks(a.text, "VideoInputChannel")) {
                    val id = tag(b, "id")?.toIntOrNull() ?: continue
                    val enabled = tag(b, "videoInputEnabled")?.equals("false", ignoreCase = true) != true
                    // a DVR input with no camera on its cable says "NO VIDEO"
                    val signal = !(tag(b, "resDesc") ?: "").replace(" ", "").contains("NOVIDEO", ignoreCase = true)
                    out[id] = Channel(id, tag(b, "name") ?: "", enabled && signal)
                }
            }
            val p = call(l, "GET", "/ISAPI/ContentMgmt/InputProxy/channels")
            if (p.ok) {
                val online = HashMap<Int, Boolean>()
                val s = call(l, "GET", "/ISAPI/ContentMgmt/InputProxy/channels/status")
                if (s.ok) {
                    for (b in blocks(s.text, "InputProxyChannelStatus")) {
                        val id = tag(b, "id")?.toIntOrNull() ?: continue
                        online[id] = tag(b, "online")?.equals("true", ignoreCase = true) == true
                    }
                }
                for (b in blocks(p.text, "InputProxyChannel")) {
                    val id = tag(b, "id")?.toIntOrNull() ?: continue
                    out[id] = Channel(id, tag(b.replace(HIK_SOURCE_RX, ""), "name") ?: "", online[id] ?: true)
                }
            }
            out.values.toList()
        } catch (_: Throwable) {
            null
        }
    }

    /** Recorded video of the camera's channel between [from] and [to] (wall-clock ms), joined and in order. Null = not reached. */
    fun recordings(cam: Camera, from: Long, to: Long): List<Segment>? {
        if (to <= from) return emptyList()
        if (refused(cam)) return null
        val l = Link(cam)
        return try {
            val found = ArrayList<Segment>()
            search(l, track(cam.channel), META, from, to, false) { s, e, _ ->
                val a = maxOf(s, from)
                val b = minOf(e, to)
                if (b > a) found.add(Segment(a, b))
            }
            found.sortBy { it.start }
            val out = ArrayList<Segment>()
            for (seg in found) {
                val last = out.lastOrNull()
                // the recorder cuts its files every hour or so: one piece for continuous video
                if (last != null && seg.start <= last.end + 2_000L) {
                    if (seg.end > last.end) out[out.size - 1] = Segment(last.start, seg.end)
                } else {
                    out.add(seg)
                }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Motion, person, vehicle, line-crossing and intrusion events of the channels of one recorder ([cams] = cameras
     * of the same device), newest first; an event found by two searches is listed once. Null = not reached.
     */
    fun events(cams: List<Camera>, from: Long, to: Long): List<Event>? {
        val cam = cams.firstOrNull() ?: return emptyList()
        if (refused(cam)) return null
        if (to <= from) return emptyList()
        val group = cams.filter { it === cam || it.sameDevice(cam) }
        val key = device(cam)
        val jobs = ArrayList<Pair<Int, Pair<String, String>>>()
        for (ch in group.map { it.channel }.distinct().sorted()) {
            for (s in SEARCHES) if (rejected[key + "\u0001" + s.second] != true) jobs.add(Pair(ch, s))
        }
        if (jobs.isEmpty()) return emptyList()
        val found = Collections.synchronizedList(ArrayList<Event>())
        fun run(l: Link, job: Pair<Int, Pair<String, String>>) {
            val (ch, s) = job
            search(l, track(ch), s.second, from, to, true) { a, b, meta -> found.add(Event(ch, a, b, refine(s.first, meta))) }
        }
        val first = Link(cam)
        try {
            // the first search alone: it finds the address that answers and checks the password once
            run(first, jobs[0])
        } catch (_: Throwable) {
            return null
        }
        first.target?.let { t -> group.forEach { Route.worked(it, t.remote) } }
        val rest = jobs.subList(1, jobs.size)
        if (rest.isNotEmpty()) {
            // a few searches side by side (each with its own sign-in), not more: recorders serve few connections
            val failed = AtomicBoolean(false)
            val n = minOf(WORKERS, rest.size)
            val workers = (0 until n).map { w ->
                thread(isDaemon = true, name = "ipcam-hik-search") {
                    val l = if (w == 0) first else Link(cam, first.target)
                    var i = w
                    while (i < rest.size && !failed.get()) {
                        try {
                            run(l, rest[i])
                        } catch (_: Throwable) {
                            failed.set(true)
                        }
                        i += n
                    }
                }
            }
            for (t in workers) {
                try {
                    t.join()
                } catch (_: InterruptedException) {
                    failed.set(true)
                }
            }
            if (failed.get()) return null
        }
        return tidy(ArrayList(found))
    }

    /**
     * Listens to one recorder's alerts (one connection for all its channels in [cams]) until [stop] says so;
     * [onEvent] gets the channel and the kind (human, vehicle, motion, line, intrusion, other) of every new detection.
     * Reconnects by itself (5 s, then longer up to 60 s). After a wrong password it waits 10 minutes, then 30, then
     * every hour. A recorder without the alert stream is watched through ONVIF instead. Never throws.
     */
    fun watch(cams: List<Camera>, stop: () -> Boolean, onEvent: (channel: Int, kind: String) -> Unit) {
        try {
            if (cams.isEmpty()) return
            var wait = 5_000L
            var refusals = 0
            var missing = 0
            while (!stop()) {
                val blocked = blockedFor(cams[0])
                if (blocked > 0) {
                    nap(blocked, stop)
                    continue
                }
                val worked = AtomicBoolean(false)
                var authFail = false
                var code = 0
                try {
                    stream(cams, stop, onEvent, worked)
                } catch (e: Fail) {
                    authFail = e.auth
                    code = e.code
                } catch (_: Throwable) {
                }
                if (stop()) break
                if (worked.get()) {
                    wait = 5_000L
                    refusals = 0
                    missing = 0
                }
                if (authFail && !worked.get()) {
                    refusals++
                    nap(
                        when (refusals) {
                            1 -> 10 * 60_000L
                            2 -> 30 * 60_000L
                            else -> 60 * 60_000L
                        },
                        stop,
                    )
                    continue
                }
                if (code == 403 || code == 404 || code == 405 || code == 501) {
                    // this device has no alert stream (or this user may not use it): ONVIF, like before
                    if (++missing >= 2) {
                        viaOnvif(cams, stop, onEvent)
                        return
                    }
                }
                nap(wait, stop)
                if (!worked.get()) wait = minOf(wait * 2, 60_000L)
            }
        } catch (_: Throwable) {
        }
    }

    /** "Now" on the recorder's clock (wall-clock ms); the phone's own wall clock when the recorder does not say. */
    fun now(cam: Camera): Long {
        val key = device(cam)
        val t = SystemClock.elapsedRealtime()
        clocks[key]?.let { c ->
            if (t - c.at < (if (c.real) CLOCK_MS else 60_000L)) return System.currentTimeMillis() + c.offset
        }
        if (!refused(cam)) {
            try {
                val r = call(Link(cam), "GET", "/ISAPI/System/time")
                val wall = if (r.ok) tag(r.text, "localTime")?.let { wall(it) } else null
                if (wall != null) {
                    clocks[key] = Clock(wall - System.currentTimeMillis(), t, true)
                    return wall
                }
            } catch (_: Throwable) {
            }
        }
        val phone = System.currentTimeMillis()
        val offset = TimeZone.getDefault().getOffset(phone).toLong()
        clocks[key] = Clock(offset, t, false)
        return phone + offset
    }

    /** A wall-clock ms as Hikvision's playback start "yyyyMMdd'T'HHmmss'Z'" (for [Camera.playback]). */
    fun stamp(ms: Long): String = format("yyyyMMdd'T'HHmmss'Z'", ms)

    /** The recorder refused this user name / password in the last 10 minutes: nothing is sent to it meanwhile. */
    fun refused(cam: Camera): Boolean = blockedFor(cam) > 0

    /** A still picture of the camera's channel (at most 1280 px wide), or null. */
    fun snapshot(cam: Camera): Bitmap? {
        if (refused(cam)) return null
        return try {
            val r = call(Link(cam), "GET", "/ISAPI/Streaming/channels/${track(cam.channel)}/picture")
            if (r.ok && r.data.isNotEmpty()) decode(r.data) else null
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ refused passwords

    /** The device (home address and web port): all channels of one recorder share it. No password in it. */
    private fun device(cam: Camera): String = cam.address().lowercase(Locale.US) + ":" + cam.httpPort()

    private fun login(cam: Camera): String = listOf(device(cam), cam.user, cam.pass).joinToString("\u0001")

    private fun blockedFor(cam: Camera): Long {
        val key = login(cam)
        val until = refusedUntil[key] ?: return 0L
        val left = until - SystemClock.elapsedRealtime()
        if (left > 0) return left
        refusedUntil.remove(key, until)
        return 0L
    }

    private fun refuse(cam: Camera) {
        refusedUntil[login(cam)] = SystemClock.elapsedRealtime() + REFUSED_MS
    }

    // ------------------------------------------------------------------ recordings and events

    /** Channel 1 → track 101 (main stream's recording), channel 10 → 1001. */
    private fun track(ch: Int): Int = ch * 100 + 1

    /**
     * Every match of one recording search, page by page: start, end (wall-clock ms) and the record type it names.
     * [lenient] = a record type this recorder does not know gives nothing instead of an error.
     */
    private fun search(l: Link, track: Int, meta: String, from: Long, to: Long, lenient: Boolean, onMatch: (Long, Long, String) -> Unit) {
        val key = device(l.cam)
        val id = UUID.randomUUID().toString().uppercase(Locale.US)
        var name = posName[key] ?: POS_TYPO
        var switched = false
        var pos = 0
        var lastFirst: String? = null
        var pages = 0
        while (pages++ < MAX_PAGES) {
            var r = call(l, "POST", "/ISAPI/ContentMgmt/search", searchXml(id, track, meta, from, to, name, pos))
            if (r.code == 400 && !switched) {
                // a strict recorder that reads the other spelling of the position
                switched = true
                name = if (name == POS_TYPO) POS_RIGHT else POS_TYPO
                r = call(l, "POST", "/ISAPI/ContentMgmt/search", searchXml(id, track, meta, from, to, name, pos))
                if (r.ok) posName[key] = name
                else if (r.code == 400 && pos == 0) rejected[key + "\u0001" + meta] = true
            }
            if (!r.ok) {
                if (lenient) return
                throw Fail(code = r.code)
            }
            val x = r.text
            val items = blocks(x, "searchMatchItem")
            if (items.isEmpty()) return
            if (pos > 0 && items[0] == lastFirst) {
                // the same page again: this recorder reads the other spelling of the position
                if (switched) return
                switched = true
                name = if (name == POS_TYPO) POS_RIGHT else POS_TYPO
                continue
            }
            if (pos > 0) posName[key] = name
            lastFirst = items[0]
            for (b in items) {
                val span = inner(b, "timeSpan") ?: b
                val s = tag(span, "startTime")?.let { wall(it) } ?: continue
                val e = tag(span, "endTime")?.let { wall(it) } ?: s
                onMatch(s, maxOf(s, e), inner(b, "metadataMatches") ?: tag(b, "metadataDescriptor") ?: "")
            }
            if (tag(x, "responseStatusStrg")?.equals("MORE", ignoreCase = true) != true) return
            pos += items.size
        }
    }

    private fun searchXml(id: String, track: Int, meta: String, from: Long, to: Long, posTag: String, pos: Int): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><CMSearchDescription version=\"2.0\" xmlns=\"$NS\">" +
            "<searchID>$id</searchID>" +
            "<trackList><trackID>$track</trackID></trackList>" +
            "<timeSpanList><timeSpan><startTime>${iso(from)}</startTime><endTime>${iso(to)}</endTime></timeSpan></timeSpanList>" +
            "<maxResults>$PAGE</maxResults>" +
            "<$posTag>$pos</$posTag>" +
            "<metadataList><metadataDescriptor>$meta</metadataDescriptor></metadataList>" +
            "</CMSearchDescription>"

    /** The record type a match names can be more precise than the search (a person, a vehicle). */
    private fun refine(kind: String, meta: String): String {
        val m = meta.lowercase(Locale.US)
        return when {
            HIK_HUMAN_RX.containsMatchIn(m) -> "human"
            HIK_VEHICLE_RX.containsMatchIn(m) -> "vehicle"
            kind == "motion" && m.contains("linedetection") -> "line"
            kind == "motion" && (m.contains("fielddetection") || m.contains("regionentr")) -> "intrusion"
            else -> kind
        }
    }

    /**
     * One entry per event: the same kind found twice (overlapping) is joined; a plain motion that overlaps a person,
     * vehicle, line crossing or intrusion of the same channel is that event. Newest first.
     */
    private fun tidy(all: List<Event>): List<Event> {
        val out = ArrayList<Event>()
        for ((ch, list) in all.groupBy { it.channel }) {
            val joined = ArrayList<Event>()
            for ((kind, same) in list.groupBy { it.kind }) {
                var cur: Event? = null
                for (e in same.sortedBy { it.start }) {
                    val c = cur
                    if (c != null && e.start <= c.end) {
                        cur = Event(ch, c.start, maxOf(c.end, e.end), kind)
                    } else {
                        if (c != null) joined.add(c)
                        cur = e
                    }
                }
                cur?.let { joined.add(it) }
            }
            val precise = joined.filter { it.kind != "motion" }
            for (e in joined) {
                if (e.kind != "motion" || precise.none { it.start <= e.end && e.start <= it.end }) out.add(e)
            }
        }
        out.sortWith(compareByDescending<Event> { it.start }.thenBy { it.channel })
        return out
    }

    // ------------------------------------------------------------------ alerts (alertStream)

    /** One alert stream until it ends, fails or [stop]; throws [Fail] when it cannot be opened. */
    private fun stream(cams: List<Camera>, stop: () -> Boolean, onEvent: (Int, String) -> Unit, worked: AtomicBoolean) {
        val cam = cams[0]
        val chans = cams.map { it.channel }.toSet()
        val l = Link(cam)
        val r = call(l, "GET", "/ISAPI/Event/notification/alertStream", null, STREAM_READ_MS, true)
        if (!r.ok) throw Fail(code = r.code)
        val conn = r.conn
        val input = r.body ?: throw Fail()
        l.target?.let { t -> cams.forEach { Route.worked(it, t.remote) } }
        val opened = SystemClock.elapsedRealtime()
        val done = AtomicBoolean(false)
        // a read can wait up to 90 s: close the connection as soon as [stop] says so
        thread(isDaemon = true, name = "ipcam-hik-stop") {
            while (!done.get()) {
                if (stop()) {
                    close(conn, input)
                    break
                }
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        try {
            parts(input, HIK_BOUNDARY_RX.find(r.type)?.groupValues?.get(1)?.trim(), stop) { text ->
                worked.set(true) // any part (also the heartbeat) = the stream works
                val a = parseAlert(text) ?: return@parts
                val ch = a.channel?.takeIf { it in chans } ?: a.altChannel?.takeIf { it in chans }
                    ?: (if (a.channel == null && chans.size == 1) chans.first() else a.channel)
                    ?: return@parts
                for (k in a.kinds) {
                    if (stop()) break
                    try {
                        onEvent(ch, k)
                    } catch (_: Throwable) {
                    }
                }
            }
        } finally {
            done.set(true)
            if (SystemClock.elapsedRealtime() - opened > 30_000L) worked.set(true)
            close(conn, input)
        }
    }

    /** A device without the alert stream: its cameras through ONVIF (one listener each), until [stop]. */
    private fun viaOnvif(cams: List<Camera>, stop: () -> Boolean, onEvent: (Int, String) -> Unit) {
        val threads = cams.map { cam ->
            thread(isDaemon = true, name = "ipcam-motion") {
                Onvif.watchEvents(cam, stop) { on, kind -> if (on) onEvent(cam.channel, kind) }
            }
        }
        for (t in threads) {
            try {
                t.join()
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** What one alert says: its channel (and the NVR's other number for it), and the kinds it stands for. */
    internal class Alert(val channel: Int?, val altChannel: Int?, val kinds: List<String>)

    /** Reads one alert (XML EventNotificationAlert or JSON); null when it is not a new detection (heartbeat, video loss, ended). */
    internal fun parseAlert(text: String): Alert? {
        val t = text.trim()
        val json = t.startsWith("{")
        fun f(name: String): String? = if (json) jfield(t, name) else tag(t, name)
        val type = f("eventType")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val state = f("eventState")?.trim()
        if (state != null && !state.equals("active", ignoreCase = true)) return null
        val lt = type.lowercase(Locale.US)
        if (lt == "videoloss" || lt.contains("heartbeat")) return null
        val base = when {
            lt.contains("linedetection") || lt.contains("linecross") || lt.contains("tripwire") -> "line"
            lt.contains("fielddetection") || lt.contains("intrusion") || lt.contains("regionentr") -> "intrusion"
            lt == "vmd" || lt.contains("motion") || lt == "pir" || lt.startsWith("pir") -> "motion"
            HIK_HUMAN_RX.containsMatchIn(lt) -> "human"
            HIK_VEHICLE_RX.containsMatchIn(lt) -> "vehicle"
            else -> "other"
        }
        val found = targets(t, json)
        if (found.isEmpty() && base == "motion") {
            // some firmware only says it in the description ("Human detection alarm")
            f("eventDescription")?.let { d ->
                if (HIK_HUMAN_RX.containsMatchIn(d)) found.add("human")
                if (HIK_VEHICLE_RX.containsMatchIn(d)) found.add("vehicle")
            }
        }
        val kinds = when {
            base == "human" || base == "vehicle" -> listOf(base)
            base != "other" && found.isNotEmpty() -> found.toList()
            else -> listOf(base)
        }
        val ch = (f("channelID") ?: f("channel"))?.trim()?.toIntOrNull()
        val dyn = f("dynChannelID")?.trim()?.toIntOrNull()
        return Alert(ch ?: dyn, dyn, kinds)
    }

    /** The targets an alert names: "human" and / or "vehicle" (in the order seen); "others" is no target. */
    private fun targets(t: String, json: Boolean): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        for (m in (if (json) HIK_TARGET_JSON_RX else HIK_TARGET_XML_RX).findAll(t)) {
            for (v in m.groupValues[2].split(',', ';', '|', ' ')) {
                if (HIK_HUMAN_RX.containsMatchIn(v)) out.add("human")
                else if (v.contains("vehicle", ignoreCase = true) || v.equals("car", ignoreCase = true)) out.add("vehicle")
            }
        }
        for (m in (if (json) HIK_FLAG_JSON_RX else HIK_FLAG_XML_RX).findAll(t)) {
            out.add(if (m.groupValues[1].startsWith("human", ignoreCase = true)) "human" else "vehicle")
        }
        return out
    }

    /**
     * Reads a multipart/mixed stream part by part (headers, then Content-Length bytes or the lines up to the next
     * boundary) and gives every text part to [onPart]; pictures are skipped. Also reads alerts sent with no parts.
     */
    internal fun parts(input: InputStream, boundary: String?, stop: () -> Boolean, onPart: (String) -> Unit) {
        val inp = BufferedInputStream(input, 16 * 1024)
        val buf = ByteArrayOutputStream()
        val delim = boundary?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("--")) it else "--$it" }
        fun isDelim(s: String): Boolean {
            val x = s.trim()
            if (x.isEmpty()) return false
            return if (delim != null) x.startsWith(delim) || x == boundary else x.startsWith("--") && !x.startsWith("-->")
        }
        var pending: String? = null
        while (!stop()) {
            val line = pending ?: readLine(inp, buf) ?: return
            pending = null
            if (isDelim(line)) {
                var len = -1
                var type = ""
                var firstBody: String? = null
                while (true) {
                    val h = readLine(inp, buf) ?: return
                    if (h.isEmpty()) break
                    val ht = h.trimStart()
                    if (ht.startsWith("<") || ht.startsWith("{")) {
                        firstBody = h // a part without headers
                        break
                    }
                    if (isDelim(h)) {
                        pending = h // an empty part
                        break
                    }
                    val k = h.substringBefore(':').trim().lowercase(Locale.US)
                    val v = h.substringAfter(':', "").trim()
                    if (k == "content-length") len = v.toIntOrNull() ?: -1
                    if (k == "content-type") type = v.lowercase(Locale.US)
                }
                if (pending != null) continue
                val picture = type.startsWith("image") || type.contains("octet")
                if (firstBody == null && len in 0..MAX_PART) {
                    val data = readExactly(inp, len) ?: return
                    if (!picture) onPart(String(data, Charsets.UTF_8))
                } else {
                    val sb = StringBuilder()
                    if (firstBody != null) sb.append(firstBody).append('\n')
                    while (true) {
                        val b = readLine(inp, buf)
                        if (b == null) {
                            if (!picture && sb.isNotBlank()) onPart(utf8(sb))
                            return
                        }
                        if (isDelim(b)) {
                            pending = b
                            break
                        }
                        if (sb.length < MAX_PART) sb.append(b).append('\n')
                    }
                    if (!picture && sb.isNotBlank()) onPart(utf8(sb))
                }
            } else {
                val lt = line.trimStart()
                if (lt.startsWith("<") && line.contains("EventNotificationAlert") && !line.contains("</")) {
                    // an alert sent without multipart framing: up to its closing tag
                    val sb = StringBuilder(line).append('\n')
                    while (true) {
                        val b = readLine(inp, buf) ?: return
                        sb.append(b).append('\n')
                        if (b.contains("</") && b.contains("EventNotificationAlert")) break
                        if (sb.length > MAX_PART) break
                    }
                    onPart(utf8(sb))
                } else if (lt.startsWith("<") && line.contains("EventNotificationAlert")) {
                    onPart(utf8(StringBuilder(line)))
                } else if (lt.startsWith("{")) {
                    // JSON without framing: up to the brace that closes the first one
                    val sb = StringBuilder(line).append('\n')
                    var depth = line.count { it == '{' } - line.count { it == '}' }
                    while (depth > 0) {
                        val b = readLine(inp, buf) ?: return
                        sb.append(b).append('\n')
                        depth += b.count { it == '{' } - b.count { it == '}' }
                        if (sb.length > MAX_PART) break
                    }
                    onPart(utf8(sb))
                }
            }
        }
    }

    /** Lines are read as bytes (ISO-8859-1, one char per byte): back to the UTF-8 text they carry. */
    private fun utf8(sb: CharSequence): String = String(sb.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)

    /** One line (without CR LF) as ISO-8859-1, or null at the end of the stream. */
    private fun readLine(input: InputStream, buf: ByteArrayOutputStream): String? {
        buf.reset()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                break
            }
            if (b == '\n'.code) break
            buf.write(b)
            if (buf.size() >= 64 * 1024) break
        }
        var s = String(buf.toByteArray(), Charsets.ISO_8859_1)
        if (s.endsWith("\r")) s = s.substring(0, s.length - 1)
        return s
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val k = input.read(out, got, n - got)
            if (k < 0) return null
            got += k
        }
        return out
    }

    private fun close(conn: HttpURLConnection?, input: InputStream?) {
        try {
            conn?.disconnect()
        } catch (_: Throwable) {
        }
        try {
            input?.close()
        } catch (_: Throwable) {
        }
    }

    private fun nap(ms: Long, stop: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (!stop() && SystemClock.elapsedRealtime() < end) {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    // ------------------------------------------------------------------ HTTP and sign-in

    /**
     * One ISAPI call. The first call of a [Link] tries the device's addresses in [Route] order (the next one only when
     * an address cannot be reached or refuses the password: it may be another device on another network) and keeps
     * the one that answered. Returns any HTTP answer; throws [Fail] when no address answers, and a refused [Fail]
     * (and blocks the device for 10 minutes) when the password was refused.
     */
    private fun call(l: Link, method: String, path: String, body: String? = null, readMs: Int = READ_MS, stream: Boolean = false): Resp {
        if (blockedFor(l.cam) > 0) throw Fail(true)
        val known = l.target
        if (known != null) {
            try {
                return send(l, known, method, path, body, readMs, stream)
            } catch (e: Fail) {
                if (e.auth) refuse(l.cam)
                throw e
            }
        }
        var refusedHere = false
        for (t in Route.targets(l.cam)) {
            if (t.host.isEmpty()) continue
            try {
                val r = send(l, t, method, path, body, readMs, stream)
                l.target = t
                if (r.ok) Route.worked(l.cam, t.remote)
                return r
            } catch (e: Fail) {
                if (e.auth) refusedHere = true
            }
        }
        if (refusedHere) {
            refuse(l.cam)
            throw Fail(true)
        }
        throw Fail()
    }

    /**
     * One request to one address, answering the device's password request once: a fresh Digest challenge (or Basic).
     * A remembered challenge that expired is answered again with the new one; a fresh one refused = wrong password.
     */
    private fun send(l: Link, t: Route.Target, method: String, path: String, body: String?, readMs: Int, stream: Boolean): Resp {
        val url = "http://" + hostPort(t.host, t.httpPort) + path
        val data = body?.toByteArray(Charsets.UTF_8)
        val user = l.cam.user
        val pass = l.cam.pass
        val same = l.target === t
        val ch0 = if (same) l.ch else null
        val sentBasic = same && ch0 == null && l.basic && user.isNotEmpty()
        val first = when {
            ch0 != null -> Digest.header(ch0, method, url, user, pass)
            sentBasic -> Digest.basic(user, pass)
            else -> null
        }
        var r = http(url, method, data, first, readMs, stream)
        if (r.code != 401) return r
        if (user.isEmpty()) throw Fail(true)
        val ch = Digest.challenge(r.challenges)
        val header = when {
            ch != null -> Digest.header(ch, method, url, user, pass)
            sentBasic -> throw Fail(true) // Basic never expires: the same answer again would fail again
            Digest.offersBasic(r.challenges) -> Digest.basic(user, pass)
            else -> throw Fail(true)
        }
        r = http(url, method, data, header, readMs, stream)
        if (r.code == 401) {
            l.ch = null
            l.basic = false
            throw Fail(true)
        }
        l.ch = ch
        l.basic = ch == null
        return r
    }

    /** One HTTP request; any network problem throws [Fail]. [stream] = a successful answer is left open for reading. */
    private fun http(url: String, method: String, body: ByteArray?, auth: String?, readMs: Int, stream: Boolean): Resp {
        var conn: HttpURLConnection? = null
        var keep = false
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = CONNECT_MS
            c.readTimeout = readMs
            c.useCaches = false
            c.instanceFollowRedirects = false
            c.requestMethod = method
            c.setRequestProperty("Connection", if (stream) "keep-alive" else "close")
            if (auth != null) c.setRequestProperty("Authorization", auth)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/xml; charset=UTF-8")
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val type = c.contentType ?: ""
            val challenges = ArrayList<String>()
            val fields = c.headerFields
            if (fields != null) {
                for ((k, v) in fields) {
                    if (k != null && v != null && k.equals("WWW-Authenticate", ignoreCase = true)) challenges.addAll(v)
                }
            }
            if (stream && code in 200..299) {
                keep = true
                return Resp(code, ByteArray(0), type, challenges, c, c.inputStream)
            }
            val input: InputStream? = if (code >= 400) c.errorStream else c.inputStream
            val data = input?.use { read(it) } ?: ByteArray(0)
            return Resp(code, data, type, challenges)
        } catch (e: Fail) {
            throw e
        } catch (_: Throwable) {
            throw Fail()
        } finally {
            if (!keep) {
                try {
                    conn?.disconnect()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun read(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) throw Fail()
        }
        return out.toByteArray()
    }

    private fun decode(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_WIDTH) sample *= 2
        val o = BitmapFactory.Options()
        o.inSampleSize = sample
        return BitmapFactory.decodeByteArray(data, 0, data.size, o)
    }

    // ------------------------------------------------------------------ small helpers

    private fun hostPort(host: String, port: Int): String =
        (if (host.contains(':') && !host.startsWith("[")) "[$host]" else host) + ":" + port

    /** "2024-05-01T13:05:00Z" / "…+03:00" / "2024-05-01 13:05:00" → wall-clock ms (the date and time as if UTC). */
    internal fun wall(s: String): Long? {
        val g = HIK_TIME_RX.find(s)?.groupValues ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        return cal.timeInMillis
    }

    /** A wall-clock ms as the recorder writes its time ("2024-05-01T13:05:00Z", its own clock). */
    private fun iso(ms: Long): String = format("yyyy-MM-dd'T'HH:mm:ss'Z'", ms)

    private fun format(pattern: String, ms: Long): String {
        val f = SimpleDateFormat(pattern, Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    /** Text of the first element with this local name (any prefix). */
    private fun tag(xml: String, name: String): String? =
        Regex("<(?:[\\w.-]+:)?$name\\b[^>]*(?<!/)>([^<]*)</(?:[\\w.-]+:)?$name\\s*>")
            .find(xml)?.groupValues?.get(1)?.let { unesc(it).trim() }

    /** Inner XML of the first element with this local name (any prefix). */
    private fun inner(xml: String, name: String): String? =
        Regex("<(?:[\\w.-]+:)?$name\\b[^>]*(?<!/)>(.*?)</(?:[\\w.-]+:)?$name\\s*>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)

    /** Inner XML of every element with this local name (any prefix). */
    private fun blocks(xml: String, name: String): List<String> =
        Regex("<(?:[\\w.-]+:)?$name\\b[^>]*(?<!/)>(.*?)</(?:[\\w.-]+:)?$name\\s*>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }.toList()

    /** A JSON field's value (first one with this name, any depth; text or number). */
    private fun jfield(json: String, name: String): String? {
        val m = Regex("\"$name\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(-?[\\w.]+))").find(json) ?: return null
        return m.groups[1]?.value ?: m.groupValues[2]
    }

    private fun unesc(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
