package laptimer.core

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaceBoxTest {
    private val docFrame = SyntheticRaceBox.PROTOCOL_DOC_FRAME

    private fun decodeDocFrame(): GpsFix {
        val frames = UbxStreamParser().feed(docFrame)
        assertEquals(1, frames.size, "sample frame should pass the checksum")
        return assertNotNull(RaceBox.decodeFix(frames[0]))
    }

    @Test
    fun `encoding the sample payload reproduces the documented frame`() {
        val payload = docFrame.copyOfRange(Ubx.HEADER_LENGTH, docFrame.size - Ubx.CHECKSUM_LENGTH)
        assertContentEquals(docFrame, Ubx.encode(0xFF, 0x01, payload))
    }

    @Test
    fun `decodes position, motion and accuracy from the documented frame`() {
        val fix = decodeDocFrame()
        assertEquals(118_286_240L, fix.gpsTimeOfWeekMs)
        assertEquals(3, fix.fixType)
        assertTrue(fix.fixOk)
        assertEquals(11, fix.satellites)
        assertEquals(42.6719035, fix.latitudeDeg, 1e-9)
        assertEquals(23.2887238, fix.longitudeDeg, 1e-9)
        assertTrue(fix.positionValid)
        assertEquals(590.095, fix.altitudeMslM, 1e-9)
        assertEquals(0.924, fix.horizontalAccuracyM, 1e-9)
        assertEquals(1.836, fix.verticalAccuracyM, 1e-9)
        assertEquals(0.035, fix.speedMps, 1e-9)
        assertEquals(0.0, fix.headingDeg, 1e-9)
        assertEquals(0.208, fix.speedAccuracyMps, 1e-9)
        assertEquals(145.26856, fix.headingAccuracyDeg, 1e-9)
        assertEquals(3.0, fix.pdop, 1e-9)
    }

    @Test
    fun `decodes motion sensors and battery from the documented frame`() {
        val fix = decodeDocFrame()
        assertEquals(-0.003, fix.accelXG, 1e-9)
        assertEquals(0.113, fix.accelYG, 1e-9)
        assertEquals(0.974, fix.accelZG, 1e-9)
        assertEquals(-2.09, fix.rotationXDps, 1e-9)
        assertEquals(0.86, fix.rotationYDps, 1e-9)
        assertEquals(-0.04, fix.rotationZDps, 1e-9)
        assertEquals(89, RaceBox.miniBatteryPercent(fix.batteryRaw))
        assertFalse(RaceBox.miniIsCharging(fix.batteryRaw))
    }

    @Test
    fun `UTC time and GPS time of week in the documented frame differ by the leap seconds`() {
        val fix = decodeDocFrame()
        val utc = assertNotNull(fix.utcEpochNanos)
        val expected = LocalDateTime.of(2022, 1, 10, 8, 51, 8)
            .toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L + 239_971_626L
        assertEquals(expected, utc)

        // This checks the nanosecond field is read correctly. GPS time ran
        // 18 s ahead of UTC in 2022, and 2022-01-10 was a Monday, day 1 of
        // the GPS week. Time of week has millisecond resolution, so the
        // difference is 18 s to within half a millisecond.
        val utcNanosOfDay = utc % 86_400_000_000_000L
        val gpsNanosOfDay = (fix.gpsTimeOfWeekMs - 86_400_000L) * 1_000_000L
        val difference = gpsNanosOfDay - utcNanosOfDay
        assertTrue(kotlin.math.abs(difference - 18_000_000_000L) < 500_000L, "difference $difference ns")
    }

    @Test
    fun `negative nanosecond correction moves the time back`() {
        val payload = SyntheticRaceBox.payload(
            gpsTimeOfWeekMs = 0,
            utc = LocalDateTime.of(2026, 11, 18, 14, 0, 9),
            nanoCorrection = -1_000_000,
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
        )
        val fix = assertNotNull(RaceBox.decodeFix(UbxFrame(0xFF, 0x01, payload)))
        val expected = LocalDateTime.of(2026, 11, 18, 14, 0, 9)
            .toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L - 1_000_000L
        assertEquals(expected, fix.utcEpochNanos)
    }

    @Test
    fun `UTC is null until the receiver reports a valid date and time`() {
        val payload = SyntheticRaceBox.payload(
            gpsTimeOfWeekMs = 0, utc = null, latitudeDeg = 0.0, longitudeDeg = 0.0, fixType = 0,
        )
        val fix = assertNotNull(RaceBox.decodeFix(UbxFrame(0xFF, 0x01, payload)))
        assertNull(fix.utcEpochNanos)
        assertFalse(fix.fixOk)
    }

    @Test
    fun `west longitudes and south latitudes decode as negative`() {
        val payload = SyntheticRaceBox.payload(
            gpsTimeOfWeekMs = 0, utc = null, latitudeDeg = -33.8688, longitudeDeg = -81.0123,
        )
        val fix = assertNotNull(RaceBox.decodeFix(UbxFrame(0xFF, 0x01, payload)))
        assertEquals(-33.8688, fix.latitudeDeg, 1e-7)
        assertEquals(-81.0123, fix.longitudeDeg, 1e-7)
    }

    @Test
    fun `other messages and short payloads are not decoded as fixes`() {
        assertNull(RaceBox.decodeFix(UbxFrame(0xFF, 0x02, ByteArray(80))))
        assertNull(RaceBox.decodeFix(UbxFrame(0x01, 0x07, ByteArray(92))))
        assertNull(RaceBox.decodeFix(UbxFrame(0xFF, 0x01, ByteArray(40))))
    }
}
