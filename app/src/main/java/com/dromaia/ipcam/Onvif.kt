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
private val DIGEST_PARAM_RX = Regex("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]*))")
private val PREFIX_RX = Regex("</?([\\w.-]+):|\\s([\\w.-]+):[\\w.-]+\\s*=")
private val SPACES_RX = Regex("\\s+")

/**
 * ONVIF: the common language of Hikvision, Dahua and Tapo cameras, spoken over plain HTTP on the home Wi-Fi.
 * Used to turn a camera (PTZ), to take a still picture for a motion notification, and to hear about motion.
 * The user name and password go only to the camera itself. Every call blocks: run it off the main thread.
 */
object Onvif {
    private const val CONNECT_MS = 4_000
    private const val READ_MS = 15_000
    private const val PULL_READ_MS = 15_000 // PullMessages waits up to 10 s on the camera, plus 5 s
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_WIDTH = 1280

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
        @Volatile var challenge: Challenge? = null
    }

    /** An HTTP Digest challenge (WWW-Authenticate: Digest …). */
    private class Challenge(val realm: String, val nonce: String, val qop: String?, val opaque: String?, val algorithm: String?) {
        val nc = AtomicInteger(0)
    }

    private class Profile(val token: String, val source: String, val vsc: String, val ptz: Boolean)

    /** What we learned about one camera (kept until a call fails or the camera's settings change). */
    private class Session(
        val sig: String,
        val host: String,
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

    // ------------------------------------------------------------------ public API

    /** Turn the camera: x = left (-1) … right (1), y = down (-1) … up (1), zoom = out (-1) … in (1). Keeps moving until [ptzStop]. */
    fun ptzMove(cam: Camera, x: Float, y: Float, zoom: Float = 0f): Boolean = try {
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
    } catch (_: Throwable) {
        forget(cam)
        false
    }

    /** Stop turning and zooming. */
    fun ptzStop(cam: Camera): Boolean = try {
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
    } catch (_: Throwable) {
        forget(cam)
        false
    }

    /** A still picture from the camera (at most 1280 px wide), or null. */
    fun snapshot(cam: Camera): Bitmap? {
        val (ou, op) = creds(cam)
        val onvifFirst = listOf(Pair(ou, op), Pair(cam.user, cam.pass)).distinct()
        try {
            val uri = withSession(cam) { s ->
                s.snapUri ?: run {
                    val token = s.profile ?: throw Fail()
                    val x = soap(
                        s.media, "$MEDIA/GetSnapshotUri",
                        "<GetSnapshotUri xmlns=\"$MEDIA\"><ProfileToken>${esc(token)}</ProfileToken></GetSnapshotUri>",
                        s.auth,
                    )
                    val u = fix(tag(x, "Uri"), s.host) ?: throw Fail()
                    s.snapUri = u
                    u
                }
            }
            val bmp = image(uri, onvifFirst)
            if (bmp != null) return bmp
        } catch (_: Throwable) {
        }
        // no picture through ONVIF: the make's own picture address (on the camera's web port)
        return try {
            val host = cam.address()
            if (host.isEmpty()) return null
            val hp = hostPort(host, cam.onvifPortOrDefault())
            val url = when (cam.brand) {
                Brand.HIKVISION -> "http://$hp/ISAPI/Streaming/channels/${cam.channel}01/picture"
                Brand.DAHUA -> "http://$hp/cgi-bin/snapshot.cgi?channel=${cam.channel}"
                else -> return null
            }
            image(url, onvifFirst.reversed())
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Listens to the camera's motion events until [stop] says so; [onMotion] gets true when motion starts and false
     * when it ends. Reconnects by itself (5 s, then longer up to 60 s; 10 minutes after a wrong password so the
     * camera does not lock this device out). Never throws. Blocks: run it on its own thread.
     */
    fun watchMotion(cam: Camera, stop: () -> Boolean, onMotion: (Boolean) -> Unit) {
        var wait = 5_000L
        while (!stop()) {
            val worked = AtomicBoolean(false)
            var authFail = false
            try {
                listen(cam, stop, onMotion, worked)
            } catch (e: Fail) {
                authFail = e.auth
            } catch (_: Throwable) {
            }
            if (stop()) break
            forget(cam)
            if (worked.get()) wait = 5_000L
            // refused after it had worked = a clock jump or a camera restart (the clock is read again): retry soon
            nap(if (authFail && !worked.get()) 10 * 60_000L else wait, stop)
            if (!worked.get()) wait = minOf(wait * 2, 60_000L)
        }
    }

    // ------------------------------------------------------------------ motion events (PullPoint)

    private fun listen(cam: Camera, stop: () -> Boolean, onMotion: (Boolean) -> Unit, worked: AtomicBoolean) {
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
                for (on in motions(x, s)) {
                    if (stop()) break
                    onMotion(on)
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
        return Pair(Sub(fix(addr, s.host) ?: addr, addr, refs, ns), lifeOrNull(x) ?: 60_000L)
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

    /** Motion on / off in the order the camera reported them. */
    private fun motions(xml: String, s: Session): List<Boolean> {
        val out = ArrayList<Boolean>()
        for (m in NOTE_RX.findAll(xml)) {
            val b = m.value
            val topic = tag(b, "Topic") ?: continue
            if (!MOTION_RX.containsMatchIn(topic)) continue
            if (s.others.isNotEmpty()) {
                // a recorder: only this camera's channel
                val src = items(inner(b, "Source") ?: "").values
                if (src.any { it in s.others } && src.none { it in s.mine }) continue
            }
            val data = items(inner(b, "Data") ?: b)
            val v = data["ismotion"] ?: data["state"] ?: data["value"] ?: continue
            val on = v.equals("true", ignoreCase = true) || v == "1"
            // "Initialized" = the state at the moment of subscribing, not a new movement
            if (on && INITIAL_RX.containsMatchIn(b)) continue
            out.add(on)
        }
        return out
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
        return listOf(cam.brand.key, cam.address(), cam.onvifPortOrDefault().toString(), cam.channel.toString(), u, p)
            .joinToString("\u0001")
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
     * Runs [block] with the camera's details; when remembered details fail (also a refused sign-in: the camera
     * restarted or a clock moved, since the password itself did not change), learns them again once.
     */
    private fun <T> withSession(cam: Camera, block: (Session) -> T): T {
        val key = sig(cam)
        val cached = cache[cam.id]?.takeIf { it.sig == key }
        if (cached != null) {
            try {
                return block(cached)
            } catch (_: Fail) {
                cache.remove(cam.id)
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

    private fun build(cam: Camera, key: String): Session {
        val host = cam.address()
        if (host.isEmpty()) throw Fail()
        val (user, pass) = creds(cam)
        val auth = Auth(user, pass)
        val device = "http://${hostPort(host, cam.onvifPortOrDefault())}/onvif/device_service"
        // Hikvision refuses a sign-in whose time is far from its own clock: sign with the camera's time
        auth.offset = clockOffset(device)

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
        val mediaUrl = fix(media, host) ?: device
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
            key, host, auth, mediaUrl, fix(ptz, host) ?: mediaUrl, fix(events, host) ?: device,
            profile, ptzProfile, mine, others,
        )
    }

    /** The camera's clock minus ours (asked without a password, as ONVIF allows). */
    private fun clockOffset(device: String): Long {
        val r = post(device, "$DEV/GetSystemDateAndTime", envelope("", "<GetSystemDateAndTime xmlns=\"$DEV\"/>"), null, READ_MS)
        if (!r.ok) return 0L
        val utc = inner(r.text, "UTCDateTime") ?: return 0L
        val y = tag(utc, "Year")?.toIntOrNull() ?: return 0L
        val mo = tag(utc, "Month")?.toIntOrNull() ?: return 0L
        val d = tag(utc, "Day")?.toIntOrNull() ?: return 0L
        val h = tag(utc, "Hour")?.toIntOrNull() ?: return 0L
        val mi = tag(utc, "Minute")?.toIntOrNull() ?: return 0L
        val sec = tag(utc, "Second")?.toIntOrNull() ?: return 0L
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
        if (!auth.digest) {
            val r = post(url, action, envelope(security(auth) + header, body, headerNs), null, readMs)
            if (r.ok) return r.text
            if (!r.authFailed) throw Fail()
            auth.challenge = digestChallenge(r.challenges)
        }
        // HTTP Digest (RFC 2617): some Dahua firmwares refuse WS-Security
        val xml = envelope(header, body, headerNs)
        var ch = auth.challenge
        if (ch == null) {
            val r = post(url, action, xml, null, readMs)
            if (r.ok) return r.text
            ch = digestChallenge(r.challenges) ?: throw Fail(true)
        }
        var r = post(url, action, xml, digestHeader(ch, "POST", url, auth.user, auth.pass), readMs)
        if (r.code == 401) {
            // the camera's one-time number expired: answer its new challenge once
            // (the same number again = wrong password: no second try, so the camera does not lock this device out)
            val fresh = digestChallenge(r.challenges)
            if (fresh == null || fresh.nonce == ch.nonce) {
                auth.digest = false
                auth.challenge = null
                throw Fail(true)
            }
            ch = fresh
            r = post(url, action, xml, digestHeader(ch, "POST", url, auth.user, auth.pass), readMs)
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

    /** The best Digest challenge among the WWW-Authenticate headers (MD5 first), or null. */
    private fun digestChallenge(list: List<String>): Challenge? {
        val all = list.map { it.trim() }.filter { it.startsWith("Digest", ignoreCase = true) }.mapNotNull { parseDigest(it.substring(6)) }
        return all.firstOrNull { it.algorithm == null || it.algorithm.startsWith("MD5", ignoreCase = true) } ?: all.firstOrNull()
    }

    private fun parseDigest(s: String): Challenge? {
        val m = HashMap<String, String>()
        for (x in DIGEST_PARAM_RX.findAll(s)) m[x.groupValues[1].lowercase(Locale.US)] = x.groups[2]?.value ?: x.groupValues[3]
        val nonce = m["nonce"] ?: return null
        return Challenge(m["realm"] ?: "", nonce, m["qop"], m["opaque"], m["algorithm"])
    }

    private fun digestHeader(ch: Challenge, method: String, url: String, user: String, pass: String): String {
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

    private fun basic(user: String, pass: String): String =
        "Basic " + Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

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

    /** Downloads a picture, answering a Basic or Digest password request; null on failure. */
    private fun image(url: String, creds: List<Pair<String, String>>): Bitmap? {
        var r = http(url, "GET", null, null, null, READ_MS)
        if (r.code == 401) {
            for ((u, p) in creds) {
                if (u.isEmpty()) continue
                val ch = digestChallenge(r.challenges)
                val header = if (ch != null) digestHeader(ch, "GET", url, u, p) else basic(u, p)
                r = http(url, "GET", null, null, header, READ_MS)
                if (r.code != 401) break
            }
        }
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
    private fun fix(u: String?, host: String): String? {
        val t = u?.trim()?.split(SPACES_RX)?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val x = URL(t)
            URL(x.protocol, host, x.port, x.file).toString()
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
