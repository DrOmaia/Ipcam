package com.dromaia.ipcam

import android.content.Context
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/** Prayer times on the device (no internet), with the same calculation as the prayer-times app: Saudi cities by
 *  Umm al-Qura (Fajr 18.5°, Isha = Maghrib + 90 min, 120 in Ramadan), Amman and Irbid by the Jordanian Ministry of
 *  Awqaf (Fajr 18°, Isha 18°). Used to quiet the motion alerts for some minutes from each adhan
 *  (Friday's Dhuhr: at least an hour, for the Jumu'ah sermon and prayer). */
object Prayer {
    class City(
        val key: String, val ar: String, val en: String,
        val lat: Double, val lon: Double, val zone: String = "Asia/Riyadh", val umm: Boolean = true,
    ) {
        fun label(c: Context) = L.t(c, ar, en)
    }

    val CITIES = listOf(
        City("riyadh", "الرياض", "Riyadh", 24.7136, 46.6753),
        City("makkah", "مكة المكرمة", "Makkah", 21.4225, 39.8262),
        City("madinah", "المدينة المنورة", "Madinah", 24.4672, 39.6112),
        City("jeddah", "جدة", "Jeddah", 21.4858, 39.1925),
        City("dammam", "الدمام", "Dammam", 26.4207, 50.0888),
        City("khobar", "الخبر", "Khobar", 26.2172, 50.1971),
        City("ahsa", "الأحساء", "Al-Ahsa", 25.3833, 49.5833),
        City("buraidah", "بريدة", "Buraidah", 26.3260, 43.9750),
        City("taif", "الطائف", "Taif", 21.2703, 40.4158),
        City("abha", "أبها", "Abha", 18.2164, 42.5053),
        City("tabuk", "تبوك", "Tabuk", 28.3835, 36.5662),
        City("hail", "حائل", "Hail", 27.5114, 41.7208),
        City("jazan", "جازان", "Jazan", 16.8892, 42.5511),
        City("najran", "نجران", "Najran", 17.5656, 44.2289),
        City("amman", "عمّان", "Amman", 31.9539, 35.9106, "Asia/Amman", false),
        City("irbid", "إربد", "Irbid", 32.5556, 35.8500, "Asia/Amman", false),
    )

    /** Friday's Dhuhr (Jumu'ah) stays quiet at least this long. */
    const val FRIDAY_MIN = 60

    fun city(c: Context): City = CITIES.firstOrNull { it.key == Prefs.prayerCity(c) } ?: CITIES[0]

    /** Inside the quiet minutes after an adhan now (Settings → Motion alerts → Prayer time). */
    fun quiet(c: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (!Prefs.prayerQuiet(c)) return false
        return try {
            val city = city(c)
            val minutes = Prefs.prayerMin(c)
            val cal = Calendar.getInstance(TimeZone.getTimeZone(city.zone))
            cal.timeInMillis = now
            // today, and yesterday for an Isha whose quiet time goes past midnight
            for (back in 0..1) {
                val day = cal.clone() as Calendar
                day.add(Calendar.DAY_OF_MONTH, -back)
                val friday = day.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
                val at = adhans(city, day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH))
                for (i in at.indices) {
                    val len = if (i == 1 && friday) maxOf(minutes, FRIDAY_MIN) else minutes
                    if (now >= at[i] && now < at[i] + len * 60_000L) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Today's five adhans in the chosen city, «04:52 · 11:41 · 15:05 · 17:22 · 18:52», for the settings. */
    fun today(c: Context): String = try {
        val city = city(c)
        val tz = TimeZone.getTimeZone(city.zone)
        val cal = Calendar.getInstance(tz)
        val at = adhans(city, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        val names = if (L.en(c)) listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha") else listOf("الفجر", "الظهر", "العصر", "المغرب", "العشاء")
        at.indices.joinToString(" · ") { i ->
            val t = Calendar.getInstance(tz)
            t.timeInMillis = at[i]
            names[i] + " " + String.format(Locale.US, "%02d:%02d", t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE))
        }
    } catch (_: Exception) {
        ""
    }

    /** The five adhans of the city's day y-m-d (m 1–12) as moments (ms): Fajr, Dhuhr, Asr, Maghrib, Isha. */
    fun adhans(city: City, y: Int, m: Int, d: Int): LongArray {
        val tz = TimeZone.getTimeZone(city.zone)
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(y, m - 1, d, 0, 0, 0)
        val utcNoon = utc.timeInMillis + 12 * 3_600_000L
        val offH = tz.getOffset(utcNoon) / 3_600_000.0 // the city's clock against UTC that day (summer time included)
        val base = utc.timeInMillis - (offH * 3_600_000).roundToLong() // the city's midnight
        val noonMs = base + 12 * 3_600_000L
        val lat = city.lat
        val jd = julian(y, m, d) - city.lon / (15 * 24)

        fun noon(t: Double) = fixHour(12 - sunPos(jd + t).second)
        fun angleTime(a: Double, t: Double, ccw: Boolean): Double {
            val dc = sunPos(jd + t).first
            val x = (-sin(rad(a)) - sin(rad(dc)) * sin(rad(lat))) / (cos(rad(dc)) * cos(rad(lat)))
            val tt = deg(acos(x)) / 15
            return noon(t) + if (ccw) -tt else tt
        }
        fun asr(t: Double): Double {
            val dc = sunPos(jd + t).first
            return angleTime(-deg(atan(1 / (1 + tan(rad(abs(lat - dc)))))), t, false)
        }

        val adj = offH - city.lon / 15
        var fajr = angleTime(if (city.umm) 18.5 else 18.0, 5.0 / 24, true) + adj
        val sunrise = angleTime(0.833, 6.0 / 24, true) + adj
        val dhuhr = noon(12.0 / 24) + adj
        val asrH = asr(13.0 / 24) + adj
        val maghrib = angleTime(0.833, 18.0 / 24, false) + adj
        var isha = if (city.umm) maghrib + (if (ramadan(tz, noonMs)) 120 else 90) / 60.0 else angleTime(18.0, 18.0 / 24, false) + adj
        if (fajr.isNaN()) fajr = sunrise - 1.5
        if (isha.isNaN()) isha = maghrib + 1.5
        // clock hours of the city's day → moments, to the minute (as the prayer-times app)
        return doubleArrayOf(fajr, dhuhr, asrH, maghrib, isha).map { h -> base + (h * 60).roundToLong() * 60_000L }.toLongArray()
    }

    // Ramadan by the Umm al-Qura calendar (Android 7+ has it built in)
    private fun ramadan(tz: TimeZone, at: Long): Boolean = try {
        val cal = android.icu.util.IslamicCalendar(android.icu.util.TimeZone.getTimeZone(tz.id))
        cal.calculationType = android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
        cal.timeInMillis = at
        cal.get(android.icu.util.Calendar.MONTH) == android.icu.util.IslamicCalendar.RAMADAN
    } catch (_: Throwable) {
        false
    }

    private fun rad(d: Double) = d * Math.PI / 180
    private fun deg(r: Double) = r * 180 / Math.PI
    private fun fixAngle(a: Double) = a - 360 * floor(a / 360)
    private fun fixHour(h: Double) = h - 24 * floor(h / 24)

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    /** The sun's declination (degrees) and the equation of time (hours) on Julian day [jd]. */
    private fun sunPos(jd: Double): Pair<Double, Double> {
        val dd = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * dd)
        val q = fixAngle(280.459 + 0.98564736 * dd)
        val l = fixAngle(q + 1.915 * sin(rad(g)) + 0.020 * sin(rad(2 * g)))
        val e = 23.439 - 0.00000036 * dd
        val ra = deg(atan2(cos(rad(e)) * sin(rad(l)), cos(rad(l)))) / 15
        var eqt = q / 15 - fixHour(ra)
        if (eqt > 12) eqt -= 24
        if (eqt < -12) eqt += 24
        return Pair(deg(asin(sin(rad(e)) * sin(rad(l)))), eqt)
    }
}
