package app.treadsync.fit

import app.treadsync.ble.TreadmillData
import app.treadsync.workout.WorkoutRecorder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FitEncoderTest {
    @Test
    fun crcOfKnownInput() {
        // CRC-16/ARC of "123456789" is 0xBB3D, which is the FIT CRC.
        assertEquals(0xBB3D, FitEncoder.crc("123456789".toByteArray()))
    }

    @Test
    fun headerAndTrailingCrcAreValid() {
        val r = WorkoutRecorder(zoneOffsetSec = { -18000 })
        val t0 = 1_763_650_000_000L
        for (s in 0..600) r.onData(t0 + s * 1000L, TreadmillData(speedKmh = if (s in 200..230) 0.0 else 4.0, totalDistanceM = s, totalEnergyKcal = s / 20))
        val w = r.finish()!!
        val fit = FitEncoder.encode(w)

        assertEquals(14, fit[0].toInt())
        assertEquals(".FIT", String(fit, 8, 4, Charsets.US_ASCII))
        val dataSize = (fit[4].toInt() and 0xFF) or ((fit[5].toInt() and 0xFF) shl 8) or
            ((fit[6].toInt() and 0xFF) shl 16) or ((fit[7].toInt() and 0xFF) shl 24)
        assertEquals(fit.size - 14 - 2, dataSize)
        // CRC over the whole file including its trailing CRC is zero.
        assertEquals(0, FitEncoder.crc(fit))
        assertEquals(0, FitEncoder.crc(fit.copyOfRange(0, 14)))

        // Leave a sample for external validation (fitparse) in the build directory.
        System.getProperty("fitSampleOut")?.let { File(it).apply { parentFile?.mkdirs() }.writeBytes(fit) }
    }
}
