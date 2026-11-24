package app.treadsync.fit

import app.treadsync.workout.Workout
import java.io.ByteArrayOutputStream
import kotlin.math.roundToLong

/**
 * Writes a minimal FIT activity file (file_id, events, records, lap, session, activity)
 * for an indoor treadmill walk. Strava, Garmin Connect and most other services accept it.
 */
object FitEncoder {
    private const val FIT_EPOCH_OFFSET_S = 631_065_600L // 1989-12-31T00:00:00Z

    private const val ENUM = 0x00
    private const val UINT16 = 0x84
    private const val UINT32 = 0x86
    private const val UINT32Z = 0x8C

    private const val SPORT_WALKING = 11
    private const val SUB_SPORT_TREADMILL = 1
    private const val EVENT_TIMER = 0
    private const val EVENT_SESSION = 8
    private const val EVENT_LAP = 9
    private const val EVENT_ACTIVITY = 26
    private const val EVENT_TYPE_START = 0
    private const val EVENT_TYPE_STOP = 1
    private const val EVENT_TYPE_STOP_ALL = 4

    private class Field(val num: Int, val size: Int, val type: Int)

    fun fitTime(epochMs: Long): Long = epochMs / 1000 - FIT_EPOCH_OFFSET_S

    fun encode(w: Workout): ByteArray {
        val out = LeWriter()
        val start = fitTime(w.startMs)
        val end = fitTime(w.endMs)
        val timerMs = w.activeMs
        val elapsedMs = w.elapsedMs.coerceAtLeast(timerMs)
        val dist100 = (w.distanceM * 100).roundToLong()
        val avg = (w.avgSpeedMps * 1000).roundToLong().coerceAtMost(0xFFFE)
        val max = (w.maxSpeedMps * 1000).roundToLong().coerceAtMost(0xFFFE)
        val calories = w.kcal?.toLong()?.coerceIn(0, 0xFFFE) ?: 0xFFFF
        val cycles = w.steps?.let { it / 2 } ?: 0xFFFFFFFFL

        // file_id (global 0)
        out.define(0, 0, listOf(Field(0, 1, ENUM), Field(1, 2, UINT16), Field(2, 2, UINT16), Field(3, 4, UINT32Z), Field(4, 4, UINT32)))
        out.data(0); out.u8(4); out.u16(255); out.u16(1); out.u32(start.coerceAtLeast(1)); out.u32(start)

        // event (global 21) and record (global 20), interleaved in time order
        out.define(1, 21, listOf(Field(253, 4, UINT32), Field(0, 1, ENUM), Field(1, 1, ENUM)))
        out.define(2, 20, listOf(Field(253, 4, UINT32), Field(5, 4, UINT32), Field(6, 2, UINT16)))
        fun event(t: Long, type: Int) {
            out.data(1); out.u32(t); out.u8(EVENT_TIMER); out.u8(type)
        }

        event(start, EVENT_TYPE_START)
        val pauses = w.pauses.sortedBy { it.startMs }
        var p = 0
        for (s in w.samples.sortedBy { it.tMs }) {
            while (p < pauses.size && pauses[p].endMs <= s.tMs) {
                event(fitTime(pauses[p].startMs), EVENT_TYPE_STOP_ALL)
                event(fitTime(pauses[p].endMs), EVENT_TYPE_START)
                p++
            }
            out.data(2)
            out.u32(fitTime(s.tMs))
            out.u32((s.distanceM * 100).roundToLong())
            out.u16((s.speedMps * 1000).roundToLong().coerceIn(0, 0xFFFE))
        }
        event(end, EVENT_TYPE_STOP_ALL)

        // lap (global 19)
        out.define(3, 19, listOf(
            Field(254, 2, UINT16), Field(253, 4, UINT32), Field(0, 1, ENUM), Field(1, 1, ENUM),
            Field(2, 4, UINT32), Field(7, 4, UINT32), Field(8, 4, UINT32), Field(9, 4, UINT32),
            Field(11, 2, UINT16), Field(13, 2, UINT16), Field(14, 2, UINT16), Field(25, 1, ENUM), Field(39, 1, ENUM),
        ))
        out.data(3)
        out.u16(0); out.u32(end); out.u8(EVENT_LAP); out.u8(EVENT_TYPE_STOP)
        out.u32(start); out.u32(elapsedMs); out.u32(timerMs); out.u32(dist100)
        out.u16(calories); out.u16(avg); out.u16(max); out.u8(SPORT_WALKING); out.u8(SUB_SPORT_TREADMILL)

        // session (global 18)
        out.define(4, 18, listOf(
            Field(254, 2, UINT16), Field(253, 4, UINT32), Field(0, 1, ENUM), Field(1, 1, ENUM),
            Field(2, 4, UINT32), Field(5, 1, ENUM), Field(6, 1, ENUM), Field(7, 4, UINT32),
            Field(8, 4, UINT32), Field(9, 4, UINT32), Field(10, 4, UINT32), Field(11, 2, UINT16),
            Field(14, 2, UINT16), Field(15, 2, UINT16), Field(25, 2, UINT16), Field(26, 2, UINT16),
        ))
        out.data(4)
        out.u16(0); out.u32(end); out.u8(EVENT_SESSION); out.u8(EVENT_TYPE_STOP)
        out.u32(start); out.u8(SPORT_WALKING); out.u8(SUB_SPORT_TREADMILL)
        out.u32(elapsedMs); out.u32(timerMs); out.u32(dist100); out.u32(cycles)
        out.u16(calories); out.u16(avg); out.u16(max); out.u16(0); out.u16(1)

        // activity (global 34)
        out.define(5, 34, listOf(
            Field(253, 4, UINT32), Field(0, 4, UINT32), Field(1, 2, UINT16), Field(2, 1, ENUM),
            Field(3, 1, ENUM), Field(4, 1, ENUM), Field(5, 4, UINT32),
        ))
        out.data(5)
        out.u32(end); out.u32(timerMs); out.u16(1); out.u8(0); out.u8(EVENT_ACTIVITY); out.u8(EVENT_TYPE_STOP)
        out.u32(end + w.zoneOffsetSec)

        val body = out.bytes()
        val header = LeWriter().apply {
            u8(14); u8(0x20); u16(2132); u32(body.size.toLong())
            raw(".FIT".toByteArray(Charsets.US_ASCII))
        }.bytes()
        val headerCrc = crc(header)
        val file = LeWriter().apply {
            raw(header); u16(headerCrc.toLong()); raw(body)
        }.bytes()
        return LeWriter().apply { raw(file); u16(crc(file).toLong()) }.bytes()
    }

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    fun crc(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[v and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(v shr 4) and 0xF]
        }
        return crc
    }

    private class LeWriter {
        private val buf = ByteArrayOutputStream()
        fun u8(v: Int) = buf.write(v and 0xFF)
        fun u16(v: Long) { u8(v.toInt()); u8((v shr 8).toInt()) }
        fun u16(v: Int) = u16(v.toLong())
        fun u32(v: Long) { u16(v and 0xFFFF); u16((v shr 16) and 0xFFFF) }
        fun raw(b: ByteArray) = buf.write(b)
        fun bytes(): ByteArray = buf.toByteArray()

        fun define(local: Int, global: Int, fields: List<Field>) {
            u8(0x40 or local); u8(0); u8(0); u16(global); u8(fields.size)
            fields.forEach { u8(it.num); u8(it.size); u8(it.type) }
        }

        fun data(local: Int) = u8(local)
    }
}
