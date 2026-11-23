package app.fityoconnect.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtmsParserTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun speedDistanceEnergyAndElapsed() {
        // flags: distance (0x04) | energy (0x80) | elapsed (0x400); bit 0 clear so speed is present
        val d = FtmsParser.parseTreadmillData(bytes(
            0x84, 0x04,
            0x5E, 0x01,             // speed 3.50 km/h
            0xE8, 0x03, 0x00,       // distance 1000 m
            0x2A, 0x00, 0xFF, 0xFF, 0xFF, // energy 42 kcal, per hour/minute n/a
            0x3C, 0x00,             // elapsed 60 s
        ))!!
        assertEquals(3.5, d.speedKmh!!, 1e-9)
        assertEquals(1000, d.totalDistanceM)
        assertEquals(42, d.totalEnergyKcal)
        assertEquals(60, d.elapsedS)
        assertNull(d.inclinePct)
    }

    @Test
    fun moreDataFlagMeansNoSpeed() {
        val d = FtmsParser.parseTreadmillData(bytes(0x05, 0x00, 0x10, 0x00, 0x00))!!
        assertNull(d.speedKmh)
        assertEquals(16, d.totalDistanceM)
    }

    @Test
    fun inclineAndPaceAreSkippedCorrectly() {
        // speed | incline (0x08) | inst pace (0x20) | heart rate (0x100)
        val d = FtmsParser.parseTreadmillData(bytes(
            0x28, 0x01,
            0xC8, 0x00,        // 2.00 km/h
            0xF6, 0xFF, 0, 0,  // incline -1.0 %, ramp
            0x05,              // pace
            0x64,              // HR 100
        ))!!
        assertEquals(2.0, d.speedKmh!!, 1e-9)
        assertEquals(-1.0, d.inclinePct!!, 1e-9)
        assertEquals(100, d.heartRate)
    }

    @Test
    fun truncatedPacketKeepsDecodedFields() {
        val d = FtmsParser.parseTreadmillData(bytes(0x04, 0x00, 0x2C, 0x01, 0x10))!!
        assertEquals(3.0, d.speedKmh!!, 1e-9)
        assertNull(d.totalDistanceM)
    }

    @Test
    fun tooShort() {
        assertNull(FtmsParser.parseTreadmillData(bytes(0x00)))
    }
}
