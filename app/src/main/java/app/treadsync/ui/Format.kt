package app.treadsync.ui

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val METERS_PER_MILE = 1609.344

/** Distance as a number in the chosen unit, e.g. "1.44". */
fun distanceValue(meters: Double, miles: Boolean): String =
    "%.2f".format(Locale.US, if (miles) meters / METERS_PER_MILE else meters / 1000)

fun distanceUnit(miles: Boolean) = if (miles) "mi" else "km"

fun formatDistance(meters: Double, miles: Boolean): String = "${distanceValue(meters, miles)} ${distanceUnit(miles)}"

fun speedValue(kmh: Double, miles: Boolean): String =
    "%.1f".format(Locale.US, if (miles) kmh * 1000 / METERS_PER_MILE else kmh)

fun speedUnit(miles: Boolean) = if (miles) "mph" else "km/h"

fun formatSpeed(kmh: Double, miles: Boolean): String = "${speedValue(kmh, miles)} ${speedUnit(miles)}"

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(Locale.US, s / 3600, (s / 60) % 60, s % 60)
    else "%d:%02d".format(Locale.US, s / 60, s % 60)
}

/** "Morning treadmill walk" etc., from the local hour the workout started. */
fun autoTitle(startMs: Long, zoneOffsetSec: Int): String {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = startMs + zoneOffsetSec * 1000L }
    val part = when (cal.get(Calendar.HOUR_OF_DAY)) {
        in 5..10 -> "Morning"
        in 11..16 -> "Afternoon"
        in 17..20 -> "Evening"
        else -> "Night"
    }
    return "$part treadmill walk"
}
