package com.example.myapplication.data.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt

const val MINUTES_PER_DAY = 1440
private const val SECONDS_PER_DAY = 86400

fun minutesSinceMidnight(instant: Instant, zoneId: ZoneId): Int {
    val zdt = ZonedDateTime.ofInstant(instant, zoneId)
    return zdt.hour * 60 + zdt.minute
}

/** Fraction of the day elapsed (0.0..1.0), including seconds and nanos. Used for smooth "now" pointer. */
fun fractionOfDaySinceMidnight(instant: Instant, zoneId: ZoneId): Float {
    val zdt = ZonedDateTime.ofInstant(instant, zoneId)
    val seconds = zdt.hour * 3600 + zdt.minute * 60 + zdt.second
    val nanos = zdt.nano
    return ((seconds + nanos / 1_000_000_000.0) / SECONDS_PER_DAY).toFloat()
}

fun angleForMinutes(minutesSinceMidnight: Int): Float {
    // Midnight (0:00) at bottom, noon (12:00) at top
    val raw = (minutesSinceMidnight / MINUTES_PER_DAY.toFloat()) * 360f + 90f
    return normalizeAngle0To360(raw)
}

/** Angle for an instant with full precision (seconds, nanos). Use for event end times so arcs end at the real end time. */
fun angleForInstantPrecise(instant: Instant, zoneId: ZoneId): Float {
    val fraction = fractionOfDaySinceMidnight(instant, zoneId)
    val raw = fraction * 360f + 90f
    return normalizeAngle0To360(raw)
}

/** Angle for an instant, using minute precision only. Use for events/tasks. */
fun angleForInstant(instant: Instant, zoneId: ZoneId): Float {
    return angleForMinutes(minutesSinceMidnight(instant, zoneId))
}

/** Angle for the current time including seconds, so the "now" pointer moves every second. */
fun angleForInstantWithSeconds(instant: Instant, zoneId: ZoneId): Float {
    val fraction = fractionOfDaySinceMidnight(instant, zoneId)
    val raw = fraction * 360f - 90f
    return normalizeAngle0To360(raw)
}

fun angleFromTouch(dx: Float, dy: Float): Float {
    val radians = atan2(dy, dx)
    val degrees = (radians * 180f / PI).toFloat()
    return normalizeAngle0To360(degrees)
}

fun normalizeAngle0To360(angle: Float): Float {
    var normalized = angle
    while (normalized < 0f) normalized += 360f
    while (normalized >= 360f) normalized -= 360f
    return normalized
}

fun isAngleWithinArc(angle: Float, start: Float, end: Float): Boolean {
    val a = normalizeAngle0To360(angle)
    val s = normalizeAngle0To360(start)
    val e = normalizeAngle0To360(end)
    return if (s <= e) a in s..e else a >= s || a <= e
}

fun minutesDiff(startMinutes: Int, endMinutes: Int): Int {
    return endMinutes - startMinutes
}

fun clampMinutes(minutes: Int): Int {
    return minutes.coerceIn(0, MINUTES_PER_DAY)
}

fun roundToMinutes(value: Int, step: Int): Int {
    if (step <= 1) return value
    val rounded = (value.toFloat() / step).roundToInt() * step
    return rounded.coerceIn(0, MINUTES_PER_DAY)
}
