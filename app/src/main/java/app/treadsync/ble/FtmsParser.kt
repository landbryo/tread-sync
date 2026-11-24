package app.treadsync.ble

import java.util.UUID

/** Bluetooth SIG UUIDs used by the Fitness Machine Service (FTMS). */
object Uuids {
    private fun sig(short: Int): UUID =
        UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))

    val FTMS: UUID = sig(0x1826)
    val TREADMILL_DATA: UUID = sig(0x2ACD)
    val FTMS_CONTROL_POINT: UUID = sig(0x2AD9)
    val FTMS_STATUS: UUID = sig(0x2ADA)
    val CCCD: UUID = sig(0x2902)

    fun shortName(uuid: UUID): String {
        val s = uuid.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb")) "0x" + s.substring(4, 8).uppercase() else s
    }
}

/** One decoded Treadmill Data notification. Fields the treadmill did not send are null. */
data class TreadmillData(
    val speedKmh: Double? = null,
    val avgSpeedKmh: Double? = null,
    val totalDistanceM: Int? = null,
    val inclinePct: Double? = null,
    val totalEnergyKcal: Int? = null,
    val heartRate: Int? = null,
    val elapsedS: Int? = null,
)

/**
 * Parses the FTMS Treadmill Data characteristic (0x2ACD), little endian.
 *
 * Flag bit 0 ("More Data") is inverted: when it is 0, Instantaneous Speed is present.
 * Treadmills may split a reading across several notifications, so any field can be missing.
 */
object FtmsParser {
    fun parseTreadmillData(bytes: ByteArray): TreadmillData? {
        if (bytes.size < 2) return null
        val r = LeReader(bytes)
        val flags = r.u16()
        var speed: Double? = null
        var avgSpeed: Double? = null
        var distance: Int? = null
        var incline: Double? = null
        var energy: Int? = null
        var heartRate: Int? = null
        var elapsed: Int? = null
        try {
            if (flags and 0x0001 == 0) speed = r.u16() / 100.0
            if (flags and 0x0002 != 0) avgSpeed = r.u16() / 100.0
            if (flags and 0x0004 != 0) distance = r.u24()
            if (flags and 0x0008 != 0) {
                incline = r.s16() / 10.0
                r.skip(2) // ramp angle
            }
            if (flags and 0x0010 != 0) r.skip(4) // positive + negative elevation gain
            if (flags and 0x0020 != 0) r.skip(1) // instantaneous pace
            if (flags and 0x0040 != 0) r.skip(1) // average pace
            if (flags and 0x0080 != 0) {
                energy = r.u16().takeIf { it != 0xFFFF }
                r.skip(3) // energy per hour (u16) + per minute (u8)
            }
            if (flags and 0x0100 != 0) heartRate = r.u8().takeIf { it != 0 }
            if (flags and 0x0200 != 0) r.skip(1) // metabolic equivalent
            if (flags and 0x0400 != 0) elapsed = r.u16()
        } catch (_: IndexOutOfBoundsException) {
            // Truncated packet: keep the fields decoded so far.
        }
        return TreadmillData(speed, avgSpeed, distance, incline, energy, heartRate, elapsed)
    }
}

internal class LeReader(private val b: ByteArray) {
    private var i = 0
    private fun byte(): Int {
        if (i >= b.size) throw IndexOutOfBoundsException()
        return b[i++].toInt() and 0xFF
    }
    fun u8(): Int = byte()
    fun u16(): Int = byte() or (byte() shl 8)
    fun s16(): Int = u16().toShort().toInt()
    fun u24(): Int = byte() or (byte() shl 8) or (byte() shl 16)
    fun skip(n: Int) {
        if (i + n > b.size) throw IndexOutOfBoundsException()
        i += n
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
