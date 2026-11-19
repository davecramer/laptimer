package laptimer.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime

/** Builds RaceBox data messages for tests. */
object SyntheticRaceBox {
    /**
     * A sample Mini frame from the RaceBox BLE Protocol document, revision 8,
     * as transcribed in lgnap/RaceBoxMicroBleClient (test/test_parser.cpp).
     * It is 6 header bytes, an 80-byte payload, and 2 checksum bytes.
     */
    val PROTOCOL_DOC_FRAME: ByteArray = intArrayOf(
        0xB5, 0x62, 0xFF, 0x01, 0x50, 0x00,
        0xA0, 0xE7, 0x0C, 0x07, 0xE6, 0x07, 0x01, 0x0A, 0x08, 0x33, 0x08, 0x37,
        0x19, 0x00, 0x00, 0x00, 0x2A, 0xAD, 0x4D, 0x0E, 0x03, 0x01, 0xEA, 0x0B,
        0xC6, 0x93, 0xE1, 0x0D, 0x3B, 0x37, 0x6F, 0x19, 0x61, 0x8C, 0x09, 0x00,
        0x0F, 0x01, 0x09, 0x00, 0x9C, 0x03, 0x00, 0x00, 0x2C, 0x07, 0x00, 0x00,
        0x23, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xD0, 0x00, 0x00, 0x00,
        0x88, 0xA9, 0xDD, 0x00, 0x2C, 0x01, 0x00, 0x59, 0xFD, 0xFF, 0x71, 0x00,
        0xCE, 0x03, 0x2F, 0xFF, 0x56, 0x00, 0xFC, 0xFF,
        0x06, 0xDB,
    ).map { it.toByte() }.toByteArray()

    /**
     * Builds an 80-byte live-data payload. [utc] null means the receiver has
     * not yet resolved date and time, so the validity flags are cleared.
     */
    fun payload(
        gpsTimeOfWeekMs: Long,
        utc: LocalDateTime?,
        nanoCorrection: Int = 0,
        latitudeDeg: Double,
        longitudeDeg: Double,
        speedMps: Double = 0.0,
        headingDeg: Double = 0.0,
        horizontalAccuracyM: Double = 0.5,
        fixType: Int = 3,
        satellites: Int = 12,
    ): ByteArray {
        val b = ByteBuffer.allocate(RaceBox.DATA_PAYLOAD_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, gpsTimeOfWeekMs.toInt())
        if (utc != null) {
            b.putShort(4, utc.year.toShort())
            b.put(6, utc.monthValue.toByte())
            b.put(7, utc.dayOfMonth.toByte())
            b.put(8, utc.hour.toByte())
            b.put(9, utc.minute.toByte())
            b.put(10, utc.second.toByte())
            b.put(11, 0x07)
        }
        b.putInt(16, nanoCorrection)
        b.put(20, fixType.toByte())
        b.put(21, if (fixType >= 2) 0x01 else 0x00)
        b.put(23, satellites.toByte())
        b.putInt(24, Math.round(longitudeDeg * 1e7).toInt())
        b.putInt(28, Math.round(latitudeDeg * 1e7).toInt())
        b.putInt(40, Math.round(horizontalAccuracyM * 1000).toInt())
        b.putInt(48, Math.round(speedMps * 1000).toInt())
        b.putInt(52, Math.round(headingDeg * 1e5).toInt())
        b.putShort(72, 1000)
        return b.array()
    }

    fun frame(payload: ByteArray): ByteArray =
        Ubx.encode(RaceBox.MESSAGE_CLASS, RaceBox.ID_LIVE_DATA, payload)
}
