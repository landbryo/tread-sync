package app.treadsync.workout

import app.treadsync.ble.TreadmillData
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Turns a stream of treadmill readings into workouts, with no user interaction:
 * a workout starts when the belt moves, pauses when it stops, and finishes once it
 * has been stopped (or the treadmill unreachable) for [autoStopMs].
 */
class WorkoutRecorder(
    private val autoStopMs: () -> Long = { 120_000L },
    private val strideM: () -> Double = { 0.70 },
    private val zoneOffsetSec: (Long) -> Int = { TimeZone.getDefault().getOffset(it) / 1000 },
    private val minActiveMs: Long = 60_000L,
    private val minDistanceM: Double = 20.0,
    private val movingKmh: Double = 0.3,
) {
    enum class Phase { IDLE, ACTIVE, PAUSED }

    data class Snapshot(
        val phase: Phase,
        val speedKmh: Double,
        val distanceM: Double,
        val activeMs: Long,
        val steps: Long,
        val kcal: Int?,
    )

    var phase = Phase.IDLE
        private set
    var deviceName: String = ""

    private var startMs = 0L
    private var lastMs = 0L
    private var lastSpeedMs = 0L
    private var lastMovingMs = 0L
    private var pauseStartMs = 0L
    private var activeMs = 0L
    private var distanceM = 0.0
    private var kcal = 0.0
    private var sawEnergy = false
    private var sawTreadmillDistance = false
    private var maxSpeedMps = 0.0
    private var speedKmh = 0.0
    private val distAcc = Accumulator(maxJump = 50.0)
    private val energyAcc = Accumulator(maxJump = 25.0)
    private val samples = mutableListOf<Sample>()
    private val pauses = mutableListOf<Pause>()

    fun onData(nowMs: Long, d: TreadmillData) {
        val speed = d.speedKmh
        if (phase == Phase.IDLE) {
            if (speed == null || speed < movingKmh) return
            begin(nowMs, d)
        }
        lastMs = nowMs

        d.totalDistanceM?.let {
            sawTreadmillDistance = true
            distanceM += distAcc.delta(it.toDouble())
        }
        d.totalEnergyKcal?.let {
            sawEnergy = true
            kcal += energyAcc.delta(it.toDouble())
        }
        // Treadmills often split one reading across packets and only some carry speed.
        // Measure time between speed-bearing packets so the others don't swallow it.
        if (speed == null) return
        val dt = (nowMs - lastSpeedMs).coerceIn(0L, 5_000L)
        lastSpeedMs = nowMs
        speedKmh = speed
        val moving = speed >= movingKmh
        val mps = speed / 3.6

        when (phase) {
            Phase.ACTIVE -> {
                activeMs += dt
                if (!sawTreadmillDistance) distanceM += mps * dt / 1000.0
                if (moving) {
                    lastMovingMs = nowMs
                    if (mps > maxSpeedMps) maxSpeedMps = mps
                    addSample(nowMs, mps)
                } else {
                    phase = Phase.PAUSED
                    pauseStartMs = nowMs
                    addSample(nowMs, 0.0, force = true)
                }
            }
            Phase.PAUSED -> if (moving) {
                pauses += Pause(pauseStartMs, nowMs)
                phase = Phase.ACTIVE
                lastMovingMs = nowMs
                addSample(nowMs, mps, force = true)
            }
            Phase.IDLE -> Unit
        }
    }

    /** The treadmill connection dropped: treat it as a pause so auto-stop can finish the workout. */
    fun onLinkLost(nowMs: Long) {
        if (phase == Phase.ACTIVE) {
            phase = Phase.PAUSED
            pauseStartMs = lastMs.coerceAtMost(nowMs)
        }
        speedKmh = 0.0
    }

    /** Call about once a second. Returns a finished workout when auto-stop fires. */
    fun tick(nowMs: Long): Workout? {
        val limit = autoStopMs()
        return when (phase) {
            Phase.PAUSED -> if (nowMs - pauseStartMs >= limit) finish() else null
            Phase.ACTIVE -> if (nowMs - lastMs >= limit) finish() else null
            Phase.IDLE -> null
        }
    }

    /** Ends the current workout now. Returns null when nothing (or too little) was recorded. */
    fun finish(): Workout? {
        if (phase == Phase.IDLE) return null
        val stride = strideM()
        val w = Workout(
            id = UUID.randomUUID().toString(),
            startMs = startMs,
            endMs = lastMovingMs,
            zoneOffsetSec = zoneOffsetSec(startMs),
            activeMs = activeMs,
            distanceM = distanceM,
            maxSpeedMps = maxSpeedMps,
            kcal = if (sawEnergy) kcal.roundToLong().toInt() else null,
            steps = if (stride > 0) (distanceM / stride).roundToLong() else null,
            deviceName = deviceName,
            samples = samples.filter { it.tMs <= lastMovingMs },
            pauses = pauses.toList(),
        )
        reset()
        return if (w.activeMs >= minActiveMs && w.distanceM >= minDistanceM) w else null
    }

    fun snapshot(): Snapshot {
        val stride = strideM()
        return Snapshot(
            phase = phase,
            speedKmh = speedKmh,
            distanceM = distanceM,
            activeMs = activeMs,
            steps = if (stride > 0) (distanceM / stride).roundToLong() else 0,
            kcal = if (sawEnergy) kcal.roundToLong().toInt() else null,
        )
    }

    private fun begin(nowMs: Long, d: TreadmillData) {
        reset()
        phase = Phase.ACTIVE
        startMs = nowMs
        lastMs = nowMs
        lastSpeedMs = nowMs
        lastMovingMs = nowMs
        distAcc.reset(d.totalDistanceM?.toDouble())
        energyAcc.reset(d.totalEnergyKcal?.toDouble())
        samples += Sample(nowMs, 0.0, (d.speedKmh ?: 0.0) / 3.6)
    }

    private fun addSample(nowMs: Long, mps: Double, force: Boolean = false) {
        val last = samples.lastOrNull()
        if (!force && last != null && nowMs - last.tMs < 1_000) return
        if (last != null && nowMs <= last.tMs) return
        samples += Sample(nowMs, distanceM, mps)
    }

    private fun reset() {
        phase = Phase.IDLE
        startMs = 0; lastMs = 0; lastSpeedMs = 0; lastMovingMs = 0; pauseStartMs = 0
        activeMs = 0; distanceM = 0.0; kcal = 0.0; maxSpeedMps = 0.0; speedKmh = 0.0
        sawEnergy = false; sawTreadmillDistance = false
        distAcc.reset(null); energyAcc.reset(null)
        samples.clear(); pauses.clear()
    }
}

/**
 * Converts a treadmill's running total into increments, surviving the counter resetting
 * to zero (many walking pads reset when the belt stops) and ignoring implausible jumps.
 */
class Accumulator(private val maxJump: Double) {
    private var last: Double? = null

    fun reset(value: Double?) {
        last = value
    }

    fun delta(value: Double): Double {
        val prev = last
        last = value
        if (prev == null) return 0.0
        val d = value - prev
        return when {
            d < 0 -> if (value <= maxJump) value else 0.0
            d > maxJump -> 0.0
            else -> d
        }
    }
}
