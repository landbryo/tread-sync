package app.fityoconnect.workout

import app.fityoconnect.ble.TreadmillData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutRecorderTest {
    private fun recorder() = WorkoutRecorder(autoStopMs = { 120_000 }, strideM = { 0.7 }, zoneOffsetSec = { 0 })

    @Test
    fun recordsWalkWithPauseAndAutoStops() {
        val r = recorder()
        val t0 = 1_700_000_000_000L
        // Belt idle with an old odometer value: ignored.
        r.onData(t0, TreadmillData(speedKmh = 0.0, totalDistanceM = 500))
        assertEquals(WorkoutRecorder.Phase.IDLE, r.phase)
        // Walk 5 minutes at 3.6 km/h (1 m/s), distance reported by the treadmill.
        for (s in 1..300) r.onData(t0 + s * 1000L, TreadmillData(speedKmh = 3.6, totalDistanceM = 500 + s - 1))
        // Stop for 30 s, then walk 60 s more, with the counter reset to zero (as many pads do).
        r.onData(t0 + 301_000, TreadmillData(speedKmh = 0.0, totalDistanceM = 799))
        assertEquals(WorkoutRecorder.Phase.PAUSED, r.phase)
        for (s in 331..390) r.onData(t0 + s * 1000L, TreadmillData(speedKmh = 3.6, totalDistanceM = s - 331))
        r.onData(t0 + 391_000, TreadmillData(speedKmh = 0.0, totalDistanceM = 59))
        assertNull(r.tick(t0 + 391_000 + 60_000))
        val w = r.tick(t0 + 391_000 + 120_000)
        assertNotNull(w)
        w!!
        assertEquals(299.0 + 59.0, w.distanceM, 0.01)
        assertEquals(1, w.pauses.size)
        assertEquals(t0 + 301_000, w.pauses[0].startMs)
        assertEquals(t0 + 331_000, w.pauses[0].endMs)
        assertEquals(t0 + 1_000, w.startMs)
        assertEquals(t0 + 390_000, w.endMs)
        assertEquals(360_000.0, w.activeMs.toDouble(), 2_000.0)
        assertEquals(Math.round(358 / 0.7), w.steps)
        assertEquals(WorkoutRecorder.Phase.IDLE, r.phase)
    }

    @Test
    fun splitPacketsDoNotLoseTime() {
        // Like FitShow FTMS modules: a speed packet, then a distance-only packet half a second later.
        val r = recorder()
        for (s in 0..900) {
            r.onData(s * 1000L, TreadmillData(speedKmh = 3.6))
            r.onData(s * 1000L + 500, TreadmillData(totalDistanceM = 100 + s))
        }
        val w = r.finish()!!
        assertEquals(900_000L, w.activeMs)
        assertEquals(900.0, w.distanceM, 1.0)
    }

    @Test
    fun integratesSpeedWhenNoDistanceReported() {
        val r = recorder()
        val t0 = 0L
        for (s in 0..120) r.onData(t0 + s * 1000L, TreadmillData(speedKmh = 3.6))
        val w = r.finish()!!
        assertEquals(120.0, w.distanceM, 0.5)
    }

    @Test
    fun tooShortWorkoutIsDropped() {
        val r = recorder()
        for (s in 0..10) r.onData(s * 1000L, TreadmillData(speedKmh = 3.6))
        assertNull(r.finish())
    }

    @Test
    fun linkLossAutoFinishes() {
        val r = recorder()
        for (s in 0..120) r.onData(s * 1000L, TreadmillData(speedKmh = 3.6))
        r.onLinkLost(125_000)
        assertNull(r.tick(200_000))
        assertNotNull(r.tick(240_001))
    }

    @Test
    fun jsonRoundTrip() {
        val r = recorder()
        for (s in 0..120) r.onData(s * 1000L, TreadmillData(speedKmh = 3.6, totalEnergyKcal = s / 10))
        val w = r.finish()!!.copy(stravaActivityId = 123L, hcError = "x")
        assertEquals(w, Workout.fromJson(w.toJson()))
    }
}
