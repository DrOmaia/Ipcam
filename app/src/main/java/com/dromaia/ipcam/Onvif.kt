package com.dromaia.ipcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

// patterns used to read the cameras' answers (any namespace prefix: tt:, tds:, env:, SOAP-ENV: …)
private val FAULT_RX = Regex("<(?:[\\w.-]+:)?Fault\\b")
private val AUTH_RX = Regex(
    "NotAuthorized|FailedAuthentication|InvalidSecurity|FailedCheck|not ?authori[sz]ed|Unauthori[sz]ed",
    RegexOption.IGNORE_CASE,
)
private val NOTE_RX = Regex(
    "<(?:[\\w.-]+:)?NotificationMessage\\b.*?</(?:[\\w.-]+:)?NotificationMessage\\s*>",
    RegexOption.DOT_MATCHES_ALL,
)
private val MOTION_RX = Regex("Motion|CellMotionDetector|MotionAlarm|VideoMotion", RegexOption.IGNORE_CASE)
// topics that say what moved (Tapo / Reolink PeopleDetector, VehicleDetector …; line crossing; intrusion)
private val HUMAN_TOPIC_RX = Regex("People|Human|Person|Pedestrian", RegexOption.IGNORE_CASE)
private val VEHICLE_TOPIC_RX = Regex("Vehicle|CarDetect", RegexOption.IGNORE_CASE)
private val LINE_TOPIC_RX = Regex("LineDetector|LineCross|Crossed|Tripwire", RegexOption.IGNORE_CASE)
private val FIELD_TOPIC_RX = Regex("FieldDetector|Intrusion|ObjectsInside|RegionEntr", RegexOption.IGNORE_CASE)
private val INITIAL_RX = Regex("PropertyOperation\\s*=\\s*[\"']Initialized", RegexOption.IGNORE_CASE)
private val ITEM_RX = Regex("<(?:[\\w.-]+:)?SimpleItem\\b([^>]*)>")
private val PROFILE_RX = Regex(
    "<(?:[\\w.-]+:)?Profiles\\b([^>]*)>(.*?)</(?:[\\w.-]+:)?Profiles\\s*>",
    RegexOption.DOT_MATCHES_ALL,
)
private val VSC_RX = Regex(
    "<(?:[\\w.-]+:)?VideoSourceConfiguration\\b([^>]*)>(.*?)</(?:[\\w.-]+:)?VideoSourceConfiguration\\s*>",
    RegexOption.DOT_MATCHES_ALL,
)
private val PTZ_CONF_RX = Regex("<(?:[\\w.-]+:)?PTZConfiguration\\b")
private val SERVICE_RX = Regex(
    "<(?:[\\w.-]+:)?Service\\b[^>]*>(.*?)</(?:[\\w.-]+:)?Service\\s*>",
    RegexOption.DOT_MATCHES_ALL,
)
private val TIME_RX = Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:[.,]\\d+)?(Z|[+-]\\d{2}:?\\d{2})?")
private val PREFIX_RX = Regex("</?([\\w.-]+):|\\s([\\w.-]+):[\\w.-]+\\s*=")
private val SPACES_RX = Regex("\\s+")

/**
 * ONVIF: the common language of Hikvision, Dahua and Tapo cameras, spoken over plain HTTP on the home Wi-Fi (or the
 * camera's outside address, see [Route]). Used to turn a camera (PTZ), to take a still picture for a motion
 * notification, and to hear about motion (with its kind when the camera tells: person, vehicle, line crossing).
 * The user name and password go only to the camera itself. Every call blocks: run it off the main thread.
 */
object Onvif {
    private const val CONNECT_MS = 4_000
    private const val READ_MS = 15_000
    private const val PULL_READ_MS = 15_000 // PullMessages waits up to 10 s on the camera, plus 5 s
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_WIDTH = 1280
    private const val REFUSED_MS = 10 * 60_000L // a refused password: no PTZ / picture tries for 10 minutes

    private const val SOAP = "http://www.w3.org/2003/05/soap-envelope"
    private const val DEV = "http://www.onvif.org/ver10/device/wsdl"
    private const val MEDIA = "http://www.onvif.org/ver10/media/wsdl"
    private const val PTZ = "http://www.onvif.org/ver20/ptz/wsdl"
    private const val EVENTS = "http://www.onvif.org/ver10/events/wsdl"
    private const val WSN = "http://docs.oasis-open.org/wsn/b-2"
    private const val TT = "http://www.onvif.org/ver10/schema"
    private const val WSA = "http://www.w3.org/2005/08/addressing"
    private const val WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
    private const val WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
    private const val PW_DIGEST =
        "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest"
    private const val BASE64 =
        "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary"

    private const val A_CREATE = "http://www.onvif.org/ver10/events/wsdl/EventPortType/CreatePullPointSubscriptionRequest"
    private const val A_PULL = "http://www.onvif.org/ver10/events/wsdl/PullPointSubscription/PullMessagesRequest"
    private const val A_RENEW = "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/RenewRequest"
    private const val A_UNSUB = "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/UnsubscribeRequest"

    private val rnd = SecureRandom()

    /** A failed call; [auth] = the camera refused the user name / password. */
    private class Fail(val auth: Boolean = false) : Exception()

    /** How to sign in to one camera. */
    private class Auth(val user: String, val pass: String) {
        @Volatile var offset = 0L              // the camera's clock minus this device's clock (ms)
        @Volatile var digest = false           // this camera wants HTTP Digest instead of WS-Security
        @Volatile var challenge: Digest.Challenge? = null
    }

    private class Profile(val token: String, val source: String, val vsc: String, val ptz: Boolean)

    /** What we learned about one camera (kept until a call fails or the camera's settings change). */
    private class Session(
        val sig: String,
        val host: String,        // the address that answered (home or outside, see Route)
        val httpPort: Int,       // its web port
        val fixPort: Int,        // port for the camera's service addresses: -1 = the camera's own (at home)
        val device: String,      // device service on that address
        val auth: Auth,
        val media: String,
        val ptz: String,
        val events: String,
        val profile: String?,
        val ptzProfile: String?,
        val mine: Set<String>,   // tokens of this camera's channel (recorders with several cameras)
        val others: Set<String>, // tokens of the other channels
    ) {
        @Volatile var snapUri: String? = null
    }

    /** A motion subscription on the camera. */
    private class Sub(val url: String, val to: String, val refs: String, val ns: String)

    private class Resp(val code: Int, val data: ByteArray, val challenges: List<String>) {
        val text: String by lazy { String(data, Charsets.UTF_8) }
        val fault: Boolean get() = FAULT_RX.containsMatchIn(text)
        val ok: Boolean get() = code in 200..299 && !fault
        val authFailed: Boolean get() = code == 401 || ((code >= 400 || fault) && AUTH_RX.containsMatchIn(text))
    }

    private val cache = ConcurrentHashMap<String, Session>()

    /**
     * Cameras that refused the user name / password lately: sign-in signature → until when (elapsedRealtime).
     * While a camera is in here, PTZ and pictures are not even tried, so a wrong password never gets this device
     * locked out of the camera (Hikvision and Dahua lock a device out after a few failed sign-ins).
     */
    private val refused = ConcurrentHashMap<String, Long>()

    // ------------------------------------------------------------------ public API

    /** Turn the camera: x = left (-1) … right (1), y = down (-1) … up (1), zoom = out (-1) … in (1). Keeps moving until [ptzStop]. */
    fun ptzMove(cam: Camera, x: Float, y: Float, zoom: Float = 0f): Boolean {
        if (refusedFor(cam) > 0) return false
        return try {
            withSession(cam) { s ->
                val token = s.ptzProfile ?: throw Fail()
                val v = StringBuilder()
                if (x != 0f || y != 0f || zoom == 0f) {
                    v.append("<PanTilt x=\"").append(num(x)).append("\" y=\"").append(num(y)).append("\" xmlns=\"$TT\"/>")
                }
                if (zoom != 0f) v.append("<Zoom x=\"").append(num(zoom)).append("\" xmlns=\"$TT\"/>")
                soap(
                    s.ptz, "$PTZ/ContinuousMove",
                    "<ContinuousMove xmlns=\"$PTZ\"><ProfileToken>${esc(token)}</ProfileToken><Velocity>$v</Velocity></ContinuousMove>",
                    s.auth,
                )
                true
            }
        } catch (e: Throwable) {
            failed(cam, e)
            false
        }
    }

    /** Stop turning and zooming. */
    fun ptzStop(cam: Camera): Boolean {
        if (refusedFor(cam) > 0) return false
        return try {
            withSession(cam) { s ->
                val token = esc(s.ptzProfile ?: throw Fail())
                try {
                    soap(
                        s.ptz, "$PTZ/Stop",
                        "<Stop xmlns=\"$PTZ\"><ProfileToken>$token</ProfileToken><PanTilt>true</PanTilt><Zoom>true</Zoom></Stop>",
                        s.auth,
                    )
                } catch (e: Fail) {
                    if (e.auth) throw e
                    // cameras without zoom may refuse the Zoom part; a Stop with no parts stops everything
                    soap(s.ptz, "$PTZ/Stop", "<Stop xmlns=\"$PTZ\"><ProfileToken>$token</ProfileToken></Stop>", s.auth)
                }
                true
            }
        } catch (e: Throwable) {
            failed(cam, e)
            false
        }
    }

    /** A still picture from the camera (at most 1280 px wide), or null. */
    fun snapshot(cam: Camera): Bitmap? {
        if (refusedFor(cam) > 0) return null
        val (ou, op) = creds(cam)
        // the ONVIF user first, then the camera's own (Hikvision keeps ONVIF users apart); a refused pair is not tried again
        var pairs = listOf(Pair(ou, op), Pair(cam.user, cam.pass)).distinct()
        // a Hikvision password its own web language (ISAPI) refused a moment ago is not tried again here
        if (cam.brand == Brand.HIKVISION && Hik.refused(cam)) pairs = pairs.filter { it != Pair(cam.user, cam.pass) }
        if (pairs.isEmpty()) return null
        var wasRefused = false
        try {
            val uri = withSession(cam) { s ->
                s.snapUri ?: run {
                    val token = s.profile ?: throw Fail()
                    val x = soap(
                        s.media, "$MEDIA/GetSnapshotUri",
                        "<GetSnapshotUri xmlns=\"$MEDIA\"><ProfileToken>${esc(token)}</ProfileToken></GetSnapshotUri>",
                        s.auth,
                    )
                    val u = fix(tag(x, "Uri"), s.host, s.fixPort) ?: throw Fail()
                    s.snapUri = u
                    u
                }
            }
            try {
                image(uri, pairs)?.let { return it }
            } catch (e: Fail) {
                if (e.auth) {
                    // the picture address refused every user name / password
                    wasRefused = true
                    pairs = emptyList()
                }
            }
        } catch (e: Fail) {
            if (e.auth) {
                // ONVIF refused its own user name / password: only the camera's own one is left to try
                wasRefused = true
                pairs = pairs.drop(1)
            }
        } catch (_: Throwable) {
        }
        // no picture through ONVIF: the make's own picture address (on the camera's web port, at the address that answered)
        val s = cache[cam.id]
        val t = if (s == null) Route.targets(cam).firstOrNull() else null
        val host = s?.host ?: t?.host ?: ""
        if (pairs.isNotEmpty() && host.isNotEmpty()) {
            val hp = hostPort(host, s?.httpPort ?: t?.httpPort ?: cam.onvifPortOrDefault())
            val url = when (cam.brand) {
                Brand.HIKVISION -> "http://$hp/ISAPI/Streaming/channels/${cam.channel}01/picture"
                Brand.DAHUA -> "http://$hp/cgi-bin/snapshot.cgi?channel=${cam.channel}"
                else -> null
            }
            if (url != null) {
                try {
                    image(url, pairs.reversed())?.let { return it }
                } catch (e: Fail) {
                    if (e.auth) wasRefused = true
                } catch (_: Throwable) {
                }
            }
        }
        if (wasRefused) refuse(cam)
        return null
    }

    /** Like [watchEvents], plain motion only: [onMotion] gets true when motion starts and false when it ends. */
    fun watchMotion(cam: Camera, stop: () -> Boolean, onMotion: (Boolean) -> Unit) =
        watchEvents(cam, stop) { on, kind -> if (kind == "motion") onMotion(on) }

    /**
     * Listens to the camera's events until [stop] says so; [onEvent] gets true when something starts and false when
     * it ends, with its kind: "human" / "vehicle" when the camera's topic says so (person / vehicle detection),
     * "line" (line crossing), "intrusion", otherwise "motion". Reconnects by itself (5 s, then longer up to 60 s).
     * After a wrong password it waits 10 minutes, then 30, then every hour, so the camera does not lock this device
     * out. Never throws. Blocks: run it on its own thread.
     */
    fun watchEvents(cam: Camera, stop: () -> Boolean, onEvent: (on: Boolean, kind: String) -> Unit) {
        var wait = 5_000L
        var refusals = 0
        while (!stop()) {
            // a PTZ press or a picture of this camera was refused a moment ago: no new sign-in before that pause ends
            val blocked = refusedFor(cam)
            if (blocked > 0) {
                nap(blocked, stop)
                continue
            }
            val worked = AtomicBoolean(false)
            var authFail = false
            try {
                listen(cam, stop, onEvent, worked)
            } catch (e: Fail) {
                authFail = e.auth
            } catch (_: Throwable) {
            }
            if (stop()) break
            forget(cam)
            if (worked.get()) {
                wait = 5_000L
                refusals = 0
            }
            if (authFail && !worked.get()) {
                // a wrong password: wait longer each time (PTZ and pictures of this camera wait too)
                refusals++
                refuse(cam)
                nap(
                    when (refusals) {
                        1 -> 10 * 60_000L
                        2 -> 30 * 60_000L
                        else -> 60 * 60_000L
                    },
                    stop,
                )
            } else {
                // refused after it had worked = a clock jump or a camera restart (the clock is read again): retry soon
                nap(wait, stop)
                if (!worked.get()) wait = minOf(wait * 2, 60_000L)
            }
        }
    }

    // ------------------------------------------------------------------ refused passwords

    /** The camera's sign-in (address, port, user name, password): the channels of one recorder share it. */
    private fun login(cam: Camera): String {
        val (u, p) = creds(cam)
        return listOf(cam.address(), cam.onvifPortOrDefault().toString(), u, p).joinToString("\u0001")
    }

    /** ms left of the pause after a refused password (0 = it may be tried). */
    private fun refusedFor(cam: Camera): Long {
        val key = login(cam)
        val until = refused[key] ?: return 0L
        val left = until - SystemClock.elapsedRealtime()
        if (left > 0) return left
        refused.remove(key, until)
        return 0L
    }

    private fun refuse(cam: Camera) {
        refused[login(cam)] = SystemClock.elapsedRealtime() + REFUSED_MS
    }

    /** After a failed PTZ call: learn the camera again next time; a refused password pauses this camera's sign-ins. */
    private fun failed(cam: Camera, e: Throwable) {
        forget(cam)
        if (e is Fail && e.auth) refuse(cam)
    }

    // ------------------------------------------------------------------ motion events (PullPoint)

    private fun listen(cam: Camera, stop: () -> Boolean, onEvent: (Boolean, String) -> Unit, worked: AtomicBoolean) {
        val s = session(cam)
        var (sub, life) = subscribe(s)
        var expires = SystemClock.elapsedRealtime() + life
        try {
            while (!stop()) {
                if (expires - SystemClock.elapsedRealtime() < 20_000L) {
                    val more = renew(s, sub)
                    if (more != null) {
                        expires = SystemClock.elapsedRealtime() + more
                    } else {
                        // this camera cannot renew: a new subscription straight away
                        unsubscribe(s, sub)
                        val next = subscribe(s)
                        sub = next.first
                        expires = SystemClock.elapsedRealtime() + next.second
                    }
                }
                val x = soap(
                    sub.url, A_PULL,
                    "<PullMessages xmlns=\"$EVENTS\"><Timeout>PT10S</Timeout><MessageLimit>10</MessageLimit></PullMessages>",
                    s.auth, PULL_READ_MS, subHeader(sub, A_PULL), sub.ns,
                )
                worked.set(true)
                // some cameras extend the subscription with every pull
                lifeOrNull(x)?.let { expires = maxOf(expires, SystemClock.elapsedRealtime() + it) }
                for ((on, kind) in events(x, s)) {
                    if (stop()) break
                    onEvent(on, kind)
                }
            }
        } finally {
            unsubscribe(s, sub)
        }
    }

    private fun subscribe(s: Session): Pair<Sub, Long> {
        val x = soap(
            s.events, A_CREATE,
            "<CreatePullPointSubscription xmlns=\"$EVENTS\"><InitialTerminationTime>PT60S</InitialTerminationTime></CreatePullPointSubscription>",
            s.auth,
        )
        val ref = inner(x, "SubscriptionReference") ?: throw Fail()
        val addr = tag(ref, "Address") ?: throw Fail()
        val refs = inner(ref, "ReferenceParameters")?.trim() ?: ""
        val ns = if (refs.isEmpty()) "" else namespaces(x, refs)
        return Pair(Sub(fix(addr, s.host, s.fixPort) ?: addr, addr, refs, ns), lifeOrNull(x) ?: 60_000L)
    }

    /** New lifetime (ms) after a Renew, or null when the camera cannot renew. */
    private fun renew(s: Session, sub: Sub): Long? = try {
        val x = soap(
            sub.url, A_RENEW, "<Renew xmlns=\"$WSN\"><TerminationTime>PT60S</TerminationTime></Renew>",
            s.auth, READ_MS, subHeader(sub, A_RENEW), sub.ns,
        )
        lifeOrNull(x) ?: 60_000L
    } catch (e: Fail) {
        if (e.auth) throw e
        null
    }

    private fun unsubscribe(s: Session, sub: Sub) {
        try {
            soap(sub.url, A_UNSUB, "<Unsubscribe xmlns=\"$WSN\"/>", s.auth, 3_000, subHeader(sub, A_UNSUB), sub.ns)
        } catch (_: Throwable) {
        }
    }

    /** WS-Addressing headers some cameras need on the subscription, plus the camera's own reference parameters. */
    private fun subHeader(sub: Sub, action: String): String =
        "<wsa:Action xmlns:wsa=\"$WSA\">$action</wsa:Action><wsa:To xmlns:wsa=\"$WSA\">${esc(sub.to)}</wsa:To>${sub.refs}"

    /** Namespace declarations the copied reference parameters need (declared higher up in the camera's answer). */
    private fun namespaces(xml: String, refs: String): String {
        val used = LinkedHashSet<String>()
        for (m in PREFIX_RX.findAll(refs)) used.add(m.groups[1]?.value ?: m.groupValues[2])
        val sb = StringBuilder()
        for (p in used) {
            if (p.isEmpty() || p == "s" || p == "xml" || p == "xmlns") continue
            if (refs.contains("xmlns:$p=")) continue
            val uri = Regex("xmlns:${Regex.escape(p)}\\s*=\\s*\"([^\"]*)\"").find(xml)?.groupValues?.get(1) ?: continue
            sb.append(" xmlns:").append(p).append("=\"").append(uri).append('"')
        }
        return sb.toString()
    }

    /** Events on / off with their kind, in the order the camera reported them. */
    private fun events(xml: String, s: Session): List<Pair<Boolean, String>> {
        val out = ArrayList<Pair<Boolean, String>>()
        for (m in NOTE_RX.findAll(xml)) {
            val b = m.value
            val topic = tag(b, "Topic") ?: continue
            val data = items(inner(b, "Data") ?: b)
            val kind = kind(topic, data) ?: continue
            if (s.others.isNotEmpty()) {
                // a recorder: only this camera's channel
                val src = items(inner(b, "Source") ?: "").values
                if (src.any { it in s.others } && src.none { it in s.mine }) continue
            }
            val v = data["ismotion"] ?: data["state"] ?: data["value"]
                ?: data.entries.firstOrNull { it.key.startsWith("is") }?.value
                ?: (if (kind == "line") "true" else null) // a line crossing is a moment: it carries only the object's number
                ?: continue
            val on = v.equals("true", ignoreCase = true) || v == "1"
            // "Initialized" = the state at the moment of subscribing, not a new movement
            if (on && INITIAL_RX.containsMatchIn(b)) continue
            out.add(Pair(on, kind))
        }
        return out
    }

    /** What an event topic reports: human, vehicle, line, intrusion, motion; null = not a detection (tamper, …). */
    private fun kind(topic: String, data: Map<String, String>): String? {
        val cls = listOf("classtypes", "class", "objecttype", "type").firstNotNullOfOrNull { data[it] } ?: ""
        return when {
            HUMAN_TOPIC_RX.containsMatchIn(topic) || "ispeople" in data || "ishuman" in data -> "human"
            VEHICLE_TOPIC_RX.containsMatchIn(topic) || "isvehicle" in data -> "vehicle"
            LINE_TOPIC_RX.containsMatchIn(topic) -> "line"
            FIELD_TOPIC_RX.containsMatchIn(topic) -> "intrusion"
            topic.contains("ObjectDetection", ignoreCase = true) && HUMAN_TOPIC_RX.containsMatchIn(cls) -> "human"
            topic.contains("ObjectDetection", ignoreCase = true) && VEHICLE_TOPIC_RX.containsMatchIn(cls) -> "vehicle"
            MOTION_RX.containsMatchIn(topic) -> "motion"
            else -> null
        }
    }

    /** SimpleItem Name → Value (names in lower case). */
    private fun items(xml: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in ITEM_RX.findAll(xml)) {
            val a = m.groupValues[1]
            val n = attr(a, "Name") ?: continue
            out[n.lowercase(Locale.US)] = attr(a, "Value") ?: continue
        }
        return out
    }

    /** TerminationTime − CurrentTime of an answer (both on the camera's clock), in ms. */
    private fun lifeOrNull(xml: String): Long? {
        val cur = tag(xml, "CurrentTime")?.let { time(it) } ?: return null
        val end = tag(xml, "TerminationTime")?.let { time(it) } ?: return null
        if (end <= cur) return null
        return minOf(end - cur, 10 * 60_000L)
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

    // ------------------------------------------------------------------ what the camera offers (cached)

    private fun creds(cam: Camera): Pair<String, String> {
        if (cam.brand == Brand.CUSTOM && cam.onvifUser.isEmpty()) {
            // a full RTSP link usually carries the user and password itself
            val info = try { Uri.parse(cam.url.trim()).userInfo } catch (_: Exception) { null }
            if (!info.isNullOrEmpty()) return Pair(info.substringBefore(':'), info.substringAfter(':', ""))
        }
        return Pair(cam.onvifUserOrDefault(), cam.onvifPassOrDefault())
    }

    private fun sig(cam: Camera): String {
        val (u, p) = creds(cam)
        return listOf(
            cam.brand.key, cam.address(), cam.onvifPortOrDefault().toString(), cam.channel.toString(), u, p,
            cam.remoteHost.trim(), cam.remoteHttp.toString(),
        ).joinToString("\u0001")
    }

    private fun forget(cam: Camera) {
        cache.remove(cam.id)
    }

    private fun session(cam: Camera): Session {
        val key = sig(cam)
        val old = cache[cam.id]
        if (old != null && old.sig == key) return old
        val s = build(cam, key)
        cache[cam.id] = s
        return s
    }

    /**
     * Runs [block] with the camera's details; when remembered details fail, learns them again once. A refused
     * sign-in is tried again only when the camera's clock moved (a restart or a time update): otherwise the password
     * is wrong, and one press costs one failed sign-in, never a series.
     */
    private fun <T> withSession(cam: Camera, block: (Session) -> T): T {
        val key = sig(cam)
        val cached = cache[cam.id]?.takeIf { it.sig == key }
        if (cached != null) {
            try {
                return block(cached)
            } catch (e: Fail) {
                cache.remove(cam.id)
                if (e.auth && !clockMoved(cached)) throw e
            }
        }
        val s = build(cam, key)
        cache[cam.id] = s
        try {
            return block(s)
        } catch (e: Fail) {
            cache.remove(cam.id)
            throw e
        }
    }

    /** The camera's clock is now more than 5 s away from the one this session signs with (asked without a password). */
    private fun clockMoved(s: Session): Boolean = try {
        val now = clockOffset(s.device)
        now != null && abs(now - s.auth.offset) > 5_000L
    } catch (_: Throwable) {
        false
    }

    private fun deviceUrl(host: String, port: Int): String = "http://${hostPort(host, port)}/onvif/device_service"

    /**
     * Learns the camera at its addresses in [Route] order (home first on its own network, else the outside one):
     * the next address is tried when one cannot be reached or refuses (it may be another device on another network).
     */
    private fun build(cam: Camera, key: String): Session {
        if (cam.address().isEmpty()) throw Fail()
        var refusedErr: Fail? = null
        var last = Fail()
        for (t in Route.targets(cam)) {
            if (t.host.isEmpty()) continue
            try {
                val s = buildAt(cam, key, t)
                Route.worked(cam, t.remote)
                return s
            } catch (e: Fail) {
                if (e.auth) refusedErr = e else last = e
            }
        }
        throw refusedErr ?: last
    }

    private fun buildAt(cam: Camera, key: String, t: Route.Target): Session {
        val host = t.host
        // from outside, every service of the camera is reached on the forwarded web port
        val fixPort = if (t.remote) t.httpPort else -1
        val (user, pass) = creds(cam)
        val auth = Auth(user, pass)
        val device = deviceUrl(host, t.httpPort)
        // Hikvision refuses a sign-in whose time is far from its own clock: sign with the camera's time
        auth.offset = clockOffset(device) ?: 0L

        var media: String? = null
        var ptz: String? = null
        var events: String? = null
        try {
            val caps = soap(device, "$DEV/GetCapabilities", "<GetCapabilities xmlns=\"$DEV\"><Category>All</Category></GetCapabilities>", auth)
            media = xaddr(caps, "Media")
            ptz = xaddr(caps, "PTZ")
            events = xaddr(caps, "Events")
        } catch (e: Fail) {
            if (e.auth) throw e
        }
        if (media == null || events == null) {
            try {
                val sv = soap(device, "$DEV/GetServices", "<GetServices xmlns=\"$DEV\"><IncludeCapability>false</IncludeCapability></GetServices>", auth)
                if (media == null) media = service(sv, "/ver10/media/wsdl")
                if (ptz == null) ptz = service(sv, "/ver20/ptz/wsdl")
                if (events == null) events = service(sv, "/ver10/events/wsdl")
            } catch (e: Fail) {
                if (e.auth) throw e
            }
        }
        val mediaUrl = fix(media, host, fixPort) ?: device
        var profiles: List<Profile> = emptyList()
        try {
            profiles = parseProfiles(soap(mediaUrl, "$MEDIA/GetProfiles", "<GetProfiles xmlns=\"$MEDIA\"/>", auth))
        } catch (e: Fail) {
            if (e.auth) throw e
        }
        if (media == null && events == null && profiles.isEmpty()) throw Fail()

        // a recorder lists several cameras (video sources): channel n = the n-th source
        val sources = profiles.map { it.source }.filter { it.isNotEmpty() }.distinct()
        val src = if (sources.size > 1) sources.getOrElse(cam.channel - 1) { sources[0] } else sources.firstOrNull()
        val minePs = profiles.filter { src == null || it.source == src || it.source.isEmpty() }
        val profile = (minePs.firstOrNull() ?: profiles.firstOrNull())?.token
        val ptzProfile = (minePs.firstOrNull { it.ptz } ?: minePs.firstOrNull() ?: profiles.firstOrNull())?.token
        val mine = HashSet<String>()
        val others = HashSet<String>()
        if (sources.size > 1) {
            for (p in profiles) {
                val set = if (p.source == src) mine else others
                if (p.source.isNotEmpty()) set.add(p.source)
                if (p.vsc.isNotEmpty()) set.add(p.vsc)
            }
            others.removeAll(mine)
        }
        return Session(
            key, host, t.httpPort, fixPort, device, auth, mediaUrl, fix(ptz, host, fixPort) ?: mediaUrl,
            fix(events, host, fixPort) ?: device, profile, ptzProfile, mine, others,
        )
    }

    /** The camera's clock minus ours (asked without a password, as ONVIF allows); null when the camera does not say. */
    private fun clockOffset(device: String): Long? {
        val r = post(device, "$DEV/GetSystemDateAndTime", envelope("", "<GetSystemDateAndTime xmlns=\"$DEV\"/>"), null, READ_MS)
        if (!r.ok) return null
        val utc = inner(r.text, "UTCDateTime") ?: return null
        val y = tag(utc, "Year")?.toIntOrNull() ?: return null
        val mo = tag(utc, "Month")?.toIntOrNull() ?: return null
        val d = tag(utc, "Day")?.toIntOrNull() ?: return null
        val h = tag(utc, "Hour")?.toIntOrNull() ?: return null
        val mi = tag(utc, "Minute")?.toIntOrNull() ?: return null
        val sec = tag(utc, "Second")?.toIntOrNull() ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(y, mo - 1, d, h, mi, sec)
        return cal.timeInMillis - System.currentTimeMillis()
    }

    private fun parseProfiles(xml: String): List<Profile> {
        val out = ArrayList<Profile>()
        for (m in PROFILE_RX.findAll(xml)) {
            val token = attr(m.groupValues[1], "token") ?: continue
            val body = m.groupValues[2]
            val vsc = VSC_RX.find(body)
            out.add(
                Profile(
                    token,
                    vsc?.let { tag(it.groupValues[2], "SourceToken") } ?: "",
                    vsc?.let { attr(it.groupValues[1], "token") } ?: "",
                    PTZ_CONF_RX.containsMatchIn(body),
                ),
            )
        }
        return out
    }

    /** XAddr of one category in GetCapabilities (it is the category's first child). */
    private fun xaddr(xml: String, cat: String): String? =
        Regex("<(?:[\\w.-]+:)?$cat\\b[^>]*>\\s*<(?:[\\w.-]+:)?XAddr\\b[^>]*>\\s*([^<\\s]+)")
            .find(xml)?.groupValues?.get(1)?.let { unesc(it) }

    /** XAddr of one service in GetServices, by the end of its namespace. */
    private fun service(xml: String, ns: String): String? {
        for (m in SERVICE_RX.findAll(xml)) {
            val b = m.groupValues[1]
            if (tag(b, "Namespace")?.endsWith(ns) == true) return tag(b, "XAddr")
        }
        return null
    }

    // ------------------------------------------------------------------ SOAP and sign-in

    /** One ONVIF call; returns the answer or throws [Fail]. WS-Security first; HTTP Digest when the camera wants it. */
    private fun soap(
        url: String,
        action: String,
        body: String,
        auth: Auth?,
        readMs: Int = READ_MS,
        header: String = "",
        headerNs: String = "",
    ): String {
        if (auth == null || auth.user.isEmpty()) {
            val r = post(url, action, envelope(header, body, headerNs), null, readMs)
            if (r.ok) return r.text
            throw Fail(r.authFailed)
        }
        // a Digest challenge remembered from an earlier call may have expired; one received during this call may not
        val remembered = auth.digest && auth.challenge != null
        if (!auth.digest) {
            val r = post(url, action, envelope(security(auth) + header, body, headerNs), null, readMs)
            if (r.ok) return r.text
            if (!r.authFailed) throw Fail()
            auth.challenge = Digest.challenge(r.challenges)
        }
        // HTTP Digest (RFC 2617): some Dahua firmwares refuse WS-Security
        val xml = envelope(header, body, headerNs)
        var ch = auth.challenge
        if (ch == null) {
            val r = post(url, action, xml, null, readMs)
            if (r.ok) return r.text
            ch = Digest.challenge(r.challenges) ?: throw Fail(true)
        }
        var r = post(url, action, xml, Digest.header(ch, "POST", url, auth.user, auth.pass), readMs)
        if (r.code == 401) {
            // a remembered one-time number expired: answer the camera's new challenge once
            // (a number received just now, or the same number again = wrong password: no second try, so the camera
            // does not lock this device out)
            val fresh = Digest.challenge(r.challenges)
            if (!remembered || fresh == null || fresh.nonce == ch.nonce) {
                auth.digest = false
                auth.challenge = null
                throw Fail(true)
            }
            ch = fresh
            r = post(url, action, xml, Digest.header(ch, "POST", url, auth.user, auth.pass), readMs)
        }
        if (r.ok) {
            auth.digest = true
            auth.challenge = ch
            return r.text
        }
        if (r.authFailed) {
            auth.digest = false
            auth.challenge = null
            throw Fail(true)
        }
        throw Fail()
    }

    private fun envelope(header: String, body: String, headerNs: String = ""): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"$SOAP\">" +
            (if (header.isEmpty()) "" else "<s:Header$headerNs>$header</s:Header>") +
            "<s:Body xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">" +
            body + "</s:Body></s:Envelope>"

    /** WS-Security UsernameToken: PasswordDigest = Base64(SHA1(nonce + created + password)), created on the camera's clock. */
    private fun security(a: Auth): String {
        val nonce = ByteArray(16).also { rnd.nextBytes(it) }
        val created = stamp(System.currentTimeMillis() + a.offset)
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(nonce)
        sha.update(created.toByteArray(Charsets.UTF_8))
        sha.update(a.pass.toByteArray(Charsets.UTF_8))
        val digest = Base64.encodeToString(sha.digest(), Base64.NO_WRAP)
        return "<wsse:Security s:mustUnderstand=\"1\" xmlns:wsse=\"$WSSE\" xmlns:wsu=\"$WSU\"><wsse:UsernameToken>" +
            "<wsse:Username>${esc(a.user)}</wsse:Username>" +
            "<wsse:Password Type=\"$PW_DIGEST\">$digest</wsse:Password>" +
            "<wsse:Nonce EncodingType=\"$BASE64\">${Base64.encodeToString(nonce, Base64.NO_WRAP)}</wsse:Nonce>" +
            "<wsu:Created>$created</wsu:Created></wsse:UsernameToken></wsse:Security>"
    }

    private fun stamp(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    // ------------------------------------------------------------------ HTTP

    private fun post(url: String, action: String, xml: String, authHeader: String?, readMs: Int): Resp =
        http(url, "POST", xml.toByteArray(Charsets.UTF_8), "application/soap+xml; charset=utf-8; action=\"$action\"", authHeader, readMs)

    /** One HTTP request; any network problem throws [Fail]. */
    private fun http(url: String, method: String, body: ByteArray?, type: String?, authHeader: String?, readMs: Int): Resp {
        var conn: HttpURLConnection? = null
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = CONNECT_MS
            c.readTimeout = readMs
            c.useCaches = false
            c.requestMethod = method
            c.setRequestProperty("Connection", "close")
            if (authHeader != null) c.setRequestProperty("Authorization", authHeader)
            if (body != null) {
                c.doOutput = true
                if (type != null) c.setRequestProperty("Content-Type", type)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val input: InputStream? = if (code >= 400) c.errorStream else c.inputStream
            val data = input?.use { read(it) } ?: ByteArray(0)
            val challenges = ArrayList<String>()
            val fields = c.headerFields
            if (fields != null) {
                for ((k, v) in fields) {
                    if (k != null && v != null && k.equals("WWW-Authenticate", ignoreCase = true)) challenges.addAll(v)
                }
            }
            return Resp(code, data, challenges)
        } catch (e: Fail) {
            throw e
        } catch (_: Throwable) {
            throw Fail()
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
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

    /**
     * Downloads a picture, answering a Basic or Digest password request; null on failure.
     * Throws a refused [Fail] when every user name / password given was refused.
     */
    private fun image(url: String, creds: List<Pair<String, String>>): Bitmap? {
        var r = http(url, "GET", null, null, null, READ_MS)
        var tried = false
        if (r.code == 401) {
            for ((u, p) in creds) {
                if (u.isEmpty()) continue
                val ch = Digest.challenge(r.challenges)
                val header = if (ch != null) Digest.header(ch, "GET", url, u, p) else Digest.basic(u, p)
                r = http(url, "GET", null, null, header, READ_MS)
                tried = true
                if (r.code != 401) break
            }
        }
        if (r.code == 401 && tried) throw Fail(true)
        if (r.code !in 200..299 || r.data.isEmpty()) return null
        return decode(r.data)
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

    /** The address a camera gave for one of its services, pointed at the address we reach it on. */
    private fun fix(u: String?, host: String, port: Int): String? {
        val t = u?.trim()?.split(SPACES_RX)?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val x = URL(t)
            // [port] -1 = the camera's own port (at home); from outside the forwarded one
            URL(x.protocol, host, if (port > 0) port else x.port, x.file).toString()
        } catch (_: Exception) {
            null
        }
    }

    /** Text of the first element with this local name (any prefix; attributes may hold URLs, so only "/>" is excluded). */
    private fun tag(xml: String, name: String): String? =
        Regex("<(?:[\\w.-]+:)?$name\\b[^>]*(?<!/)>([^<]*)</(?:[\\w.-]+:)?$name\\s*>")
            .find(xml)?.groupValues?.get(1)?.let { unesc(it).trim() }

    /** Inner XML of the first element with this local name (any prefix). */
    private fun inner(xml: String, name: String): String? =
        Regex("<(?:[\\w.-]+:)?$name\\b[^>]*(?<!/)>(.*?)</(?:[\\w.-]+:)?$name\\s*>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)

    /** Value of an attribute in a start tag's attribute text. */
    private fun attr(attrs: String, name: String): String? {
        val m = Regex("(?:^|\\s)(?:[\\w.-]+:)?$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").find(attrs) ?: return null
        return unesc(m.groups[1]?.value ?: m.groupValues[2])
    }

    /** xsd:dateTime → ms since 1970 (UTC). */
    private fun time(s: String): Long? {
        val g = TIME_RX.find(s.trim())?.groupValues ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        var ms = cal.timeInMillis
        val z = g[7]
        if (z.isNotEmpty() && z != "Z") {
            val sign = if (z[0] == '-') -1 else 1
            val d = z.substring(1).replace(":", "")
            val hh = d.take(2).toIntOrNull() ?: 0
            val mm = d.drop(2).toIntOrNull() ?: 0
            ms -= sign * (hh * 3_600_000L + mm * 60_000L)
        }
        return ms
    }

    private fun num(v: Float): String = String.format(Locale.US, "%.2f", v.coerceIn(-1f, 1f))

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun unesc(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

}

/**
 * HTTP sign-in shared by ONVIF and Hikvision ISAPI: Digest (RFC 2617 / 7616, MD5 or SHA-256, qop=auth) and Basic.
 * The password itself is never sent with Digest, only a hash bound to the camera's one-time number.
 */
internal object Digest {
    private val PARAM_RX = Regex("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]*))")
    private val rnd = SecureRandom()

    /** An HTTP Digest challenge (WWW-Authenticate: Digest …); [nc] counts the answers given to it. */
    class Challenge(val realm: String, val nonce: String, val qop: String?, val opaque: String?, val algorithm: String?) {
        val nc = AtomicInteger(0)
    }

    /** The best Digest challenge among the WWW-Authenticate headers (MD5 first), or null. */
    fun challenge(list: List<String>): Challenge? {
        val all = list.map { it.trim() }.filter { it.startsWith("Digest", ignoreCase = true) }.mapNotNull { parse(it.substring(6)) }
        return all.firstOrNull { it.algorithm == null || it.algorithm.startsWith("MD5", ignoreCase = true) } ?: all.firstOrNull()
    }

    /** The device also accepts Basic (old firmware). */
    fun offersBasic(list: List<String>): Boolean = list.any { it.trim().startsWith("Basic", ignoreCase = true) }

    private fun parse(s: String): Challenge? {
        val m = HashMap<String, String>()
        for (x in PARAM_RX.findAll(s)) m[x.groupValues[1].lowercase(Locale.US)] = x.groups[2]?.value ?: x.groupValues[3]
        val nonce = m["nonce"] ?: return null
        return Challenge(m["realm"] ?: "", nonce, m["qop"], m["opaque"], m["algorithm"])
    }

    /** The Authorization header answering [ch] for one request (method + the URL's path and query). */
    fun header(ch: Challenge, method: String, url: String, user: String, pass: String): String {
        val uri = try { URL(url).file.ifEmpty { "/" } } catch (_: Exception) { "/" }
        val alg = ch.algorithm ?: "MD5"
        val hash = if (alg.startsWith("SHA-256", ignoreCase = true)) "SHA-256" else "MD5"
        val cnonce = hex(ByteArray(8).also { rnd.nextBytes(it) })
        var ha1 = h(hash, "$user:${ch.realm}:$pass")
        if (alg.endsWith("-sess", ignoreCase = true)) ha1 = h(hash, "$ha1:${ch.nonce}:$cnonce")
        val ha2 = h(hash, "$method:$uri")
        val qopAuth = ch.qop?.split(',')?.any { it.trim().equals("auth", ignoreCase = true) } == true
        val sb = StringBuilder("Digest username=\"").append(quote(user))
            .append("\", realm=\"").append(quote(ch.realm))
            .append("\", nonce=\"").append(ch.nonce)
            .append("\", uri=\"").append(uri).append('"')
        if (qopAuth) {
            val nc = String.format(Locale.US, "%08x", ch.nc.incrementAndGet())
            sb.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce)
                .append("\", response=\"").append(h(hash, "$ha1:${ch.nonce}:$nc:$cnonce:auth:$ha2")).append('"')
        } else {
            sb.append(", response=\"").append(h(hash, "$ha1:${ch.nonce}:$ha2")).append('"')
        }
        if (ch.opaque != null) sb.append(", opaque=\"").append(ch.opaque).append('"')
        if (ch.algorithm != null) sb.append(", algorithm=").append(ch.algorithm)
        return sb.toString()
    }

    fun basic(user: String, pass: String): String =
        "Basic " + Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun quote(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun h(alg: String, s: String): String = hex(MessageDigest.getInstance(alg).digest(s.toByteArray(Charsets.UTF_8)))

    private fun hex(b: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
        return sb.toString()
    }
}
