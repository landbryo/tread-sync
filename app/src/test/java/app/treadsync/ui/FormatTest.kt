package app.treadsync.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun imperialAndMetric() {
        assertEquals("1.00 mi", formatDistance(1609.344, miles = true))
        assertEquals("1.61 km", formatDistance(1609.344, miles = false))
        assertEquals("2.5 mph", formatSpeed(4.02336, miles = true))
        assertEquals("4.0 km/h", formatSpeed(4.0, miles = false))
    }

    @Test
    fun durations() {
        assertEquals("0:59", formatDuration(59_999))
        assertEquals("15:00", formatDuration(900_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }

    @Test
    fun autoTitleUsesLocalHour() {
        // 2026-11-20 14:00 UTC is 09:00 at UTC-5.
        val t = 1_795_183_200_000L
        assertEquals("Morning treadmill walk", autoTitle(t, -5 * 3600))
        assertEquals("Afternoon treadmill walk", autoTitle(t, 0))
        assertEquals("Night treadmill walk", autoTitle(t, 9 * 3600))
    }
}
