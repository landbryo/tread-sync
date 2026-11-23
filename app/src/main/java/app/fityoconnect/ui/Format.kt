package app.fityoconnect.ui

import java.util.Locale

fun formatDistance(meters: Double): String {
    val mi = meters / 1609.344
    val km = meters / 1000
    return "%.2f mi (%.2f km)".format(Locale.US, mi, km)
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(Locale.US, s / 3600, (s / 60) % 60, s % 60)
    else "%d:%02d".format(Locale.US, s / 60, s % 60)
}

fun formatSpeed(kmh: Double): String = "%.1f mph (%.1f km/h)".format(Locale.US, kmh / 1.609344, kmh)
